plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xbot.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xbot.prototype"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-prototype"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // MediaPipe Tasks Vision 需要从 assets 读 .task 模型,开启不解压。
    androidResources {
        noCompress += listOf("task", "tflite")
    }
}

dependencies {
    // —— 相机:CameraX(取流在后台线程,这是换原生的核心动机) ——
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // —— MediaPipe Tasks Vision:人脸/手势/姿态,原生 Java API,后台线程推理 ——
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // —— WebView:表情页(复用 assets/html/dist 产物) ——
    implementation("androidx.webkit:webkit:1.12.1")

    // —— 生命周期 + UI ——
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.12.0")
}
