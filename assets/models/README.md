# 模型文件目录

## 人脸身份识别模型（必需，用于「认识我」身份识别）

身份识别基于人脸特征向量（embedding）比对，需要一个 MobileFaceNet TFLite 模型。

请将模型文件放在此目录，并命名为：

```
assets/models/mobilefacenet.tflite
```

要求：

- 输入：`1 x 112 x 112 x 3`（RGB，float32，归一化到 [-1, 1]）
- 输出：`1 x 192`（或 128/512）的人脸特征向量

可用来源（任选其一，自行下载后改名为 `mobilefacenet.tflite`）：

- MobileFaceNet (112x112) TFLite，常见于 GitHub 上的 FaceNet/MobileFaceNet 移动端示例工程
- 任意输出固定维度 embedding 的人脸识别 TFLite 模型

如果该文件缺失，App 仍可正常运行：人脸表情、关键点、手势识别均可用，
仅「身份识别 / 认识我」功能会自动停用，并在设置页提示「未加载身份识别模型」。

## 人脸关键点 / 表情模型（必需，已随项目提供）

`kwon_mediapipe_landmarker` 插件**不自带**模型，需要由 App 放到原生资源目录：

- Android：`android/app/src/main/assets/face_landmarker.task` —— **已下载好，无需操作**
- iOS：`ios/Assets/face_landmarker.task` —— 文件已放好，但需要在 Mac 上用 Xcode
  打开 `ios/Runner.xcworkspace`，把该文件拖入 Runner target（勾选 “Copy items if
  needed” 与 Runner 的 “Target Membership”），否则打包时不会进入 App Bundle。

模型来源（float16）：
`https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task`

若该文件缺失，App 启动时会报：
`face_landmarker.task doesn't have a slash in it`（实为 MediaPipe 找不到模型）。

## 手势模型

手势（hand_detection）所需的 TFLite 模型已由插件内置打包，无需手动放置。

## 物体识别模型（YOLO11，由插件自动管理）

物体识别使用官方 `ultralytics_yolo` 插件 + **YOLO11**（COCO 80 类，含 cup /
bottle / cell phone / book / laptop / remote / banana 等常见手持物体）。

模型**无需手动放入本目录**：`lib/services/object_engine.dart` 里 `modelPath`
设为官方 ID `yolo11n`，插件会在首次运行时**自动下载并缓存**（因此首次启动需
联网；之后离线可用）。类名中文显示由 `object_engine.dart` 的 `_cocoZh` 映射。

### 改为完全离线（可选，推荐用于出厂固件）

若设备首次启动无网络，可把导出的模型随包提供：

1. 用 Ultralytics 导出 TFLite：`yolo export model=yolo11n.pt format=tflite`
   （Android 用 `.tflite`；iOS 用 Core ML `.mlpackage`）。
2. 放到 `assets/models/yolo11n.tflite`，并在 `pubspec.yaml` 的 assets 已含
   `assets/models/`（无需新增）。
3. 把 `object_engine.dart` 的 `_modelPath` 改为 `assets/models/yolo11n.tflite`。

### 想只认少数自定义物体 / 更高精度

用自有数据训练 YOLO11（`yolo train ...`）后按上面方式导出替换即可，
`_cocoZh` 按你的类名补充中文映射。

若模型加载失败（如首次无网络），App 仍正常运行：物体识别停用，状态页提示
「物体识别模型加载失败」，人脸/表情/手势/身份均不受影响。
