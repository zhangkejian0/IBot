package com.xbot.android.store

import android.content.Context
import android.util.Log
import com.xbot.android.model.Person
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 人物身份的本地持久化仓库（JSON 文件 + 头像图片目录）。
 * 对应 Flutter person_repository.dart。
 *
 * - people.json：全部人物（含 embeddings）
 * - avatars/：每人一张头像 jpg（裁剪后的人脸）
 */
class PersonRepository(private val context: Context) {

    companion object {
        private const val TAG = "PersonRepository"
        private const val FILE_NAME = "people.json"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val peopleList = ArrayList<Person>()
    private var loaded = false

    /** 当前人物库（不可变视图）。 */
    val people: List<Person> get() = peopleList.toList()

    private fun file(): File = File(context.filesDir, FILE_NAME)

    /** 头像保存目录。 */
    fun avatarsDir(): File {
        val dir = File(context.filesDir, "avatars")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 加载人物库；读取失败以空库启动，不阻塞应用。 */
    fun load() {
        if (loaded) return
        try {
            val f = file()
            if (f.exists()) {
                val raw = f.readText()
                val list = json.decodeFromString(ListSerializer(Person.serializer()), raw)
                peopleList.clear()
                peopleList.addAll(list)
                Log.i(TAG, "已加载 ${peopleList.size} 个人物")
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取 people.json 失败: ${e.message}")
        }
        loaded = true
    }

    private fun save() {
        try {
            file().writeText(json.encodeToString(ListSerializer(Person.serializer()), peopleList))
        } catch (e: Exception) {
            Log.e(TAG, "写入 people.json 失败: ${e.message}")
        }
    }

    fun upsert(person: Person) {
        val idx = peopleList.indexOfFirst { it.id == person.id }
        if (idx >= 0) peopleList[idx] = person else peopleList.add(person)
        save()
    }

    fun delete(id: String) {
        val person = peopleList.firstOrNull { it.id == id }
        peopleList.removeAll { it.id == id }
        // 顺带删除头像文件。
        person?.avatarPath?.let { path ->
            try { File(path).takeIf { it.exists() }?.delete() } catch (_: Exception) {}
        }
        save()
    }

    fun get(id: String): Person? = peopleList.firstOrNull { it.id == id }
}
