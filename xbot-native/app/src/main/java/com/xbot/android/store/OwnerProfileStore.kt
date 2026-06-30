package com.xbot.android.store

import android.content.Context
import android.util.Log
import com.xbot.android.model.OwnerProfile
import java.io.File

/**
 * 主人档案的本地持久化仓库。对应 Flutter owner_profile_store.dart。
 *
 * owner_profile.json 存在 = 已注册；不存在 = 未注册（进入首次激活向导）。
 */
class OwnerProfileStore(private val context: Context) {

    companion object {
        private const val TAG = "OwnerProfileStore"
        private const val FILE_NAME = "owner_profile.json"
    }

    var profile: OwnerProfile? = null
        private set

    /** 是否已完成首次激活注册（档案文件存在）。 */
    val isRegistered: Boolean get() = profile != null

    private fun file(): File = File(context.filesDir, FILE_NAME)

    fun load() {
        try {
            val f = file()
            if (f.exists()) {
                profile = OwnerProfile.fromJson(f.readText())
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取 owner_profile.json 失败: ${e.message}")
        }
    }

    fun save(profile: OwnerProfile) {
        this.profile = profile
        try {
            file().writeText(profile.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "写入 owner_profile.json 失败: ${e.message}")
        }
    }

    fun clear() {
        profile = null
        try { file().takeIf { it.exists() }?.delete() } catch (_: Exception) {}
    }
}
