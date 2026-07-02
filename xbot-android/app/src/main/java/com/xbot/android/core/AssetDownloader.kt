package com.xbot.android.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * OkHttp 流式下载单文件，支持断点续传 + 完整性校验。
 *
 * 流程：
 * 1. 若 `.tmp` 已存在，发 `Range: bytes=<tmpSize>-` 续传；服务器不支持 206 则重头。
 * 2. 边读边追加写 `.tmp`，每个 chunk 回调 [onProgress]。
 * 3. 完成后校验 size（必）+ sha256（spec 提供时）。
 * 4. 通过则原子 rename `.tmp` → 正式文件名；失败抛异常（保留 `.tmp` 供下次续传）。
 *
 * 单文件串行下载（由 [AssetDownloadScreen] 顺序调度多个 spec），
 * 避免多文件并行抢带宽；断点续传保证弱网可恢复。
 */
class AssetDownloader(
    /** CDN 基址（含末尾斜杠），与 [ResourceManager.BASE_URL] 一致。 */
    private val baseUrl: String,
    /** 资源管理器（提供本地文件路径）。 */
    private val resources: ResourceManager,
) {
    companion object {
        private const val TAG = "AssetDownloader"
        /** 单 chunk 上限（8KB），频繁回调进度，UI 平滑。 */
        private const val BUF_SIZE = 8 * 1024
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // 大文件不限总时长
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 下载一个 [spec]。
     *
     * @param onProgress (已读字节, 总字节) 回调；总字节未知时为 -1。
     * @throws IOException 网络/IO/校验失败
     */
    suspend fun download(spec: AssetSpec, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val dest = resources.localFile(spec.path)
        // 已正式下载完成且校验通过 → 跳过。
        if (dest.exists() && matchesSize(dest, spec)) {
            Log.i(TAG, "已存在且校验通过，跳过: ${spec.path}")
            onProgress(spec.size.coerceAtLeast(dest.length()), spec.size.coerceAtLeast(dest.length()))
            return@withContext
        }

        val tmp = resources.tmpFile(spec.path)
        tmp.parentFile?.mkdirs()
        val existing = if (tmp.exists()) tmp.length() else 0L
        if (existing > 0) Log.i(TAG, "断点续传: ${spec.path}，已有 ${existing} 字节")

        val url = baseUrl + spec.path
        val builder = Request.Builder().url(url).get()
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        var resp: Response? = null
        try {
            resp = client.newCall(builder.build()).execute()
            val code = resp.code
            val body = resp.body ?: throw IOException("空响应体 ($code)")

            // 续传协商：206 = 服务器接受 Range 续传；200 = 忽略 Range 从头发（需重置 tmp）。
            val appending = code == 206
            val total = bodyExpectedTotal(spec, resp, appending, existing)
            if (code == 200 && existing > 0) {
                Log.w(TAG, "服务器不支持续传(200)，重头下载: ${spec.path}")
            }
            if (!resp.isSuccessful && code != 206) {
                throw IOException("HTTP $code 下载失败: ${spec.path}")
            }

            writeStream(body.byteStream(), tmp, appending, existing, total, onProgress)
            ensureActive() // 协程取消时及时抛出
        } finally {
            resp?.close()
        }

        // 校验 + 原子落盘。
        verify(tmp, spec)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            // rename 跨挂载点会失败，回退到拷贝。
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        Log.i(TAG, "下载完成: ${spec.path} (${dest.length()} 字节)")
    }

    /** 把响应流追加/覆盖写到 [tmp]，定期回调进度。 */
    private fun writeStream(
        input: java.io.InputStream,
        tmp: File,
        appending: Boolean,
        already: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val raf = RandomAccessFile(tmp, "rw")
        try {
            if (appending) {
                raf.seek(already) // 追加到断点
            } else {
                raf.setLength(0) // 重头
            }
            val buf = ByteArray(BUF_SIZE)
            var written = if (appending) already else 0L
            onProgress(written, total)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                raf.write(buf, 0, n)
                written += n
                onProgress(written, total)
            }
            // 确保数据落盘（rename 前刷一次）。
            raf.fd.sync()
        } finally {
            raf.close()
        }
    }

    /** 推算总字节：manifest 给定 > Content-Range 总长 > Content-Length(续传加已有) > -1 未知。 */
    private fun bodyExpectedTotal(
        spec: AssetSpec, resp: Response, appending: Boolean, existing: Long,
    ): Long {
        if (spec.size > 0) return spec.size
        if (appending) {
            val cr = resp.header("Content-Range") // 形如 "bytes 100-999/2000"
            val slash = cr?.indexOf('/')?.plus(1)
            if (slash != null && slash > 0) {
                cr.substring(slash).toLongOrNull()?.let { return it }
            }
        }
        return resp.body?.contentLength()?.let { if (it > 0) it + if (appending) existing else 0L else -1L } ?: -1L
    }

    /** 校验 size（必）+ sha256（spec 提供时）。失败抛异常并保留 tmp 供续传。 */
    private fun verify(tmp: File, spec: AssetSpec) {
        val actual = tmp.length()
        if (spec.size > 0 && actual != spec.size) {
            throw IOException("size 不符：期望 ${spec.size}，实际 $actual（${spec.path}）")
        }
        val expectSha = spec.sha256
        if (!expectSha.isNullOrEmpty()) {
            val actualSha = sha256(tmp)
            if (!actualSha.equals(expectSha, ignoreCase = true)) {
                throw IOException("sha256 不符：期望 $expectSha，实际 $actualSha（${spec.path}）")
            }
        }
    }

    private fun matchesSize(f: File, spec: AssetSpec): Boolean =
        spec.size <= 0L || f.length() == spec.size

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
