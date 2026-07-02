package com.xbot.android.core

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 可下载模型资源的中央管理器。
 *
 * 设计动机：APK 只打包小模型（唤醒词/降噪/视觉，~26MB），重型模型
 * （ASR paraformer 237MB + 声纹 CAM++ 28MB）改为首次启动从 CDN 下载，
 * 落到 [downloadRoot]（filesDir/xbot_models/），目录结构镜像 assets 布局。
 *
 * 职责：
 * 1. 解析「下载型 vs 内置型」模型路径——下载型返回绝对路径，内置型返回 assets 相对路径。
 * 2. 据 [Manifest] 判定重型模型是否就绪（[isReady]）。
 * 3. 提供下载目标文件句柄 [localFile] / [tmpFile]。
 *
 * @see AssetDownloader 下载执行器
 * @see AssetDownloadScreen 首次下载页
 */
class ResourceManager(private val context: Context) {

    companion object {
        private const val TAG = "ResourceManager"
        private const val DOWNLOAD_DIR = "xbot_models"
        private const val MANIFEST_FILE = "manifest.json"
        private const val READY_MARKER = ".downloaded"

        /**
         * 资源基址（含末尾斜杠）。开发/测试期指向七牛云 Kodo 对象存储的公开访问域名；
         * 部署到其它平台（Cloudflare R2 / GitHub Releases / 自建 nginx）只改这里。
         *
         * 当前：七牛测试域名（HTTP，30 天过期）。生产建议绑自有域名 + HTTPS。
         * 注：Android 9+ 默认禁明文 HTTP，已在 AndroidManifest 开启 usesCleartextTraffic。
         */
        const val BASE_URL = "http://thj8lagfk.hd-bkt.clouddn.com/"

        /** manifest.json 的完整 URL。 */
        val MANIFEST_URL: String = BASE_URL + MANIFEST_FILE

        /**
         * 需要从 CDN 下载的重型模型相对路径（镜像 assets 布局）。
         * 与 [Manifest.assets] 一一对应；运行时直接用作硬编码兜底（manifest 拉取失败时）。
         */
        val HEAVY_ASSETS: List<String> = listOf(
            "voice/sherpa-onnx-streaming-paraformer-bilingual-zh-en/encoder.int8.onnx",
            "voice/sherpa-onnx-streaming-paraformer-bilingual-zh-en/decoder.int8.onnx",
            "voice/sherpa-onnx-streaming-paraformer-bilingual-zh-en/tokens.txt",
            "voice/sherpa-onnx-3dspeaker-campplus-zh-cn-16k-common/speaker.onnx",
        )

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    /** 下载根目录：filesDir/xbot_models/。 */
    val downloadRoot: File = File(context.filesDir, DOWNLOAD_DIR)

    /** 已加载的 manifest（manifest.json 拉取/本地缓存后填充）；null 表示尚未加载。 */
    @Volatile
    var manifest: Manifest? = null
        private set

    /** manifest 本地缓存路径（拉取后落盘，断网时仍可判定就绪）。 */
    private fun manifestCacheFile(): File = File(downloadRoot, MANIFEST_FILE)

    /** 就绪标记文件路径（所有重型文件下载校验通过后创建）。 */
    private fun readyMarkerFile(): File = File(downloadRoot, READY_MARKER)

    init {
        downloadRoot.mkdirs()
    }

    // ============ 路径解析 ============

    /** 某相对路径下载后的正式文件句柄（可能尚不存在）。 */
    fun localFile(relPath: String): File = File(downloadRoot, relPath)

    /** 下载临时文件句柄（断点续传写它，完成后原子 rename 到 [localFile]）。 */
    fun tmpFile(relPath: String): File = File(downloadRoot, "$relPath.tmp")

    // ============ 就绪判定 ============

    /**
     * 重型模型是否全部就绪——存在就绪标记文件。
     *
     * 标记由 [markReady] 在所有文件下载校验通过后创建；
     * [clearReady] 删除标记（用于「重新下载」/失效场景）。
     */
    fun isReady(): Boolean = readyMarkerFile().exists()

    /**
     * 按 manifest 逐文件校验（存在 + size 匹配）。无 manifest 时按 [HEAVY_ASSETS] 仅查存在性。
     *
     * 比 [isReady] 更严格——用于下载完成后的最终验证，或排查标记与实际文件不一致。
     */
    fun verifyAll(): Boolean {
        val specs = manifest?.assets ?: HEAVY_ASSETS.map { AssetSpec(it, 0L, null) }
        return specs.all { spec ->
            val f = localFile(spec.path)
            f.exists() && (spec.size <= 0L || f.length() == spec.size)
        }
    }

    /** 标记资源就绪（创建空标记文件）。调用前应确保 [verifyAll] 为真。 */
    fun markReady() {
        try {
            readyMarkerFile().createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "无法创建就绪标记: ${e.message}")
        }
    }

    /** 清除就绪标记（触发重新进入下载页）。不删模型文件，故重下可走断点续传。 */
    fun clearReady() {
        readyMarkerFile().delete()
    }

    /**
     * 清除所有下载的模型文件 + 标记（彻底重下）。
     * 谨慎：会删除已下完的几百 MB，下次必须重头下载。
     */
    fun clearAll() {
        clearReady()
        downloadRoot.listFiles()?.forEach { it.deleteRecursively() }
        downloadRoot.mkdirs()
    }

    // ============ Manifest 加载 ============

    /**
     * 从本地缓存加载 manifest（应用启动时调用，不触发网络）。
     * 缓存不存在时用 [HEAVY_ASSETS] 兜底（size=0 表示不校验精确大小，仅查存在性）。
     */
    fun loadCachedManifest() {
        val cache = manifestCacheFile()
        manifest = try {
            if (cache.exists()) {
                json.decodeFromString(Manifest.serializer(), cache.readText()).also {
                    Log.i(TAG, "已加载本地 manifest（v${it.version}，${it.assets.size} 项）")
                }
            } else {
                Manifest(assets = HEAVY_ASSETS.map { AssetSpec(it, 0L, null) })
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取本地 manifest 失败，用兜底清单: ${e.message}")
            Manifest(assets = HEAVY_ASSETS.map { AssetSpec(it, 0L, null) })
        }
    }

    /**
     * 用远端 manifest 内容覆盖本地（manifest.json 下载成功后调用）。
     * 落盘缓存 + 更新 [manifest] 字段。
     */
    fun applyRemoteManifest(jsonText: String): Manifest {
        val parsed = json.decodeFromString(Manifest.serializer(), jsonText)
        manifest = parsed
        try {
            downloadRoot.mkdirs()
            manifestCacheFile().writeText(jsonText)
        } catch (e: Exception) {
            Log.w(TAG, "缓存 manifest 失败: ${e.message}")
        }
        return parsed
    }
}

/** 可下载资源清单（manifest.json）。 */
@Serializable
data class Manifest(
    /** 清单版本（手动递增，便于强制重下）。 */
    val version: Int = 1,
    /** 资源条目列表。 */
    val assets: List<AssetSpec> = emptyList(),
)

/** 单个可下载资源条目。path 相对 [ResourceManager.downloadRoot] / CDN 根。 */
@Serializable
data class AssetSpec(
    /** 相对路径（如 "voice/.../encoder.int8.onnx"）。 */
    val path: String,
    /** 文件字节数（用于进度加权 + 下载后校验）。0 表示不校验。 */
    val size: Long,
    /** SHA-256（小写十六进制）；null 表示不校验。165MB 算 sha 较慢，默认不强制。 */
    val sha256: String? = null,
)
