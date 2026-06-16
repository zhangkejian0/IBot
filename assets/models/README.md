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
