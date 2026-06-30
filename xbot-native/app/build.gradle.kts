plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.xbot.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xbot.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // sherpa-onnx / litert 原生库体积较大，按主流 ABI 打包以控制体积。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 暂用 debug 签名，便于 flutter run --release 等价的真机验证。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // MediaPipe tasks-vision / TFLite 需要从 assets 读取 .task / .tflite，开启不解压。
    androidResources {
        noCompress += listOf("task", "tflite", "onnx")
    }
}

// Kotlin 2.x：用顶层 kotlin{} 扩展的 compilerOptions DSL 设 JVM 目标
// （替代已弃用的 android.kotlinOptions.jvmTarget）。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // —— 相机：CameraX（取流在后台 executor，这是换原生的核心动机）——
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // —— MediaPipe Tasks Vision：人脸/手势/姿态/手势识别，原生 Java API，后台线程推理 ——
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // —— ML Kit：多人脸检测（包围盒，用于身份识别裁剪）——
    implementation("com.google.mlkit:face-detection:16.1.7")

    // —— TFLite：MobileFaceNet 身份识别 + YOLO26 物体识别 ——
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("com.google.ai.edge.litert:litert-support:1.4.1")

    // —— WebView：虚拟形象前端（复用 assets/dist 产物，WebViewAssetLoader 托管）——
    implementation("androidx.webkit:webkit:1.12.1")

    // —— 网络：OkHttp（Pophie 后端 HTTP/NDJSON 流式 + WebSocket 流式 STT）【阶段3 启用】——
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // —— 唤醒词：sherpa-onnx（开放词表 KWS）【阶段3 启用】——
    // 官方 sherpa-onnx 未发布可直接消费的 Maven AAR；阶段3 改为：
    //   方案A: 从 k2-fsa/sherpa-onnx releases 下载预编译 sherpa-onnx-v{ver}.aar 放 app/libs/
    //   方案B: 用 com.bihe0832.android:lib-sherpa-onnx（Maven Central 第三方包装）
    // 当前先注释，避免阶段0/1/2 构建被阻。
    // implementation(files("libs/sherpa-onnx.aar"))

    // —— 局域网日志浏览服务（人物日志 / 网络日志看板）【阶段4 启用】——
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // —— JSON（kotlinx-serialization，需在顶层 plugins 应用 plugin("kotlinx-serialization")）——
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // —— Jetpack Compose ——
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // —— 生命周期 + UI 基础 ——
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // —— 协程 ——
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
