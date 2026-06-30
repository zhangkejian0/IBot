// 顶层 build 文件。插件版本在此统一声明。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false  // Kotlin 2.0+ 必须
    // KSP/Room 后续阶段再加,原型先用 SharedPreferences + JSON 文件
}
