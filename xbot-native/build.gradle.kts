// 顶层 build 文件。插件版本在此统一声明。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // Compose 编译器插件（Kotlin 2.x 起 Compose 编译器与 Kotlin 版本解耦，需显式声明）。
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    // kotlinx-serialization 插件（kotlinx-serialization-json 运行时库需要）。
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
