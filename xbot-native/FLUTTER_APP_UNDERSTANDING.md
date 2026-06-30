# XBot Flutter App — 完整功能理解（用于原生 Android 复刻）

> 本文是逐文件精读后的功能与实现梳理，目标是把 Flutter App 的**所有功能**与**关键实现细节**讲清楚，
> 作为原生 Android（Kotlin）复刻的需求基线。`native-prototype/` 仅验证了「CameraX 后台线程 + MediaPipe 推理」
> 这一个最小环节（人脸中心 → WebView 注视），本文覆盖的是**完整 App**。

源码规模：`lib/` 约 **8,800 行 Dart** + `assets/html/` React/TS 虚拟形象前端（Vite 产物 `dist/`）。

---

## 一、产品定位

XBot 是一台**桌面陪伴机器人**的 App 端。强制**横屏全屏 + 屏幕常亮 + 沉浸式**，默认显示一个**虚拟形象（React/SVG 脸）**，
用摄像头持续感知面前的人，做到：

1. **虚拟形象注视跟随**：瞳孔跟着主脸在画面中的位置移动（始终"看着主人"）。
2. **情绪/行为驱动表情**：用时序聚合后的注意力态（专注/困倦…）驱动虚拟形象状态。
3. **语音助手**：唤醒词 → 流式 ASR → LLM → 流式 TTS，端侧感知（表情/身份/手势/物体）随对话上传给大模型。
4. **人物日志**：按天持久化记录在场人物/表情/物体/对话，局域网网页可查。
5. **底座控制**（可选）：经典蓝牙 SPP 控制物理旋转底座。
6. **首次激活向导**：采集主人昵称/性别/生日/人脸，端侧建身份库。

显示分两模式（设置切换）：**虚拟宠物模式（默认）** vs **调试模式**（摄像头预览 + 识别覆盖层）。识别在两种模式下都常驻运行。

---

## 二、应用骨架（`lib/main.dart` / `lib/app.dart` / `lib/core/`）

### 2.1 启动流程（`main.dart`）
- `WidgetsFlutterBinding.ensureInitialized()`
- 强制横屏（`landscapeLeft`/`landscapeRight`）
- 沉浸式全屏（隐藏状态栏/导航栏，`immersiveSticky`）
- 屏幕常亮（`WakelockPlus.enable`）
- `runApp(XBotApp())`

### 2.2 根路由（`app.dart`）
`XBotApp` 持有唯一的 `AppController`（`ChangeNotifier`），通过 `AppScope`（`InheritedNotifier`）注入子树。
根 Widget 根据 `controller.phase` 分流：
| `AppPhase` | 显示 |
|---|---|
| `loading` / `error` / `permissionDenied` | `LoadingScreen` |
| `onboarding` | `OnboardingScreen`（首次激活向导） |
| `ready` | `CameraScreen`（主界面） |

机器人昵称随主人档案变化：未注册显示默认"狗蛋"。

### 2.3 AppController（核心，~1770 行）—— 整个 App 的大脑
职责：权限 → 相机 → 加载所有模型 → 数据 → 按需采样 → 帧分发 → 聚合 → 驱动 UI/语音。

**初始化序列**（`initialize()`）：
1. 请求摄像头权限；失败 → `permissionDenied`
2. `_initCamera()`（CameraX 取流，详见 §4）
3. 请求麦克风权限；成功 → `voiceAssistant.markAvailable()`；后台 `voiceAssistant.initialize()`（不阻塞 ready）
4. 依次 `await` 加载：`faceEngine`（MediaPipe 人脸+姿态）→ `mlkitFaceEngine`（多脸框）→ `handEngine`（手势）→ `objectEngine`（YOLO）→ `faceRecognition`（MobileFaceNet）→ `personRepository.load()`
5. 初始化 `personaLogger` / `networkLogger`；按开关启动 `personaLogServer`
6. 加载 `llmConfigStore` / `pophieConfigStore`，注入 voiceAssistant，`_wireVoicePerception()`
7. 加载 `ownerProfileStore`（决定是否进向导）
8. `_startSampling()`；等待 `readyBuffer`（2s）后分流：已注册 → `ready` + `_ensureVoiceStartedOnReady()`；未注册 → `onboarding`

**关键架构决策——按需采样（替代持续帧流）**：
Flutter 方案卡顿根因：`webview_flutter` 在 Android 走 Hybrid Composition，WebView 合成在主线程；而 camera 的 `startImageStream` 每帧把 YUV 经 platform channel 投递到 Dart + 触发原生推理，两者抢主线程 → WebView 周期性掉帧。
方案：平时不开帧流，用 `Timer.periodic(sampleInterval=300ms, ≈3.3fps)` 临时开流抓"一帧"后立即 `stopImageStream`，再处理这一帧。把主线程占用压成短促稀疏脉冲。注视/表情靠 JS 侧 spring 平滑补偿低采样率。
> ⚠️ **原生复刻的核心收益**：CameraX + 后台 executor 可全程后台线程推理（见 `native-prototype/`），**不再需要这个"按需采样"补丁**——可恢复成持续帧流 + KEEP_ONLY_LATEST 丢帧。

**`_processFrame(image)`（帧处理核心）**：
1. 算 `detectionRotationDegrees`（sensor±device 合成角）
2. 判断 `needCapture`（录入）/ `identityDue`（每 1200ms）/ `objectDue`（每 700ms）→ 决定是否需 upright 图
3. **关键顺序**：所有对原始 `image` 的读取必须在任何 `await` 前同步发起（相机缓冲在 await 后被回收）
4. 并行发起：`faceEngine.process`（人脸+姿态）、`handEngine.process`；同步抽 `convertPayload`
5. `Isolate.run` 做 YUV→RGBA 摆正（主线程最大开销之一）→ `upright` RGBA
6. `mlkitFaceEngine.processRgba`（多脸框）；`objectDue` 时后台 fire-and-forget 跑 YOLO
7. 构建多脸列表：ML Kit 框为主，MediaPipe 主脸按 **IoU** 嫁接到最匹配的框（带表情/网格/注视）；其余脸仅包围盒
8. 身份识别（节流）：逐脸 `cropNormalized`（padding 0.2）→ `faceRecognition.embed` → `identify` → `_assignSlots`（贪心最近邻，避免串脸）
9. `_matchSlotsToBoxes` 把 TTL（3s）内 slot 身份附着到各脸
10. `_markHeldObjects`：物体框与手框 IoU>0 或中心距 < `heldDistance=0.22` → 手持
11. 组装 `DetectionResult`，喂 `behaviorTracker` / `activityTracker`，记感知/行为/活动日志，`_maybeGazeTrigger()`，`notifyListeners()`

**身份槽（`_IdentitySlot`）**：多人脸场景按归一化中心点维持稳定身份，TTL 3s，匹配距离 0.15。识别帧用独占贪心匹配绑定，非识别帧按位置续身份。

**注视触发对话**（`_maybeGazeTrigger`）：gaze 落在正视圆内（中心 `(-0.27, 0.31)`，半径 0.12）+ 人脸居中（x 在 0.5±0.22）+ 持续 5s + 冷却 60s + 容错 1s → `voiceAssistant.triggerManually(source='gaze')`。

**DisplaySettings**（不持久化，每次启动从默认开始）：face/hand/identity/object/pose 开关、debugMode、各调试可视化开关、useFrontCamera（默认前置）、底座控制（默认关）、语音开关（voiceEnabled 默认关，但进 ready 时自动开）、wakeWordEnabled/ttsEnabled/streamingSttEnabled/gazeTriggerEnabled（默认全开）、wakeWord（默认"你好小白"）、personaLog 各开关（默认开）。

### 2.4 AppTuning（`lib/core/app_tuning.dart`，全部编译期 const）
集中所有阈值，原生复刻需逐项移植：
- **注视触发**：`gazeCenterX=-0.27`, `gazeCenterY=0.31`, `gazeTriggerRadius=0.12`, `gazeTriggerSeconds=5`, `gazeCooldownSeconds=60`, `gazeToleranceSeconds=1`, `faceCenterTolerance=0.22`
- **节流**：`identityInterval=1200ms`, `sampleInterval=300ms`, `objectInterval=700ms`
- **物体/感知**：`heldDistance=0.22`, `perceptionObjectConfidence=0.65`, `identityTtl=3s`, `slotMatchDistance=0.15`
- **相机/缓冲**：`readyBuffer=2s`, `perceptionMinInterval=2s`, `captureResolution=medium(≈480p)`

---

## 三、虚拟形象前端与 JS 桥接（**复刻必须逐字对齐**）

### 3.1 前端（`assets/html/`）
React + TypeScript + Vite，产物在 `assets/html/dist/`（`index.html` + `assets/index-*.js`）。
- **三种风格**（URL query）：`?style=ambient`（默认，暗背景+环境光晕+卡哇伊珍珠眼）、`?style=neon`（霓虹机器人脸）、留空（写实脸）
- FSM 状态机 10 态：`idle/gazing/listening/thinking/happy/confused/angry/sleepy/sleeping/waking`
- 内部含 `FaceController`（弹簧平滑、微动、眨眼、呼吸、saccade、振荡器叠加），原生只需调 3 个方法即可。

### 3.2 JS 桥接契约（`assets/html/src/face/runtime/controller.ts:206`）
前端把单例挂到全局：
```ts
(window as any).__face = faceController;
```
**原生（WebView）调用的 3 个核心方法**（其余可不接）：
| 方法 | 签名 | 说明 |
|---|---|---|
| `setState(state)` | `state: FaceState` | 切虚拟形象状态（idle/gazing/listening/thinking/happy/sleepy…），自动驱动眼形/嘴形/光晕/氛围预设 |
| `setGazeTarget(x, y)` | `x,y: number(-1..1)` | 瞳孔目标位置，绕过弹簧直接应用（零延迟跟随），正=右/下 |
| `setListeningLoudness(v)` | `v: number(0..1)` | 聆听/说话时嘴部张合幅度（由音量驱动） |

可选：`getState()`、`setExpression(name, intensity)`、`setAmbientExpression(id)`。

### 3.3 Flutter 侧推送逻辑（`camera_screen.dart` `_VirtualPetWebViewState._pushAll`）—— **原生复刻的行为基线**
- **JS 推送节流**：`_pushInterval=33ms`（~30fps），合并 `setState`+`setGazeTarget` 为一次 `runJavaScript`
- **语音优先级最高**：`voiceActive`（`isRunning && state.isActive`）时接管表情 + 嘴部
  - 状态：优先用后端回传的 `robotState`，否则 `voice.state.faceState`（idle→idle, waking/listening→listening, thinking→thinking, speaking→happy）
  - 嘴部：`f.setListeningLoudness(voice.level)`（listening 用麦音量，speaking 用 TTS 音量，统一走 `voice.level`）
- **语音刚结束过渡**：强制推 `f.setState('idle'); f.setListeningLoudness(0);` 避免表情卡在 listening
- **视觉态（无语音）**：
  - 注视：人脸中心归一化 → 9 宫格量化（`GazeZoneDetector`，带死区）→ 推格中心 `(-1/0/1)`；前置镜像 x 取反
  - 状态：**仅注意力态驱动**（drowsy→sleepy, focused→gazing, 其余→idle）；同一态至少 dwell 600ms 才切换；**不再用单帧情绪映射虚拟表情**（情绪仅用于人物日志）
- **双击进入聆听**：因 WebView（platform view）吞触摸事件，用一个 `HitTestBehavior.opaque` 的 `GestureDetector` 叠在 WebView 之上捕获双击 → 自动 start 语音助手 + `triggerManually(source='double_tap')`
- **聆听态跑马灯**：waking/listening 时全屏一圈旋转暖色 `SweepGradient` 描边 + 高斯模糊光晕，随 `voice.level` 呼吸（Siri 风格）

### 3.4 WebView 加载方式
- Flutter：`StaticServer`（本地 HTTP，loopback，把 `assets/html/dist/` 复制到 temp 后托管）→ `webview_flutter` 加载 `http://localhost:port/?style=ambient`
- **native-prototype 已验证更优方案**：`WebViewAssetLoader`（`https://appassets.androidplatform.net/`）直接映射 assets，**无需起 HTTP server**，原生复刻应采用此方案。

---

## 四、相机与视觉识别管线

### 4.1 相机（Flutter `camera` 插件）
- `ResolutionPreset.medium`（≈480p），`fps=5`（从源头限 5fps 采集），`ImageFormatGroup.yuv420`，`enableAudio=false`
- 默认前置（陪伴场景），可切后置；锁定横屏取流（`lockCaptureOrientation`），随物理旋转在两横屏间自适应，忽略竖屏
- `sensorOrientation` 取自 `CameraDescription.sensorOrientation`

### 4.2 旋转与像素转换（`camera_image_utils.dart`）
- `detectionRotationDegrees`：复用 `hand_detection` 的 `rotationForFrame`（iOS 横屏已预旋转→0；Android 用 sensor±device 合成角→0/90/180/270）
- `shouldFlipFrontCameraHorizontal`：Android=true（取流未镜像，需翻转贴合自拍）；iOS=false（已镜像）
- YUV→RGB：NV12（2 平面交错 UV）/ 3 平面 YUV420（Android 常见 I420/NV21）/ BGRA8888（iOS）逐像素转换 + `copyRotate`
- `cropNormalized`：按归一化矩形裁剪 + `paddingRatio=0.2` 外扩
- **原生复刻**：CameraX `ImageProxy.toBitmap()` 已自动按 `targetRotation` 摆正（见 prototype），YUV→Bitmap 由 CameraX 处理，**大幅简化**

### 4.3 人脸引擎（`face_engine.dart`）—— MediaPipe Face Landmarker
- 插件：`kwon_mediapipe_landmarker`（封装 MediaPipe tasks-vision）
- 配置：`numFaces=1`, `minDetectionConfidence=0.5`, `minTrackingConfidence=0.5`, `outputBlendshapes=true`；同时开 `pose=true`（`numPoses=1`），失败回退 face-only
- 模型文件：`face_landmarker.task`（assets）、`pose_landmarker_lite.task`（assets）
- 输出：478 点 landmarks（归一化 0..1）+ 52 blendshapes + `horizontalGazeDirection`/`verticalGazeDirection`（-1..1）
- 派生：`eyeBlink`（左右 eyeBlink 均值）、`mouthOpenness`（jawOpen）、boundingBox（landmarks 外接框）
- **iOS 撤销插件多余的 X 翻转**（`undoPluginMirrorX = Platform.isIOS`）；Android 不撤
- **原生复刻**：直接用 MediaPipe tasks-vision Java API `FaceLandmarker`（见 prototype `FaceAnalyzer.kt`），开启 `outputFaceBlendshapes` + `outputFacialTransformationMatrix`

### 4.4 表情分类（`expression_classifier.dart`）—— 规则式，**逐项移植**
基于 ARKit 52 blendshapes 规则式打分（非训练模型），`activationThreshold=0.28`：
- `happy` = smile*0.8 + cheekSquint*0.2
- `sad` = frown*0.6 + browInnerUp*0.4 - smile*0.3
- `surprised` = jawOpen*0.45 + browOuterUp*0.25 + browInnerUp*0.15 + eyeWide*0.15
- `angry` = browDown*0.6 + mouthPress*0.2 + eyeSquint*0.2 - jawOpen*0.2
- `disgusted` = noseSneer*0.6 + upperLipUp*0.4
- `fearful` = eyeWide*0.4 + browInnerUp*0.3 + mouthStretch*0.3
- 取最高分；< 0.28 判 neutral。各特征用左右均值（`avg`）。blendshape key 见 `FaceBlendshape.*`（mouthSmileLeft/Right, cheekSquintLeft/Right, …）

### 4.5 ML Kit 多脸（`mlkit_face_engine.dart`）
- `google_mlkit_face_detection`，`performanceMode=fast`, `minFaceSize=0.1`, `maxFaces=3`，关闭 landmarks/contours/classification/tracking
- 输入：摆正后 RGBA（`InputImage.fromBitmap`，rotation 0）
- 输出：归一化包围盒列表，按面积降序（主脸在前）
- **原生复刻**：ML Kit Android 原生 API（`FaceDetector` + `InputImage.fromBitmap`）

### 4.6 身份识别（`face_recognition_service.dart`）—— MobileFaceNet TFLite
- 模型：`assets/models/mobilefacenet.tflite`，输入 `1x112x112x3 float32`，归一化 `(px-127.5)/128`，输出 embedding（动态维度，通常 192），L2 归一化
- `threads=4` + XNNPACK（CPU）
- `identify`：与人物库所有 embedding 算**余弦相似度**（点积，因已 L2 归一化），`matchThreshold=0.62`，多人多样本取最高
- **原生复刻**：TFLite Android API（`Interpreter`），同样的预处理/归一化/余弦比对

### 4.7 手势引擎（`hand_engine.dart`）—— MediaPipe Hand
- 插件：`hand_detection`（离线 TFLite）
- 配置：`mode=boxesAndLandmarks`, `landmarkModel=full`, `detectorConf=0.6`, `maxDetections=2`, `minLandmarkScore=0.5`, `enableGestures=true`, `gestureMinConfidence=0.5`
- 输出：21 点 landmarks（归一化）+ handedness + gesture（7 类：thumbUp/victory/closedFist/openPalm/pointingUp/iLoveYou/thumbDown/unknown）
- `maxDim=480`（缩放）
- **原生复刻**：MediaPipe tasks-vision `HandLandmarker`（手势分类需另接，或用规则）

### 4.8 物体引擎（`object_engine.dart`）—— YOLO
- 插件：`ultralytics_yolo`（YOLO11/YOLO26，COCO 80 类）
- 模型：`assets/models/yolo26n_int8.tflite`（int8 量化，离线），`useGpu=false`，`task=detect`，`confidenceThreshold=0.35`, `iouThreshold=0.45`, `maxObjects=10`
- 输入：摆正图 JPEG（Isolate 编码，quality 92）
- 输出：归一化框 + className + confidence，跳过 person 类，COCO 英文→中文映射表（cup→杯子, bottle→瓶子, cell phone→手机, book→书…）
- **原生复刻**：TFLite `Interpreter` 跑 `yolo26n_int8.tflite`，需自行实现 YOLO26 解码 + NMS + 80 类标签（或找等价库）

### 4.9 行为/活动时序聚合（`lib/face/`）—— **算法逐项移植**（见另文详述）

#### 4.9.1 `GazeSmoother`（EMA）：`k=0.8`，`out = prev + k*(raw-prev)`
#### 4.9.2 `GazeZoneDetector`（3×3 九宫格 + 死区）：
- 输入 -1..1 → 转 0..1 → `floor(clamp*3).clamp(0,2)` 量化
- 死区 `_deadZoneRatio=0.05`（按单格宽 1/3 算，≈0.0167），相邻格边界 ±deadZone 内不跳格
- `currentZoneCenter` 返回当前格中心 -1..1

#### 4.9.3 `BehaviorStateTracker`（注意力 FSM，窗口 8s）
5 态：`absent/present/focused/distracted/drowsy`
- `absent`：近窗 1500ms 内有脸比例 < 0.3
- `drowsy`：mean blink > 0.55
- `focused`：`moveMetric=sqrt(cxStd²+cyStd²)/meanSize < 0.15` 且 `gazeStd=sqrt(gazeXStd²+gazeYStd²) < 0.12`
- `distracted`：`gazeStd > 0.12*1.8=0.216` 或 `zoneRate > 0.6/s`
- 滞回 dwell：focused=3s, distracted=2500ms, drowsy=2s, absent/present=1500ms
- 主导表情：窗口内加权投票（权重 `0.2+score`）

#### 4.9.4 `ActivityStateTracker`（日常活动 FSM，窗口 5s，优先级 strongest-wins）
8 活动：`none/drinking/lookingAtPhone/talking/yawning/handRaised/restingCheek/poorPosture`
- drinking：持杯具且靠近嘴（cupNearFaceDist 0.12）占比 ≥ 0.4
- lookingAtPhone：持手机且 gazeDown>0.2 占比 ≥ 0.5
- yawning：maxMouth≥0.6 且 wideRatio≥0.3 且 (flux<0.5 或 blinkRatio>0.3)
- talking：flux≥0.5 且 varMouth>0.005 且 speechRatio>0.35 且 maxMouth<0.65
- 物体标签中文：杯子/瓶子/酒杯=drinkware，手机=phone
- 姿态索引：nose=0, ear=7/8, shoulder=11/12, wrist=15/16, hip=23/24；手部 wrist=0, indexTip=8, middleTip=12
- dwell：drinking=1s, lookingAtPhone=1.5s, talking=2s, yawning=1.5s, handRaised=0.6s, restingCheek=2.5s, poorPosture=3s, none=1.5s

---

## 五、语音助手（`lib/services/voice/`）—— 详见另文，要点：

### 5.1 状态机（`voice_state.dart`）：`idle → waking → listening → thinking → speaking → idle`

### 5.2 音频（`audio_capture_service.dart`）
- `AudioRecord`：16kHz / mono / PCM16bit / AGC+AEC+NS
- 音量 RMS：`sqrt(Σx²/n)/6000` clamp 0..1，EMA `0.6*old+0.4*new`
- `captureUtterance`（batch VAD）：onset 0.15×3帧, sustain 0.08, silence 500ms, onset timeout 10s, max 12s

### 5.3 唤醒词（`wake_word_service.dart`）—— sherpa-onnx KWS
- 模型：`sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`（encoder/decoder/joiner int8 + tokens.txt）
- `numThreads=2`, `provider=cpu`, `modelingUnit=cjkchar`, **`modelType` 必须空**（写 'zipformer' 会崩）
- `keywordsThreshold=0.35`, `keywordsScore=1.0`
- 关键词→拼音（lpinyin，带声调）→拆声母韵母→`"n ǐ h ǎo x iǎo b ái @你好小白"`，作 `keywordsBuf`（字节数必须 = UTF-8 长度）
- 默认词"你好小白"，可运行时改；喂 Float32（÷32768）@16kHz

### 5.4 后端 Pophie（`pophie_client.dart`）—— 主对话路径
- 默认 `http://223.109.143.135:8000`（可配置）；`robotId=robot-<uuid>`（持久化），`sessionId`（服务端返回，复用）
- **流式 STT**：WebSocket `/api/stt/stream`，JSON 文本帧。`start{sample_rate:16000,language:'zh'}` → `chunk{data:base64(pcm16)}`；收 `meta/ready/partial/final/session_end/error`，`final.voice`→透传 TTS
- **批量对话**：`POST /api/chat`，body `{robot_id, session_id?, input:{text, audio?:{format:'wav',encoding:'base64',sample_rate:16000,data}, perception?, voice_id?, skip_tts:true}}`；音频是 16kHz mono WAV（44 字节头）base64。响应 `output.{text, facial_expression, robot_state, audio, voice}` + `stt.text` + `session_id`
- **流式对话**：`POST /api/chat/stream`，NDJSON：`{type:'speak',text}`（逐句）→ `{type:'done',response}`；支持 cancelToken（barge-in）
- **流式 TTS**：`POST /api/tts/stream`，NDJSON：`meta{format,sample_rate(22050)}` → `chunk{data:base64(pcm16)}` → `done{first_packet_ms}`；返回 PCM16 mono @22050
- **批量 TTS**：`POST /api/tts`（fallback），返回 `{audio:{data,format}}`
- **主动消息**：`GET /api/proactive_messages?robot_id&session_id&since_id&limit`，每 5s 轮询；trigger: welcome/reminder/living_loop
- **主人档案**：`PUT/GET/DELETE /api/robots/{robot_id}/owner`，上传 5 字段（nickname/robot_name/face_registered/gender?/birthday?，**人脸数据不上传**）
- 超时：connect 8s, receive 40s, tts 20s, chat/stream 60s, tts/stream 40s

### 5.5 流式 TTS 播放（`streaming_tts_player.dart`）
- `flutter_pcm_sound`（**不用** flutter_sound，会 SEGV）；`setup(sampleRate=22050, channel=1)`，feed 阈值 `sampleRate*0.1`（~100ms buffer）
- 首 chunk 即播放（首音延迟 ≈ LLM done + DashScope 首包 ~500ms）；喂 chunk 时同步写 `voice.level`（嘴部同步，同 RMS/6000 公式）
- 必须等 `remainingFrames==0` 才 release（硬件缓冲还持有数百 ms，提前 release 会截断）

### 5.6 Barge-in（`barge_in_detector.dart`）
- **默认半双工**（`bargeInEnabled=false`）：TTS 时 `audio.stop()`，物理上无回声路径
- 开启时：阈值 0.18，连续 3 帧，500ms cooldown；触发 → release player + cancelToken。重听上限 1 次，重听前 `waitForSilence`（1s 静默窗，阈值 0.04）

### 5.7 两种对话模式
- **流式（默认，`streamingSttEnabled=true`）**：WS STT + `/api/chat/stream` + `/api/tts/stream`，半双工每轮（`audio.stop()` during LLM+TTS）
- **批量（fallback）**：`captureUtterance` → WAV → `/api/chat` → `/api/tts/stream`
- 流式失败自动 fallback 批量；两个残留 final 过滤（无 partial 见过 / 8s 内重复文本）

### 5.8 主动消息轮询：`Timer.periodic(5s)`，idle 时立即播报欢迎语等

### 5.9 `chat_service.dart`（直连 DeepSeek OpenAI 兼容，`speech_service.dart` 是 stub）—— 不在语音主路径，参考用

---

## 六、底座控制（`lib/services/base/`）—— 经典蓝牙 SPP
- `flutter_bluetooth_serial`（本地 fork），SPP UUID `00001101-0000-1000-8000-00805F9B34FB`
- `BaseService`：`connect/disconnect/send/getBondedDevices`，JSON 指令收发，`onFrameReceived` 回调
- `BaseStatus`：dof/mode/yaw/pitch/roll；模式 manual/faceFollow/demo
- 默认关（`baseControlEnabled=false`），调试页按需连接
- 协议见 `docs/底座控制协议.md`
- **原生复刻**：Android `BluetoothSocket`（RFCOMM）+ `BaseProtocol` 切帧

---

## 七、人物日志（`lib/services/persona_logger.dart` + `persona_log_server.dart`）
- `PersonaLogger`：按天 JSON 文件持久化，记录类型 perception/state/activity/conversation
- `PersonaLogServer`：HTTP 服务（局域网可访问），注入 `frameProvider`（远程采样当前帧 POST /api/sample）+ `networkLogger`（/net 网络交互日志看板）
- 节流：感知变化触发 + 最小间隔 2s；行为/活动仅在状态转移那帧记
- 默认开（陪伴场景常需电脑端实时查看）

---

## 八、首次激活向导（`lib/ui/onboarding/`）
5 步 PageView（iOS 风格）：
0. 欢迎
1. 关于你：昵称（必填）/性别/生日
2. 机器人昵称（默认"狗蛋"）
3. 人脸录入：`captureFaceSample`（复用主相机 + 主脸 MobileFaceNet embedding），横屏左右布局 + 扫描环动画（`face_scan_ring.dart`）
4. 总结 → `completeOnboarding`：构造主脸 Person（relation=owner）+ 存头像 + 存 people.json + 本地档案立即生效进 ready + 后台 best-effort 同步 Pophie
- 人脸数据**绝不上传**（端侧比对），后端只收 faceRegistered 标志位

---

## 九、设置页（`lib/ui/settings/settings_screen.dart`，~1400 行）
分节：显示模式 / 身份识别（认识我 + 我的朋友 + 启用身份识别）/ 识别功能开关 / 调试可视化 / 语音助手（唤醒词编辑 + TTS 音色选择 + Pophie 配置）/ 人物日志（开关 + 查看）/ 对话日志 / 关于（版本）。
重置主人 = 重新进向导。`_PophieConfigSection` 可改服务地址/robotId/sessionId/音色。

---

## 十、数据模型（`lib/models/`）
- `DetectionResult{faces[], hands[], objects[], poses[], mirror}`，`faces[0]` 为主脸（含表情/网格/注视/eyeBlink/mouthOpenness）
- `FaceOverlay{landmarks[478], boundingBox, expression, identity?, gazeX, gazeY, eyeBlink, mouthOpenness}`
- `HandOverlay{landmarks[21], boundingBox, handedness?, gesture?, gestureConfidence}`
- `ObjectOverlay{boundingBox, label?, confidence, trackingId?, heldByHand}`，`center` getter
- `PoseOverlay{landmarks[33], visibilities[33], boundingBox}`
- `Expression` 7 态 + `ExpressionResult{expression, score, scores}`
- `Person{id, name, relation, embeddings[[double]], avatarPath?, createdAt}`，`IdentityMatch{person, similarity}`
- `OwnerProfile{nickname, robotName, gender?, birthday?, faceRegistered, personId?, syncedToServer, createdAt}`，`toPophieJson()` 5 字段
- `FamilyRelation` 12 类（owner/spouse/father/.../other）

---

## 十一、复刻优先级建议（基于 native-prototype 已验证 CameraX+MediaPipe 可行）

| 优先级 | 模块 | 原生方案 | 说明 |
|---|---|---|---|
| P0 | 横屏全屏常亮 + 相机后台线程 + WebView(AssetLoader) 注视 | CameraX + WebViewAssetLoader | prototype 已验证，恢复持续帧流(去掉按需采样) |
| P0 | MediaPipe 人脸(478点+blendshapes) → 表情分类 → JS setState/setGazeTarget | tasks-vision FaceLandmarker | JS 契约逐字对齐 |
| P0 | 首次激活向导 + OwnerProfile + 人物库(MobileFaceNet) | TFLite Interpreter | 端侧身份 |
| P1 | 语音助手(唤醒+流式STT+Pophie chat/tts stream) | sherpa-onnx + OkHttp WS + AudioTrack | 主交互路径 |
| P1 | 时序聚合(behavior/activity) → 注视触发对话 | 算法逐项移植 | 见 §4.9 |
| P2 | ML Kit 多脸 + 手势 + 物体(YOLO) + 蓝牙底座 + 人物日志服务 | 各原生 SDK | 可后续补 |

**核心优势**：原生方案可全程后台线程推理（CameraX executor + MediaPipe 同线程），主线程只负责 WebView 合成与 JS 注入，
彻底消除 Flutter 方案的"按需采样"补丁与主线程争抢，理论上注视跟随更流畅（prototype 已证实 Skipped frames 大幅减少）。
