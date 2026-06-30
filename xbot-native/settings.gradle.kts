pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx Android AAR 发布在 JitPack。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "XBotNative"
include(":app")
