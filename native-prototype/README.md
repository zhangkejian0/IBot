# XBot 原生 Android 原型

> **唯一目的**:验证「CameraX 后台线程 + MediaPipe 推理」时,WebView 表情是否流畅。
> 用这个原型拿到的数据,决定是否值得把整个 Flutter App 迁移到原生。

## 为什么做这个原型

Flutter 方案卡顿根因(实测确认):相机取流/推理与 WebView 合成抢同一个 Android 主线程,
且 camera 插件反复 start/stop 触发 CameraX session 重建(`Skipped 31 frames`)。

原生方案的理论优势:相机取流 + 推理全程在**后台线程**(`Executors.newSingleThreadExecutor`),
字节不离开原生层、不经过 platform channel、不触发 session 重建。主线程只负责 WebView 合成
和接收几个坐标数字。

**这个原型就是用最小代码验证「理论优势是否成立」。**

## 工程结构

```
native-prototype/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat       ← 从现有项目复制,命令行可直接用
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts        ← CameraX 1.4.2 + MediaPipe tasks-vision 0.10.14
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── face_landmarker.task    ← 人脸模型(从现有项目复制,无需下载)
        │   └── dist/                   ← **真实表情页**(从 assets/html/dist/ 复制,
        │         ├── index.html          含 AmbientFace + 全套 SVG 渲染)
        │         └── assets/
        │             └── index-xxx.js
        ├── res/layout/activity_main.xml   ← 全屏 WebView + 右上角调试浮层
        └── java/com/xbot/prototype/
            ├── MainActivity.kt   ← 权限/相机绑定(后台executor)/WebView(真实dist)+统计
            └── FaceAnalyzer.kt   ← CameraX Analyzer + MediaPipe(后台线程同步推理)
```

## 怎么跑(约 15 分钟)

### 1. 模型(已自带,无需下载)

`face_landmarker.task` 已从现有 Flutter 项目(`android/app/src/main/assets/`)复制到
`native-prototype/app/src/main/assets/`。它与原型的 MediaPipe tasks-vision 官方 Java API
完全兼容(同一个标准 `.task` 格式 = ZIP 含 model.tflite + 元数据)。

如需重新复制:
```
cp android/app/src/main/assets/face_landmarker.task native-prototype/app/src/main/assets/
```

### 2. 用 Android Studio 打开

用 Android Studio **直接打开 `native-prototype/` 目录**(不是 XBot 根目录)。
首次打开会:
- 自动下载 Gradle 8.14(从 gradle-wrapper.properties 指定)
- 自动补 `gradle-wrapper.jar`
- 自动同步 CameraX / MediaPipe 依赖

SDK 要求(与现有 Flutter 项目对齐):compileSdk 36、minSdk 24、JDK 17。

### 3. 真机运行

数据线连真机(需 API 24+),点 Run。首次会弹相机权限,允许。

## 看什么(判断依据)

运行后,**对着摄像头左右上下移动脸**,观察:

### 屏幕上(右上角调试浮层)
```
原生后台线程
推理: 18.0 fps
耗时: 42 ms/帧
人脸: 1
```
- **耗时**:原生后台线程单帧 MediaPipe 推理耗时。这是「不阻塞主线程」的成本。
- **推理 fps**:后台线程实际处理帧率(受 KEEP_ONLY_LATEST + 推理耗时约束)。

### logcat(关键!)
过滤 `XBotProto` 和 `Choreographer`:

```
I/XBotProto: 相机管线已启动(后台线程推理)
I/XBotProto: 表情页加载完成,开始注入注视

I/Choreographer: Skipped N frames!   ← 如果这行很少/消失,说明主线程不再被占满
```

**判断标准**:
- ✅ **WebView 瞳孔跟随流畅 + 几乎没有 `Skipped frames`** → 原生方案成立,值得迁移。
- ⚠️ **WebView 流畅但仍偶有 `Skipped frames`** → 比 Flutter 好,但仍有优化空间(可加 GPU 委托)。
- ❌ **仍卡** → 说明这台设备本身推理太慢,换原生也救不了,需降模型/降分辨率。

## 与 Flutter 方案的对照(为何这个架构能解决问题)

| | Flutter(现状) | 原生原型 |
|---|---|---|
| 相机帧投递 | platform channel → Dart 主 isolate | CameraX → 后台 executor |
| 推理线程 | native 线程跑,但结果回调压主线程 | 全程后台线程,不碰主线程 |
| session 重建 | 反复 start/stop 触发(每帧 2 次) | 不 start/stop,**0 次** |
| 主线程占用 | 被推理回调 + 通道往返持续挤占 | 只接几个坐标(JS 注入) |
| 跨平台 | ✅ iOS 共用 | ❌(原型只 Android) |

## 原型的简化(迁移时要补的)

为聚焦验证目标,原型有意省略了:
- **手势/姿态/物体/身份识别**:只做人脸,够验证线程模型。
- **真实表情页**:用简化 HTML 代替完整 React 工程(接口一致,渲染负载相近)。
- **语音助手 / 蓝牙底座 / 人物日志**:不涉及。
- **iOS**:只 Android。

这些在「确认原生可行」后再按迁移计划逐项补。

## 下一步

原型跑通后,把 logcat 结果(尤其是 `Skipped frames` 出现频率)告诉我,
我据此判断「是否值得启动完整迁移」以及「迁移该怎么排期」。
