# 虚拟宠物表情系统 · 外部调用指南

本目录是 IBot 桌面机器人的 **Web 端虚拟宠物表情渲染器**（React + TypeScript + Vite）。页面在 WebView 或浏览器中全屏运行，宿主应用通过 JavaScript 桥接驱动表情、注视与唤醒光效。

---

## 目录

1. [架构概览](#架构概览)
2. [构建与部署](#构建与部署)
3. [宿主集成方式](#宿主集成方式)
4. [全局 API：`window.__face`](#全局-apiwindow__face)
5. [状态机 `FaceState`](#状态机-facestate)
6. [双层表情系统](#双层表情系统)
7. [注视 `setGazeTarget`](#注视-setgazetarget)
8. [嘴部张合 `setListeningLoudness`](#嘴部张合-setlisteningloudness)
9. [唤醒光效 Wake Overlay](#唤醒光效-wake-overlay)
10. [表情叠加 `setExpression`](#表情叠加-setexpression)
11. [URL 查询参数](#url-查询参数)
12. [localStorage 配置项](#localstorage-配置项)
13. [调试面板](#调试面板)
14. [Flutter 集成参考](#flutter-集成参考)
15. [性能与调用频率建议](#性能与调用频率建议)
16. [类型定义索引](#类型定义索引)

---

## 架构概览

```
宿主（Flutter / 原生 WebView / 浏览器控制台）
        │
        │  runJavaScript / evaluateJavaScript
        ▼
window.__face  ← FaceController 单例（controller.ts）
        │
        ├─ FSM 状态机（idle / listening / thinking …）
        ├─ 底层 FaceParams（眨眼、呼吸、头部弹簧、嘴形曲线）
        ├─ 氛围表情预设 AmbientExpression（眼形/嘴形/光晕/道具）
        └─ 唤醒光效 WakeOverlay（底部光带 + 文字）
        │
        ▼
React 渲染器（AmbientFace / LineFace / KawaiiFace）
```

**关键约定：**

- 对外只暴露 **`window.__face`**，无 `postMessage`、无自定义 Bridge 协议。
- 页面加载完成后即可调用；建议先判断 `window.__face` 是否存在。
- 所有坐标、强度均为 **归一化数值**（见各 API 说明）。

---

## 构建与部署

### 开发预览

```bash
cd assets/html
npm install
npm run dev
```

默认地址：`http://localhost:5173`

常用预览地址：

| 地址 | 说明 |
|------|------|
| `http://localhost:5173/` | 主表情页面 |
| `http://localhost:5173/?debug=1` | 开启调试面板 |
| `http://localhost:5173/?preview=eye` | 动漫眼几何预览（开发用） |

### 生产构建（嵌入 Flutter）

```bash
cd assets/html
npm run build
```

产物输出到 `assets/html/dist/`，由 Flutter `StaticServer` 复制到临时目录并通过 `http://localhost:<port>/` 提供给 WebView。

**注意：** 修改前端代码后必须重新 `npm run build`，并确保 `pubspec.yaml` 已声明：

```yaml
flutter:
  assets:
    - assets/html/dist/
    - assets/html/dist/assets/
```

### 独立 WebView 测试（Flutter）

```bash
flutter run -t lib/webview_only.dart
```

---

## 宿主集成方式

### 1. WebView（推荐，IBot 默认方式）

以 Flutter `webview_flutter` 为例：

```dart
// 1. 开启 JavaScript
_controller = WebViewController()
  ..setJavaScriptMode(JavaScriptMode.unrestricted);

// 2. 页面 onPageFinished 后再推送指令
_controller.runJavaScript(
  "var f=window.__face;if(f){f.setState('idle');}",
);
```

### 2. 浏览器 / Electron

打开页面后，在开发者控制台直接调用：

```javascript
window.__face.setState('listening');
window.__face.setListeningLoudness(0.6);
```

### 3. 调用模板（合并多次操作为一次 JS）

WebView 中 `runJavaScript` 为异步且 JS 单线程串行，**强烈建议合并**：

```javascript
var f = window.__face;
if (f) {
  f.setState('listening');
  f.setGazeTarget(0.5, -0.3);
  f.setListeningLoudness(0.42);
}
```

Dart 侧拼接示例：

```dart
final js = "var f=window.__face;if(f){"
    "f.setState('listening');"
    "f.setGazeTarget(0.0,0.5);"
    "f.setListeningLoudness(0.350);"
    "}";
await controller.runJavaScript(js);
```

---

## 全局 API：`window.__face`

`window.__face` 是 `FaceController` 的实例，挂载于 `controller.ts` 末尾。以下为宿主常用方法。

### 状态与查询

| 方法 | 签名 | 说明 |
|------|------|------|
| `setState` | `(state: FaceState) => void` | 切换 FSM 状态，驱动底层动画与默认氛围表情 |
| `getState` | `() => FaceState` | 读取当前 FSM 状态 |
| `getOverlays` | `() => ExpressionOverlay[]` | 读取当前叠加表情列表 |

### 注视

| 方法 | 签名 | 说明 |
|------|------|------|
| `setGazeTarget` | `(x: number, y: number) => void` | 设置瞳孔/头部注视目标，范围约 **-1.15 ~ 1.15** |
| `setGazePointerEnabled` | `(enabled: boolean) => void` | 是否允许用户触摸/鼠标驱动注视 |
| `isGazeInputEnabled` | `() => boolean` | 当前是否接受指针注视 |
| `onGazeExternalChange` | `(cb: (active: boolean) => void) => () => void` | 宿主接管注视时回调；返回取消订阅函数 |

### 语音 / 嘴部

| 方法 | 签名 | 说明 |
|------|------|------|
| `setListeningLoudness` | `(v: number) => void` | 嘴部张合强度 **0~1**；同时叠加到唤醒光效强度 |

### 氛围表情（渲染层语义）

| 方法 | 签名 | 说明 |
|------|------|------|
| `getAmbientExpression` | `() => AmbientExpressionId` | 当前氛围预设 ID |
| `setAmbientExpression` | `(id: AmbientExpressionId) => void` | 手动覆盖氛围表情（见下文双层系统） |

### 表情叠加（底层参数层）

| 方法 | 签名 | 说明 |
|------|------|------|
| `setExpression` | `(name: ExpressionName, intensity: number) => void` | 叠加底层表情配方，强度 **0~100**；`intensity <= 0` 移除 |

### 注视平移配置

| 方法 | 签名 | 说明 |
|------|------|------|
| `getGazeMotionConfig` | `() => GazeMotionConfig` | 读取整脸平移强度 |
| `setGazeMotionConfig` | `(patch: Partial<GazeMotionConfig>) => void` | 局部更新并持久化到 localStorage |
| `resetGazeMotionConfig` | `() => void` | 恢复默认 |

`GazeMotionConfig` 字段：

```typescript
{
  panHorizontal: number; // 0~1，左右整体移动
  panVertical: number;   // 0~1，上下整体移动
}
```

### 唤醒光效

| 方法 | 签名 | 说明 |
|------|------|------|
| `getWakeOverlayConfig` | `() => WakeOverlayConfig` | 读取光效配置 |
| `setWakeOverlayConfig` | `(patch: Partial<WakeOverlayConfig>) => void` | 局部更新配置 |
| `resetWakeOverlayConfig` | `() => void` | 恢复默认配置 |
| `showWakeOverlay` | `(text?: string \| null, opts?: { autoHideMs?: number }) => void` | 显示底部光带；可选自动隐藏毫秒数 |
| `hideWakeOverlay` | `() => void` | 隐藏光效 |
| `setWakeOverlayText` | `(text: string) => void` | 光效已显示时更新文案（如流式 ASR） |

---

## 状态机 `FaceState`

定义见 `src/face/types.ts`，与 Dart 端 `lib/face/emotion_mapper.dart` 保持一致。

| 状态 | 字符串值 | 典型场景 | 自动氛围预设 |
|------|----------|----------|--------------|
| 待机 | `idle` | 无交互 | `idle` |
| 注视 | `gazing` | 检测到用户专注 | `idle` |
| 聆听 | `listening` | 语音采集 / ASR | `doubt`（问号） |
| 思考 | `thinking` | 等待 LLM | `calm` |
| 高兴 | `happy` | TTS 播报 | `calm` |
| 困惑 | `confused` | 惊讶/厌恶/恐惧等 | `doubt` |
| 愤怒 | `angry` | 生气 | `angry` |
| 困倦 | `sleepy` | 行为层困倦 | `squint` |
| 睡眠 | `sleeping` | 休眠 | `calm` |
| 苏醒 | `waking` | 唤醒词过渡 | `squint` + 可选唤醒光效 |

### 语音状态映射（IBot 后端）

`lib/services/voice/voice_state.dart` 中的映射关系：

| VoiceState | faceState |
|------------|-----------|
| `idle` | `idle` |
| `waking` | `waking` |
| `listening` | `listening` |
| `thinking` | `thinking` |
| `speaking` | `happy` |

示例：

```javascript
// 对话开始：聆听
window.__face.setState('listening');

// LLM 思考中
window.__face.setState('thinking');

// TTS 播报（高兴态 + 嘴部随音量）
window.__face.setState('happy');
window.__face.setListeningLoudness(0.8);

// 对话结束：务必切回 idle 并归零嘴部
window.__face.setState('idle');
window.__face.setListeningLoudness(0);
```

`setState('waking')` 时，若 `showOnWakeEnd` 为 true（默认），会自动显示唤醒光效；离开 `waking` 后按配置自动隐藏。

---

## 双层表情系统

虚拟宠物有 **两层** 相互独立的表情控制，宿主需理解其分工：

### 第一层：FSM + `setState`（底层 `FaceParams`）

- 控制眨眼频率、呼吸幅度、头部弹簧、基础嘴形曲线等 **连续动画参数**。
- `setState` 会同时根据 `STATE_TO_AMBIENT` 表切换默认氛围预设。

### 第二层：氛围表情 `setAmbientExpression`（渲染语义）

- 控制 **眼形样式**（星星眼、生气眼…）、**嘴形种类**、**光晕颜色**、**道具**（问号、流汗等）。
- 适用于 `ambient` / `line` / `kawaii` 三种渲染风格中的氛围层逻辑。

可用预设 ID（`src/face/render/ambientExpression.ts`）：

`idle` · `calm` · `surprised` · `doubt` · `angry` · `faint` · `shy` · `wink` · `nervous` · `dizzy` · `petrified` · `squint` · `drinking` · `eating` · `photo` · `glow` · `magic` · `observe`

```javascript
// 通常只需 setState，已自带合理预设
window.__face.setState('listening'); // 自动 → doubt

// 需要特殊展示时可手动覆盖
window.__face.setAmbientExpression('magic');
```

---

## 注视 `setGazeTarget`

### 坐标系

- `x`：水平，**右为正**，左为负；`0` 为正中。
- `y`：垂直，**下为正**，上为负；`0` 为正中。
- 有效范围：**-1.15 ~ 1.15**（超出会被钳制）。

### 行为说明

1. 宿主调用 `setGazeTarget(x, y)` 后，会 **锁定外部注视**，页面内触摸/鼠标不再改变瞳孔方向。
2. 传入 `(0, 0)` 会 **释放** 外部锁定，恢复指针注视（若 `setGazePointerEnabled(true)`）。
3. 眼球先于头部响应（spring 平滑），具体平移幅度受 `GazeMotionConfig` 影响。

### IBot 摄像头驱动方式

Flutter 将人脸中心量化到 **3×3 九宫格**，仅跨格时推送格心坐标 `(-1|0|1, -1|0|1)`，避免低帧率抖动。参考 `lib/ui/camera_screen.dart` 中 `_pushAll()`。

```javascript
// 人脸在画面右上方 → 瞳孔看向右上方
window.__face.setGazeTarget(1.0, -1.0);

// 人脸离开画面 → 可选择不推送，或推送 (0,0) 复位
window.__face.setGazeTarget(0, 0);
```

---

## 嘴部张合 `setListeningLoudness`

- 取值：**0.0 ~ 1.0**
- 在 `listening` / `happy` 等状态下，叠加正弦微颤驱动嘴部 `openness`。
- 同时写入唤醒光效的 `level` 强度（底部光带呼吸幅度）。
- 对话结束务必设为 `0`，避免嘴部/光效残留。

```javascript
// 模拟麦克风/RMS 音量
window.__face.setListeningLoudness(0.35);
```

---

## 唤醒光效 Wake Overlay

小爱电视风格的 **底部光带 + 上方文字**，颜色跟随当前氛围皮肤。

### 常用调用

```javascript
// 手动显示（带文案）
window.__face.showWakeOverlay('我在听…');

// 流式更新识别文字（光效需已 visible）
window.__face.setWakeOverlayText('今天天气怎么样');

// 3 秒后自动隐藏
window.__face.showWakeOverlay('你好', { autoHideMs: 3000 });

// 强制隐藏
window.__face.hideWakeOverlay();
```

### 配置字段 `WakeOverlayConfig`

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `enabled` | boolean | `true` | 总开关 |
| `defaultText` | string | `''` | 无显式文案时的默认文字 |
| `bottomBand` | number | `0.14` | 光带高度（占容器高比例 0~1） |
| `bottomOffset` | number | `0` | 光带距底边额外偏移 |
| `glowIntensity` | number | `0.82` | 光晕基础强度 0~1 |
| `breatheSpeed` | number | `1.25` | 呼吸动画速度 |
| `textSize` | number | `12` | 文字字号 px（8~24） |
| `letterSpacing` | number | `1` | 字间距 px |
| `showOnWaking` | boolean | `true` | `setState('waking')` 时自动显示 |
| `hideOnWakeEnd` | boolean | `true` | 离开 `waking` 后自动隐藏 |
| `autoHideMs` | number | `0` | 默认自动隐藏时长；0 表示不自动隐藏 |

```javascript
window.__face.setWakeOverlayConfig({
  defaultText: '小助手',
  glowIntensity: 0.9,
  textSize: 14,
});
```

---

## 表情叠加 `setExpression`

在 FSM 基础态之上叠加 **底层表情配方**（与 `ExpressionName` 对应），强度 0~100。

可用名称：`neutral` · `happy` · `confused` · `sleepy` · `sleeping` · `thinking` · `listening` · `gazing` · `waking`

```javascript
// 叠加 60% 强度的 happy
window.__face.setExpression('happy', 60);

// 移除叠加
window.__face.setExpression('happy', 0);
```

一般业务场景 **优先使用 `setState`**；`setExpression` 多用于调试或精细动画混合。

---

## URL 查询参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `debug` | `1` / `true` | 启用调试面板并写入 localStorage |
| `debug` | `0` / `false` | 关闭调试并清除标记 |
| `preview` | `eye` | 进入动漫眼几何预览页（非主流程） |

**说明：** Flutter 加载 URL 时常带 `?style=ambient`，但当前版本 **不会** 从 URL 读取 `style`；渲染风格由 localStorage 键 `face.style` 或调试面板切换（见下节）。若需指定风格，请在首次加载前写入 localStorage，或通过调试面板操作。

---

## localStorage 配置项

以下键在页面内持久化，宿主可通过注入 JS 在 `loadRequest` 之前设置（需注意 WebView 同源策略）：

| 键 | 值 | 说明 |
|----|-----|------|
| `face.style` | `ambient` \| `line` \| `kawaii` | 渲染风格；默认 `ambient` |
| `face.debug` | `1` \| `0` | 调试面板开关 |
| `face.gazeMotion` | JSON | `GazeMotionConfig` |
| `face.wakeOverlay` | JSON | `WakeOverlayConfig` |

切换 `face.style` 后需 **刷新页面** 才能生效（React 启动时读取）。

```javascript
localStorage.setItem('face.style', 'kawaii');
location.reload();
```

---

## 调试面板

### 开启方式

1. URL：`?debug=1`
2. Vite 开发模式（`npm run dev`）默认开启
3. 左上角 **连续点击 5 次**（2 秒窗口内）
4. 快捷键：`Ctrl/Cmd + Shift + D` 开关；`` ` `` 收起/展开面板；`Esc` 收起

### 面板能力

- 切换渲染风格（氛围 / 线条 / 卡哇伊）
- 切换氛围表情预设、皮肤
- 模拟聆听音量、唤醒光效
- 调整注视平移参数
- 表情编辑器（导出 `ExpressionRecipe` JSON）

生产环境默认 **关闭**；嵌入设备上不建议对用户暴露。

---

## Flutter 集成参考

IBot 完整实现见：

- `lib/ui/camera_screen.dart` — WebView 加载、状态/注视/音量推送
- `lib/services/static_server.dart` — 本地静态资源服务
- `lib/core/app_controller.dart` — `startVirtualPetServer()`
- `lib/services/voice/voice_state.dart` — 语音态 → `FaceState` 映射

### 推送优先级（与 IBot 一致）

1. **语音活跃**（`listening` / `thinking` / `speaking`）：语音态 + `setListeningLoudness` 接管表情，忽略摄像头情绪。
2. **语音刚结束**：强制 `setState('idle')` + `setListeningLoudness(0)`，防止卡在 `listening`。
3. **平时**：摄像头行为层（困倦 → `sleepy`，专注 → `gazing`）+ 九宫格注视；其余为 `idle`。

### 节流建议

IBot 使用约 **50ms（~20fps）** 合并窗口，并将 `setState` + `setGazeTarget` 合成 **单次** `runJavaScript` 调用。

---

## 性能与调用频率建议

| 建议 | 原因 |
|------|------|
| 合并多次 API 为一条 JS | 减少 WebView 队列堆积 |
| 限制推送频率 ≤ 20~30 fps | `runJavaScript` 异步串行，过高会卡顿 |
| 状态变化加去抖/最小停留时间 | 避免 FSM 频繁切换导致表情跳变 |
| 对话结束务必 `idle` + `loudness 0` | 防止嘴型/光效残留 |
| 页面 `onPageFinished` 后再调用 | 确保 `window.__face` 已挂载 |

---

## 类型定义索引

| 类型 | 文件 |
|------|------|
| `FaceState`, `ExpressionName`, `FaceParams` | `src/face/types.ts` |
| `AmbientExpressionId` | `src/face/render/ambientExpression.ts` |
| `FaceStyleId` | `src/face/render/faceStyle.ts` |
| `WakeOverlayConfig` | `src/face/render/wakeOverlayConfig.ts` |
| `GazeMotionConfig` | `src/face/runtime/gazeMotionConfig.ts` |
| `FaceController` 实现 | `src/face/runtime/controller.ts` |

### 内部事件总线（未暴露给宿主）

`eventBus`（`src/face/runtime/eventBus.ts`）在页面内部使用，**未挂载到 `window`**。若宿主需要订阅帧数据，可自行扩展 `controller.ts` 或在宿主侧轮询 `getState()`。

事件类型：

- `state:change` — `{ state: FaceState }`
- `expression:change` — `{ expression, intensity }`
- `frame` — `{ headTilt, headBobY, mouthOpenness }`（约 30fps）

---

## 快速上手清单

```javascript
// 1. 确认 API 可用
if (!window.__face) throw new Error('Face API not ready');

// 2. 待机
window.__face.setState('idle');

// 3. 唤醒 → 聆听
window.__face.setState('waking');
setTimeout(() => window.__face.setState('listening'), 300);

// 4. 驱动嘴部（用户说话）
window.__face.setListeningLoudness(0.5);

// 5. 思考
window.__face.setState('thinking');
window.__face.setListeningLoudness(0);

// 6. 播报
window.__face.setState('happy');
window.__face.setListeningLoudness(0.7);

// 7. 结束
window.__face.setState('idle');
window.__face.setListeningLoudness(0);
window.__face.hideWakeOverlay();
```

---

## 相关文档

- 项目功能说明：`docs/功能说明书.md`（虚拟形象数据流章节）
- Dart 端 `FaceState`：`lib/face/emotion_mapper.dart`
- 语音映射：`lib/services/voice/voice_state.dart`
