import org.gradle.api.file.RelativePath

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

configurations.all {
    // flutter_litert 依赖 litert:1.4.1(传递引入 litert-api:1.4.1),
    // ultralytics_yolo 依赖 litert:2.1.5。版本仲裁把 litert 升到 2.1.5(已把
    // org.tensorflow.lite.* 类合并进主包),但残留的 litert-api:1.4.1 含同名类,
    // 触发 checkDuplicateClasses 失败。这里排除独立的 litert-api 旧包,
    // 由 litert:2.1.5 统一提供这些 API 类。
    exclude(group = "com.google.ai.edge.litert", module = "litert-api")
}

// flutter_litert / hand_detection 在运行时通过 FFI dlopen('libtensorflowlite_jni.so'),
// 该 .so 仅由 litert:1.4.x 打包。但上面的版本仲裁把 litert 升到 2.1.5(LiteRT Next),
// 2.1.5 改名为 libLiteRt.so 且不再含 libtensorflowlite_jni.so,导致运行时
// 「dlopen failed: library "libtensorflowlite_jni.so" not found」、手势 isolate 初始化失败。
//
// 不能直接同时引入 1.4.1 完整 AAR(其 org.tensorflow.lite.* 类与 2.1.5 重复,
// 触发 checkDuplicateClasses)。这里用独立配置仅下载 1.4.1 的 AAR,
// 抽取其中的 libtensorflowlite_jni.so 注入 jniLibs —— 既保留 2.1.5 供 ultralytics_yolo
// 编译/运行,又补回 flutter_litert 需要的核心原生库。
val litertJniConfig: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    add("litertJniConfig", "com.google.ai.edge.litert:litert:1.4.1@aar")
}

val litertJniOutDir = layout.buildDirectory.dir("litert-jni-1.4.1")

val extractLitertJni = tasks.register<Copy>("extractLitertJni") {
    from({ litertJniConfig.map { zipTree(it) } }) {
        include("jni/**/libtensorflowlite_jni.so")
        // AAR 内为 jni/<abi>/lib.so;去掉首层 jni/,使其符合 jniLibs 的 <abi>/lib.so 布局。
        eachFile {
            relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    into(litertJniOutDir)
}

tasks.named("preBuild") {
    dependsOn(extractLitertJni)
}

android {
    namespace = "com.xbot.xbot"
    // ultralytics_yolo 要求 compileSdk>=36、NDK 28.2.x;取较高值对齐,避免
    // 「compileSdk 36 required」构建失败,以及多插件 NDK 版本不一致告警。
    compileSdk = maxOf(flutter.compileSdkVersion, 36)
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    // 把从 litert:1.4.1 抽取出来的 libtensorflowlite_jni.so 作为 jniLibs 源目录,
    // 补回被 2.1.5 版本仲裁挤掉的核心原生库(见文件顶部说明)。
    sourceSets["main"].jniLibs.srcDir(litertJniOutDir)

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.xbot.xbot"
        // MediaPipe / OpenCV / ML Kit 需要较新的 minSdk。
        minSdk = maxOf(flutter.minSdkVersion, 24)
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            // MediaPipe 通过栈回溯加载原生库，R8 混淆会导致运行时
            // "no caller found on the stack" 崩溃，这里关闭代码压缩/混淆。
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        jniLibs {
            // ultralytics_yolo 与 flutter_litert 都打包 LiteRT/TFLite 原生库,
            // 合并时可能因同名 .so 报「More than one file ...」。pickFirst 取其一,
            // 避免重复库导致打包失败(同库不同副本,取首个即可)。
            pickFirsts += setOf(
                "**/libtensorflowlite_jni.so",
                "**/libtensorflowlite_gpu_jni.so",
                "**/libtensorflowlite_c.so",
                "**/liblitert_jni.so",
            )
        }
    }
}

flutter {
    source = "../.."
}
