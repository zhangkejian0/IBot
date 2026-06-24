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

## 物体识别模型（YOLO26，已内置，完全离线）

物体识别使用官方 `ultralytics_yolo` 插件 + **YOLO26**（COCO 80 类，含 cup /
bottle / cell phone / book / laptop / remote / banana 等常见手持物体）。类名
中文显示由 `object_engine.dart` 的 `_cocoZh` 映射。

### 当前内置模型

| 文件 | 量化 | 说明 |
| --- | --- | --- |
| `yolo26n_int8.tflite` | int8（量化） | **默认**。约 3MB。插件 0.6.5 原生后处理按 YOLO26 架构设计，匹配度最好。 |

`object_engine.dart` 的 `_modelCandidates` 设为官方 ID `yolo26n`，插件优先用本
目录内置的 `yolo26n_int8.tflite`（离线可用），找不到才回退联网下载。

> ⚠️ **不要换成 `yolo11n`**：它是不同架构，且可下载的旧 v0.2.0 导出与插件
> 0.6.5 的原生解码器不匹配，实测识别效果反而更差。提升精度要用**同架构**的
> 非量化 yolo26n（见下）。

### 想要更高精度：换同架构的非量化 yolo26n（可选）

int8 量化的 nano 模型对相近小物体（水杯/书/手机）区分略弱。要更准，导出
**非量化的 yolo26n**（保持同架构，避免换 yolo11 的劣化）：

```bash
# float16（精度≈float32，体积适中，推荐移动端）
yolo export model=yolo26n.pt format=tflite half=True   # 生成 yolo26n_float*.tflite
# 或 float32（最准、最大）
yolo export model=yolo26n.pt format=tflite
```

导出后改名为 `yolo26n_float.tflite` 放入本目录，并把 `object_engine.dart` 的
`_modelCandidates` 改为：

```dart
static const List<String> _modelCandidates = [
  'assets/models/yolo26n_float.tflite', // 非量化,精度优先
  'yolo26n',                             // 回退:内置 int8
];
```

`pubspec.yaml` 已包含 `assets/models/`，无需改动。

### 想只认少数自定义物体 / 更高精度

用自有数据训练 YOLO26（`yolo train ...`）后按上面方式导出替换即可，
`_cocoZh` 按你的类名补充中文映射。

若模型加载失败（如首次无网络），App 仍正常运行：物体识别停用，状态页提示
「物体识别模型加载失败」，人脸/表情/手势/身份均不受影响。
