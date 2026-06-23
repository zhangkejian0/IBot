# Pophie API 对接文档

本文档描述 Pophie 陪伴机器人后端的 HTTP JSON API，供 **Web 前端**、**Android 客户端** 及第三方集成使用。

---

## 1. 基本信息

| 项目 | 说明 |
|------|------|
| 协议 | HTTP/1.1（TTS 流式为 `application/x-ndjson` 行流） |
| 数据格式 | `application/json`；音频在 JSON 内为 Base64，流式 TTS 按行推送 |
| 默认地址 | `http://<host>:8000`（`config.yaml` 中 `server.host` / `server.port` 可配置） |
| 跨域 | 已开启 CORS，`allow_origins: *` |
| 默认机器人 | `default`（`robot_id` 省略时使用） |

### 1.1 推荐对接流程

```
1. GET  /api/health          → 确认服务与语音能力（tts_engine=dashscope-realtime）
2. GET  /api/schema          → 拉取表情枚举、TTS 音色列表
3. POST /api/session/new     → 获取 session_id（也可自行生成）
4. PUT  /api/robots/{robot_id}/owner → 首次激活时注册主人档案（见 §3.16）
5. POST /api/chat            → 主对话（建议 input.skip_tts=true，先拿文字）
6. POST /api/tts/stream      → 流式合成并边收边播（推荐，见 §3.6.1）
7. GET  /api/proactive_messages → 轮询主动消息（可选；播放同样走 §3.6.1）
```

**语音对话时序（推荐）：**

```
客户端                    服务端
  |-- POST /api/chat (skip_tts=true) -->|
  |<-- 200 output.text, audio=null -----|  （仅 LLM，较快返回）
  |-- POST /api/tts/stream ------------>|
  |<-- NDJSON: meta/chunk*/done --------|  （首包 ~500ms 可开口）
```

### 1.2 身份与会话

- **robot_id**：机器人唯一标识。每台物理设备（如 Android 手机 / XBot 端侧）应在首次启动时生成 UUID（推荐 `robot-<uuid>`）并持久化，后续所有请求携带同一 `robot_id`。不同 `robot_id` 的记忆、对话上下文完全隔离，可并发对话。
- **user_id**（可选）：端侧身份识别（「认识我」）到的当前用户标识/人名。**仅作溯源写入与响应回显**，记忆与召回**仍按 `robot_id` 隔离，不按人分库**。省略时服务端按 `default` 处理。若同时也作为感知上下文喂给大模型，请放入 `input.perception.identity`（见 §2.4）。
- **session_id**：会话标识，同一机器人内保留 L2 对话上下文；不传时服务端自动生成 `sess-<8位hex>`。
- 客户端应在首次对话后**保存响应中的 `session_id`**，后续请求原样带回；App 重启后复用同一 `session_id` 可延续 L2 会话记忆。

多台机器人连接同一后端时，各自使用不同 `robot_id` 即可并发聊天，服务端无共享状态（SQLite WAL 模式支持并发读写）。

### 1.3 服务端配置（`config.yaml`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `chat.defer_side_tasks` | `true` | 记忆写入与提醒提取放后台；为 `true` 时 `/api/chat` 响应中 `memory_flow`、`l1_frames` 恒为 `[]` |
| `chat.inline_tts` | `true` | 为 `true` 且未设 `skip_tts` 时 `/api/chat` 内嵌完整 TTS（`output.audio` 为 MP3）；**推荐客户端设 `skip_tts: true` 并走 `/api/tts/stream`** |
| `speech.enabled` | `true` | 关闭后无 STT/TTS，`/api/stt`、`/api/tts`、`/api/tts/stream` 返回 503 |
| `speech.tts.base_url` | `wss://dashscope.aliyuncs.com/api-ws/v1/inference` | CosyVoice **实时 WebSocket** 端点（北京地域） |
| `speech.tts.model` | `cosyvoice-v3-flash` | TTS 模型 |
| `speech.tts.format` | `mp3` | **`POST /api/tts`** 与 **`inline_tts` 内嵌音频** 的输出格式 |
| `speech.tts.stream_format` | `pcm` | **`POST /api/tts/stream`** 分片格式（推荐 `pcm`，便于边收边播） |
| `speech.tts.sample_rate` | `22050` | TTS 输出采样率 |
| `server.default_robot` | `default` | `robot_id` 省略时的默认机器人 |
| `server.admin_token` | `""` | 非空时 Admin API 要求 `X-Admin-Token` 请求头 |

---

## 2. 公共数据类型

### 2.1 面部表情 `FacialExpression`

机器人与用户侧均使用 **7 类** 标准表情，key 与后端 `FacialExpression` 枚举一致：

| key | 中文 label |
|-----|------------|
| `angry` | 恼怒 |
| `disgust` | 厌恶 |
| `fear` | 恐惧 |
| `happy` | 开心 |
| `neutral` | 中性 |
| `sad` | 悲伤 |
| `surprise` | 惊讶 |

请求与响应中的 `facial_expression` **均须使用上表 key**。中文 label 仅用于 UI 展示；服务端在响应中通过 `facial_expression_label` 字段回传。

完整枚举列表可通过 `GET /api/schema` 的 `facial_expressions` 动态获取。

`gesture`（手势）现已接入大模型（见 §2.4）；`posture`（体姿态）字段仍为预留，可传 `null` 或省略。

### 2.2 语音情感 `VoiceProsody`

描述 TTS 的语气、语调、语速，用于机器人回复的合成风格。

| 字段 | 说明 | 可选值 |
|------|------|--------|
| `tone` | 语气 | 温柔、平静、急躁、兴奋、低落、撒娇、疑问、冷淡 |
| `intonation` | 语调 | 平稳、上扬、下沉、起伏大 |
| `speed` | 语速 | 慢、正常、快、极快 |

LLM 会根据 `facial_expression` 自动补全默认 `voice`；客户端也可在 TTS 请求中显式指定。

### 2.3 音频载荷 `AudioPayload`

```json
{
  "format": "wav",
  "encoding": "base64",
  "sample_rate": 16000,
  "data": "<Base64 编码的音频字节>"
}
```

| 字段 | 说明 |
|------|------|
| `format` | `wav`、`mp3`（批量/内嵌 TTS）或 `pcm`（**流式 TTS 默认**） |
| `encoding` | 固定 `base64`（`AudioPayload` 场景）；流式 `chunk.data` 同为 Base64 |
| `sample_rate` | STT 输入建议 **16000**；TTS 输出通常为 **22050** |
| `data` | Base64 字符串，**不含** `data:audio/...` 前缀 |

**STT 输入要求**：16kHz、单声道、PCM WAV（客户端录音后编码即可）。

> **TTS 播放**：官方 Web / Android 客户端默认不在 `/api/chat` 等待内嵌音频，而是 `skip_tts: true` 后调 **`POST /api/tts/stream`**。`AudioPayload` 仍用于 STT 上传、以及可选的批量 `/api/tts`、旧版 `inline_tts` 内嵌播放。

**批量播放示例（JavaScript，`/api/tts` 或内嵌 `output.audio`）**：

```javascript
const bytes = Uint8Array.from(atob(audio.data), c => c.charCodeAt(0));
const blob = new Blob([bytes], {
  type: audio.format === "mp3" ? "audio/mpeg" : "audio/wav"
});
new Audio(URL.createObjectURL(blob)).play();
```

**流式播放（JavaScript，`/api/tts/stream`，PCM）**：

```javascript
const resp = await fetch("/api/tts/stream", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ text, voice_id: "gentle_female", voice: outputVoice }),
});
const reader = resp.body.getReader();
const dec = new TextDecoder();
let buf = "", sampleRate = 22050;
const ctx = new AudioContext();
let t = ctx.currentTime;
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buf += dec.decode(value, { stream: true });
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const msg = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1);
    if (msg.type === "meta") sampleRate = msg.sample_rate;
    if (msg.type === "chunk") {
      const b = atob(msg.data);
      const pcm = new Int16Array(new Uint8Array([...b].map(c => c.charCodeAt(0))).buffer);
      const f32 = Float32Array.from(pcm, s => s / 32768);
      const ab = ctx.createBuffer(1, f32.length, sampleRate);
      ab.getChannelData(0).set(f32);
      const src = ctx.createBufferSource();
      src.buffer = ab; src.connect(ctx.destination); src.start(t);
      t += ab.duration;
    }
  }
}
```

### 2.4 感知输入 `PerceptionInput`

用户侧多模态上下文，可单独发送或与文字/语音组合。

```json
{
  "facial_expression": "sad",
  "voice": { "tone": "低落", "intonation": "下沉", "speed": "慢" },
  "touch": "摸头",
  "identity": "小明",
  "gesture": { "type": "wave" },
  "posture": null
}
```

| 字段 | 说明 |
|------|------|
| `facial_expression` | 用户面部表情（7 类 key，见 §2.1） |
| `voice` | 用户语音侧道（语气/语调/语速）；**仅在与文字或 STT 结果同时存在时有效** |
| `touch` | 抚摸类物理交互，如 `摸头`、`拥抱` |
| `identity` | 端侧身份识别到的人名，如 `小明`。**仅作为感知上下文喂给大模型**（可个性化称呼），记忆仍按 `robot_id` 隔离，不分人。也可传 `{ "name": "小明", "confidence": 0.9 }`，服务端取 `name` |
| `gesture` | 手势对象，**现已进入大模型**，见下方 |
| `posture` | 体姿态对象（预留，暂不进入 LLM），见下方 |

**`gesture` / `posture` 对象结构：**

```json
{
  "gesture": { "type": "wave", "params": { "target": "robot" } },
  "posture": { "type": "slouched", "params": null }
}
```

| 字段 | 说明 |
|------|------|
| `type` | 动作类型 key |
| `params` | 可选扩展参数（自由 JSON） |

**手势 `type` 取值**（中文 label 见 `GET /api/schema` 的 `gestures`）：`wave`(挥手)、`nod`(点头)、`shake_head`(摇头)、`thumbs_up`(点赞)、`heart`(比心)、`raise_hand`(举手)、`ok`、`victory`、`fist`、`point`、`open_palm`。未知 `type` 会原样作为标签传入。

> 服务端会把 `identity`、`gesture` 拼进 `[感知 ...]` 上下文喂给 LLM；`posture` 仍不送入 LLM，客户端可提前按此结构发送。

### 2.5 聊天输入 `ChatInput`

```json
{
  "text": "今天好累",
  "audio": null,
  "perception": { "facial_expression": "sad", "touch": "摸头" },
  "voice_id": "gentle_female",
  "skip_tts": true
}
```

| 字段 | 说明 |
|------|------|
| `text` | 用户文字；可与 `audio`、`perception` 组合 |
| `audio` | 语音输入；无 `text` 时服务端自动 STT |
| `perception` | 感知上下文 |
| `voice_id` | TTS 音色 ID（见 `/api/schema`）；省略则用服务端默认音色 |
| `skip_tts` | 可选。**推荐 `true`**：`/api/chat` 不等待 TTS，`output.audio` 为 `null`，客户端立即调 **`POST /api/tts/stream`**；`false` 强制内嵌 TTS；省略则遵循 `chat.inline_tts`（默认 `true`，首声较慢） |

**输入合法性**（至少满足其一）：

1. 非空 `text`
2. 带 `data` 的 `audio`（且 `speech.enabled=true`）
3. `perception` 中至少一项非声音感知（表情、抚摸等）

> 纯表情/抚摸、无文字时：不要传 `voice` 侧道字段，服务端会忽略。

### 2.6 机器人输出 `RobotOutput`

```json
{
  "text": "听起来你今天真的很累…",
  "facial_expression": "neutral",
  "facial_expression_label": "中性",
  "robot_state": "idle",
  "robot_state_label": "待机",
  "voice": { "tone": "温柔", "intonation": "平稳", "speed": "慢" },
  "audio": null,
  "gesture": null,
  "posture": null
}
```

| 字段 | 说明 |
|------|------|
| `text` | 机器人回复正文（必填） |
| `facial_expression` | 机器人表情（7 类 key） |
| `facial_expression_label` | 中文标签（服务端自动填充） |
| `robot_state` | 机器人此刻的**互动动作状态**，**取值限定为虚拟宠物 FSM 9 态之一**：`idle`/`gazing`/`listening`/`thinking`/`happy`/`confused`/`sleepy`/`sleeping`/`waking`。由大模型给出；缺省或非法时服务端按 `facial_expression` 自动推导（映射见 §4.4）。端侧可直接用它驱动虚拟形象 |
| `robot_state_label` | FSM 状态中文标签（服务端自动填充） |
| `voice` | 合成用语音情感参数；**流式 TTS 时原样传入 `POST /api/tts/stream` 的 `voice` 字段** |
| `audio` | 内嵌 TTS 音频（MP3/WAV）；`skip_tts=true`、`chat.inline_tts=false`、`speech.enabled=false` 或合成失败时为 **`null`**（此时应走 `/api/tts/stream`） |

> **FSM 状态保证：** `robot_state` 必定是上述 9 个值之一（即使大模型漏填或乱填，服务端也会按表情兜底为合法值），端侧无需再做校验即可直接切换虚拟形象状态机。可用值列表见 `GET /api/schema` 的 `robot_states`。

> **共情对齐（服务端自动）：** 当用户表情为 `sad` / `fear` / `angry`，而 LLM 返回 `neutral` 时，服务端会将机器人表情纠正为 `sad`，并将 `voice` 设为温柔 + 下沉 + 慢，避免冷漠式安抚。

### 2.7 TTS 音色 `voice_id`

CosyVoice v3 音色（通过 `GET /api/schema` 的 `tts_voices` 动态获取；`default: true` 标记当前默认）：

| voice_id | 说明 |
|----------|------|
| `gentle_female` | 知性女声 |
| `child_girl` | 童声女童 |
| `sunny_boy` | 阳光大男孩 |
| `warm_male` | 温暖元气男 |

在 `ChatInput.voice_id` 或 `TtsRequest.voice_id` 中传入。无效 ID 将回退为 `config.yaml` 中 `speech.tts.default_voice`（未配置时回退 `gentle_female`）。

> 部分音色支持 CosyVoice **Instruct** 情感控制（见 `tts_voices[].instruct: true`），可配合 `voice` 字段中的 `tone` 等参数。

### 2.8 主人档案 `OwnerProfile`

桌面陪伴机器人「首次激活向导」采集的**主人**信息。该档案绑定到 `robot_id`（一台物理机器人对应一个主人），用于机器人对主人的个性化称呼、自我介绍与长期记忆偏好。

> **人脸数据不上传**：端侧通过 MobileFaceNet 完成人脸特征提取与比对，后端永远不接收人脸图像/特征向量。本类型只携带 `face_registered` 标志位。

```json
{
  "nickname": "小明",
  "robot_name": "狗蛋",
  "gender": "male",
  "birthday": "1990-01-01",
  "face_registered": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `nickname` | string | 是 | 主人昵称/称呼，机器人对主人的个性化称呼（同时作为 `/api/chat` 的 `user_id` 与 `perception.identity`） |
| `robot_name` | string | 是 | 主人给机器人起的名字，替换服务端默认名 |
| `gender` | string | 否 | `male` / `female` / `other`；不填写时省略或为 `null` |
| `birthday` | string | 否 | ISO 日期 `YYYY-MM-DD`；用户选择不填写时省略或为 `null` |
| `face_registered` | bool | 否 | 端侧是否已录入主人人脸，默认 `false`。**仅标志位，不传人脸向量** |

---

## 3. 接口详情

### 3.1 健康检查

**`GET /api/health`**

**响应 200：**

```json
{
  "ok": true,
  "speech_enabled": true,
  "stt_engine": "dashscope",
  "tts_engine": "dashscope-realtime"
}
```

| 字段 | 说明 |
|------|------|
| `speech_enabled` | 语音模块是否可用 |
| `stt_engine` | 当前 STT 引擎：`dashscope`（`qwen3-asr-flash-realtime`）；`speech.enabled=false` 时为 `null` |
| `tts_engine` | 当前 TTS 引擎：`dashscope-realtime`（CosyVoice WebSocket）；`speech.enabled=false` 时为 `null` |

---

### 3.2  Schema 元数据

**`GET /api/schema`**

返回表情、语音情感枚举及 TTS 音色列表，客户端可用于构建 UI。

**响应 200：**

```json
{
  "facial_expressions": [
    { "key": "happy", "label": "开心" },
    { "key": "neutral", "label": "中性" }
  ],
  "voice_prosody": {
    "tone": ["温柔", "平静", "急躁", "兴奋", "低落", "撒娇", "疑问", "冷淡"],
    "intonation": ["平稳", "上扬", "下沉", "起伏大"],
    "speed": ["慢", "正常", "快", "极快"]
  },
  "gestures": [
    { "key": "wave", "label": "挥手" },
    { "key": "thumbs_up", "label": "点赞" },
    { "key": "heart", "label": "比心" }
  ],
  "robot_states": [
    { "key": "idle", "label": "待机" },
    { "key": "happy", "label": "高兴" },
    { "key": "confused", "label": "困惑" }
  ],
  "perception_fields": {
    "facial_expression": "用户面部表情（7 类 key）",
    "voice": "语音侧道（语气/语调/语速），仅与文字/STT 同时有效",
    "touch": "抚摸类物理交互，如 摸头/拥抱",
    "identity": "端侧身份识别到的人名（仅作感知上下文，记忆按 robot_id 隔离）",
    "gesture": "端侧手势识别结果（type 取 gestures 列表 key）",
    "posture": "体姿态（预留，暂不进入 LLM）"
  },
  "fsm_state_mapping": {
    "happy": "happy",
    "neutral": "idle",
    "sad": "sleepy",
    "angry": "confused",
    "disgust": "confused",
    "fear": "confused",
    "surprise": "confused"
  },
  "tts_voices": [
    { "id": "gentle_female", "label": "知性女声", "default": true, "instruct": false }
  ],
  "reserved_fields": ["posture"]
}
```

| 新增字段 | 说明 |
|----------|------|
| `gestures` | 端侧手势 key → 中文 label（手势现已进入大模型） |
| `robot_states` | 机器人回复 `output.robot_state` 的合法取值（FSM 9 态）→ 中文 label |
| `perception_fields` | 感知通道字段说明（含 `identity`、`gesture`） |
| `fsm_state_mapping` | 后端输出 `facial_expression` → 端侧虚拟宠物 FSM 状态的兜底映射（`robot_state` 缺省时使用，见 §4.4） |

---

### 3.3 新建会话

**`POST /api/session/new`**

**Query（可选）：**

| 参数 | 说明 |
|------|------|
| `robot_id` | 机器人 ID；默认 `default` |
| `user_id` | 用户/身份标识；默认 `default` |

**响应 200：**

```json
{
  "robot_id": "robot-a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "user_id": "default",
  "session_id": "sess-a1b2c3d4"
}
```

---

### 3.4 聊天（核心）

**`POST /api/chat`**

主对话接口：支持文字、语音、表情/抚摸感知；自动记忆写入、召回、提醒提取。

**TTS 说明：** 服务端 TTS 已改为 CosyVoice **WebSocket 流式合成**。推荐客户端设 `input.skip_tts: true`，在收到 `output.text` 后立即请求 **`POST /api/tts/stream`**，以降低首声延迟。若未设 `skip_tts` 且 `chat.inline_tts=true`，响应内仍含完整 `output.audio`（MP3，需等合成结束）。

#### 请求体

```json
{
  "robot_id": "robot-xxx",
  "user_id": "小明",
  "session_id": "sess-a1b2c3d4",
  "input": {
    "text": "今天好累",
    "perception": {
      "facial_expression": "sad",
      "touch": "摸头",
      "identity": "小明",
      "gesture": { "type": "wave" }
    },
    "voice_id": "gentle_female",
    "skip_tts": true
  }
}
```

> `user_id` 与 `input.perception.identity` 可同时存在：前者用于响应回显/溯源，后者会进入大模型上下文。两者均可省略。

#### 典型场景

**① 纯文字（推荐流式 TTS）**

```json
{
  "session_id": "sess-xxx",
  "input": { "text": "你好", "skip_tts": true }
}
```

收到响应后：

```http
POST /api/tts/stream
Content-Type: application/json

{"text":"<output.text>","voice_id":"gentle_female","voice":<output.voice>}
```

**② 语音（按住说话）**

```json
{
  "session_id": "sess-xxx",
  "input": {
    "text": "",
    "audio": {
      "format": "wav",
      "encoding": "base64",
      "sample_rate": 16000,
      "data": "UklGRi..."
    },
    "perception": { "facial_expression": "neutral" }
  }
}
```

**③ 仅发送表情（非语言信号）**

```json
{
  "session_id": "sess-xxx",
  "input": {
    "text": "",
    "perception": { "facial_expression": "happy" }
  }
}
```

**④ 表情 + 文字 + 语音情感侧道**

```json
{
  "input": {
    "text": "我还好",
    "perception": {
      "facial_expression": "sad",
      "voice": { "tone": "低落", "intonation": "下沉", "speed": "慢" }
    }
  }
}
```

#### 响应 200

```json
{
  "robot_id": "robot-xxx",
  "user_id": "小明",
  "session_id": "sess-a1b2c3d4",
  "output": {
    "text": "听起来你今天真的很累…",
    "facial_expression": "neutral",
    "facial_expression_label": "中性",
    "robot_state": "idle",
    "robot_state_label": "待机",
    "voice": { "tone": "温柔", "intonation": "平稳", "speed": "慢" },
    "audio": null,
    "gesture": null,
    "posture": null
  },
  "stt": {
    "text": "今天好累",
    "voice": null
  },
  "memory_flow": [],
  "l1_frames": [],
  "recalled": [
    {
      "id": 12,
      "layer": "L3",
      "summary": "用户近期工作压力大",
      "importance": 0.72
    }
  ],
  "scheduled_reminders": []
}
```

| 字段 | 说明 |
|------|------|
| `user_id` | 回显本次请求的用户/身份标识（省略时为 `default`） |
| `output` | 结构化机器人回复 |
| `stt` | 若请求含语音，返回识别结果；纯文字时为 `null` |
| `memory_flow` | 本轮新产生的记忆条目（完整 memory 对象）；`chat.defer_side_tasks=true` 时恒为 `[]` |
| `l1_frames` | L1 瞬时感知帧；`chat.defer_side_tasks=true` 时恒为 `[]` |
| `recalled` | 本轮召回的长期记忆 |
| `scheduled_reminders` | 从用户文字中解析出的提醒（`defer_side_tasks=true` 时可能为空，提醒在后台调度） |

#### 语音无法识别（STT 空结果或失败）

仍返回 **200**，机器人**保持静默**（不调用 LLM、不合成 TTS）：

```json
{
  "robot_id": "default",
  "user_id": "default",
  "session_id": "sess-a1b2c3d4",
  "output": {
    "text": "",
    "facial_expression": "neutral",
    "facial_expression_label": "中性",
    "voice": null,
    "audio": null,
    "gesture": null,
    "posture": null
  },
  "stt": { "text": "", "voice": null },
  "memory_flow": [],
  "l1_frames": [],
  "recalled": [],
  "scheduled_reminders": []
}
```

客户端应提示用户重新说话，勿将空 `output.text` 当作有效回复。

#### LLM 失败

同样返回 **200** 且 `output.text` 为空（静默），**不会**返回兜底文案。

#### 错误

| HTTP | 场景 |
|------|------|
| 400 | 无有效输入（无文字、无音频、无非声音感知）；语音未启用却传了 `audio` |

---

### 3.5 语音识别

**`POST /api/stt`**

仅做 STT，不触发 LLM 对话。

**请求：**

```json
{
  "audio": {
    "format": "wav",
    "encoding": "base64",
    "sample_rate": 16000,
    "data": "..."
  }
}
```

**响应 200：**

```json
{
  "text": "今天天气不错",
  "voice": { "tone": "兴奋", "intonation": "上扬", "speed": "正常" }
}
```

> `voice` 字段：`qwen3-asr-flash-realtime` 可从语音推断情感；识别失败时为 `null`。

**错误：** `503` 语音未启用；`400` 识别失败。

---

### 3.6 语音合成

TTS 基于阿里云百炼 **CosyVoice 实时 WebSocket**（`cosyvoice-v3-flash`），服务端通过 DashScope SDK 流式合成。

**推荐对接方式（低首声延迟）：**

1. `POST /api/chat` 请求中设 `"skip_tts": true`，先拿到文字与表情；
2. 立即调用 `POST /api/tts/stream`，按 NDJSON 行流边收边播。

#### 3.6.1 流式合成（推荐）

**`POST /api/tts/stream`**

与 `/api/tts` 请求体相同，响应为 **`application/x-ndjson`**（每行一个 JSON 对象）：

**请求：**

```json
{
  "text": "你好，我是 Pophie",
  "voice": { "tone": "温柔", "intonation": "平稳", "speed": "正常" },
  "voice_id": "gentle_female"
}
```

**响应流（按行顺序）：**

```json
{"type":"meta","format":"pcm","sample_rate":22050,"encoding":"base64"}
{"type":"chunk","data":"<Base64 PCM 分片>"}
{"type":"chunk","data":"..."}
{"type":"done","first_packet_ms":512}
```

| 行类型 | 说明 |
|--------|------|
| `meta` | 首行；`format` 为 `pcm` 或 `mp3`，`sample_rate` 为采样率 |
| `chunk` | 音频二进制分片的 Base64；客户端收到首包即可开始播放（PCM 用 AudioTrack / Web Audio） |
| `done` | 合成结束；`first_packet_ms` 为 DashScope 首包延迟（毫秒，供监控） |
| `error` | 合成失败；含 `message` 字段 |

**PCM 分片格式：** 16-bit 小端、单声道，采样率见 `meta.sample_rate`（默认 22050）。Android 使用 `AudioTrack`（`ENCODING_PCM_16BIT`）边收边播；Web 使用 Web Audio API 排队播放。

**请求头：** `Content-Type: application/json`；响应 `Content-Type: application/x-ndjson`。客户端应使用 **chunked 读取**（`curl -N`、OkHttp `BufferedReader`、Fetch `ReadableStream`），勿等待整段响应结束再解析。

**错误：** `503` 语音未启用；流中 `error` 行或 HTTP `400`（参数非法）。

#### 3.6.2 批量合成（兼容）

**`POST /api/tts`**

一次性返回完整 `AudioPayload`（内部仍走 WebSocket，等待全部音频分片拼合后响应）。适合不需边播的简易集成。

**请求：**

```json
{
  "text": "你好，我是 Pophie",
  "voice": { "tone": "温柔", "intonation": "平稳", "speed": "正常" },
  "voice_id": "gentle_female"
}
```

> `voice` 可省略，省略时按默认情感参数合成。

**响应 200：**

```json
{
  "text": "你好，我是 Pophie",
  "voice": { "tone": "温柔", "intonation": "平稳", "speed": "正常" },
  "audio": { "format": "mp3", "encoding": "base64", "sample_rate": 22050, "data": "..." }
}
```

> 批量接口默认 `speech.tts.format: mp3`；流式接口默认 `speech.tts.stream_format: pcm`。二者内部均通过 DashScope **WebSocket** 合成，差异在于响应形态（一次性 JSON vs NDJSON 流）。

---

### 3.7 主动感知 Tick

**`POST /api/tick`**

模拟「Living Loop」被动感知：根据环境信号 + 长期记忆，由 LLM 决策是否主动开口。

**请求：**

```json
{
  "robot_id": "default",
  "user_id": "小明",
  "session_id": "sess-a1b2c3d4",
  "signal": {
    "time": "15:00",
    "present": true,
    "identity": "小明",
    "facial_expression": "sad",
    "gesture": null,
    "posture": "slouched",
    "silence_min": 30,
    "scene": "独处"
  }
}
```

`signal` 为自由结构 JSON。XBot 端侧可借助持续运行的识别管线，把连续感知喂入：

| 字段 | 说明 |
|------|------|
| `time` | 当前时间（如 `15:00`） |
| `present` | 画面中是否有人 |
| `identity` | 识别到的人名 |
| `facial_expression` | 主脸表情（7 类 key，见 §2.1） |
| `gesture` | 当前手势 key |
| `posture` | 体姿态 |
| `silence_min` | 已静默分钟数 |
| `scene` | 场景描述，如 `独处` |

字段均为可选；服务端结合长期记忆由 LLM 决策是否主动开口。`user_id` 同 §1.2，仅作溯源。

**响应 200：**

```json
{
  "decision": "speak",
  "reason": "用户独处较久且记忆显示近期情绪低落",
  "content": "要不要休息一下？我给你放点轻音乐？",
  "used_memory_ids": [12, 15],
  "ts": "2026-06-15T15:00:00+08:00",
  "output": {
    "text": "要不要休息一下？我给你放点轻音乐？",
    "facial_expression": "neutral",
    "facial_expression_label": "中性",
    "robot_state": "idle",
    "robot_state_label": "待机",
    "voice": { "tone": "温柔", "intonation": "平稳", "speed": "正常" },
    "audio": null
  }
}
```

| 字段 | 说明 |
|------|------|
| `decision` | `speak` 或 `silent` |
| `content` | `speak` 时的主动发言文本 |
| `output` | 仅 `decision=speak` 时返回；`output.audio` 通常为 `null`，客户端应对 `content` / `output.text` 调 **`POST /api/tts/stream`** |

---

### 3.8 主动消息轮询

**`GET /api/proactive_messages`**

获取服务端记录的主动发言（`role=proactive`），适合客户端定时轮询。

**Query：**

| 参数 | 说明 |
|------|------|
| `robot_id` | 默认 `default` |
| `session_id` | 可选，按会话过滤 |
| `since_id` | 只返回 `id > since_id` 的消息，默认 `0` |
| `limit` | 条数上限，默认 `50` |

**响应 200：**

```json
{
  "items": [
    {
      "id": 101,
      "session_id": "sess-a1b2c3d4",
      "content": "记得今天是你妈妈的生日哦",
      "metadata": { "trigger": { "time": "09:00" }, "used_memory_ids": [8] },
      "created_at": "2026-06-15T09:00:00"
    }
  ],
  "last_id": 101
}
```

**轮询建议：** 每 3–10 秒请求一次，将上次 `last_id` 作为下次 `since_id`。`items[].content` 非空时，对每条消息调用 **`POST /api/tts/stream`** 播放（官方 Android 客户端已按此实现）。

---

### 3.9 记忆查询

**`GET /api/memories`**

| Query | 说明 |
|-------|------|
| `robot_id` | 默认 `default` |
| `layer` | 可选：`L2` / `L3` / `L4`（L1 仅通过 `/api/l1_frames` 获取） |
| `session_id` | 可选 |

**响应 200：**

```json
{
  "items": [
    {
      "id": 12,
      "robot_id": "default",
      "layer": "L3",
      "content": "...",
      "summary": "用户近期工作压力大",
      "emotion_score": -0.6,
      "importance": 0.72,
      "repetition_count": 2,
      "tags": ["工作", "压力"],
      "flow_path": [{ "layer": "L1", "ts": "...", "reason": "..." }],
      "session_id": "sess-a1b2c3d4",
      "created_at": "2026-06-15T10:00:00"
    }
  ]
}
```

---

**`GET /api/memories/{mem_id}`**

单条记忆详情。不存在返回 `404`。

---

**`GET /api/l1_frames`**

当前用户 L1 瞬时感知缓冲区快照。

**Query：** `robot_id`（可选）

**响应：** `{ "items": [ ... ] }`

---

### 3.10 对话历史

**`GET /api/conversations`**

| Query | 说明 |
|-------|------|
| `robot_id` | 默认 `default` |
| `session_id` | 可选 |
| `limit` | 默认 `100` |

**响应：** `{ "items": [ ... ] }`（按时间正序）

---

### 3.11 主动决策日志

**`GET /api/proactive_log`**

历史 tick 决策记录（含 `trigger`、`decision`、`content`）。

| Query | 说明 |
|-------|------|
| `robot_id` | 默认 `default` |
| `limit` | 默认 `50` |

---

### 3.12 提醒

**`GET /api/reminders`**

| Query | 说明 |
|-------|------|
| `robot_id` | 默认 `default` |
| `status` | 可选：`pending` / `fired` / `cancelled` |

---

**`DELETE /api/reminders/{reminder_id}`**

取消未触发的提醒。成功：`{ "ok": true, "id": 123 }`；不存在或已触发：`404`。

---

### 3.13 管理：清空数据

**`POST /api/admin/wipe`**

> 调试/重置用。若 `config.yaml` 中 `server.admin_token` 非空，所有 `/api/admin/*` 接口需在请求头携带 `X-Admin-Token`。

**请求：**

```json
{
  "scope": "memories",
  "robot_id": "robot-xxx"
}
```

`scope` 可选：`memories` | `conversations` | `reminders` | `all`（`all` 同时清空 `proactive_log`）

**响应：**

```json
{
  "ok": true,
  "scope": "memories",
  "deleted": { "memories": 42 }
}
```

`deleted` 键为实际删除的表名及行数，例如 `scope=all` 时可能包含 `memories`、`conversations`、`reminders`、`proactive_log`。

---

### 3.14 管理：机器人与记忆

管理后台页面：`GET /admin`（静态 HTML）。

所有以下接口路径前缀为 `/api/admin`，鉴权规则同 3.13。

#### 列出机器人

**`GET /api/admin/robots`**

| Query | 说明 |
|-------|------|
| `q` | 可选，模糊搜索 `robot_id` 或 `display_name` |

**响应：**

```json
{
  "items": [
    {
      "robot_id": "robot-xxx",
      "display_name": "客厅机器人",
      "created_at": "2026-06-16T10:00:00+08:00",
      "last_seen_at": "2026-06-16T12:00:00+08:00",
      "memories_count": 42,
      "conversations_count": 128,
      "reminders_pending": 2
    }
  ]
}
```

列表合并 `robots` 表与各业务表中的 `robot_id`（含仅有数据但未显式注册的孤儿 ID）。

#### 机器人详情

**`GET /api/admin/robots/{robot_id}`**

**响应：**

```json
{
  "robot_id": "robot-xxx",
  "display_name": "客厅机器人",
  "created_at": "2026-06-16T10:00:00+08:00",
  "last_seen_at": "2026-06-16T12:00:00+08:00",
  "memories_count": 42,
  "conversations_count": 128,
  "reminders_pending": 2,
  "proactive_log_count": 15,
  "session_count": 5,
  "layer_counts": { "L2": 10, "L3": 20, "L4": 12 }
}
```

不存在时返回 `404`。

#### 更新备注名

**`PATCH /api/admin/robots/{robot_id}`**

**请求：**

```json
{ "display_name": "客厅机器人" }
```

**响应：** 更新后的 `robots` 行。

#### 彻底删除机器人

**`DELETE /api/admin/robots/{robot_id}`**

删除该机器人在 `memories`、`conversations`、`reminders`、`proactive_log`、`robots` 中的全部数据，并清空进程内 L1 缓冲。

**响应：**

```json
{
  "ok": true,
  "robot_id": "robot-xxx",
  "deleted": {
    "memories": 42,
    "conversations": 128,
    "reminders": 3,
    "proactive_log": 15,
    "robots": 1
  }
}
```

#### 删除单条记忆

**`DELETE /api/admin/memories/{mem_id}?robot_id=robot-xxx`**

校验记忆归属后删除。成功：`{ "ok": true, "id": 123 }`；不存在或归属不匹配：`404`。

管理页还可复用现有只读接口（`GET /api/memories`、`/api/l1_frames`、`/api/conversations`、`/api/reminders`、`/api/proactive_log`）及 `DELETE /api/reminders/{id}`，均按 `robot_id` 过滤。

---

### 3.15 管理：系统配置

管理后台「系统配置」页可读写 `config.yaml`。

#### 读取配置

**`GET /api/admin/config`**

**响应：**

```json
{
  "config": {
    "llm": { "base_url": "...", "api_key": "***", "model": "..." },
    "memory": { "l2_session_cap": 20 },
    "server": { "host": "0.0.0.0", "port": 8000, "admin_token": "***" },
    "chat": { "defer_side_tasks": true },
    "speech": { "enabled": true }
  },
  "schema": [ { "section": "llm", "title": "LLM 模型", "fields": [] } ],
  "config_path": "/path/to/config.yaml",
  "restart_required_for": ["server.host", "server.port"]
}
```

敏感字段 `llm.api_key`、`server.admin_token` 以 `***` 掩码返回。

#### 保存配置

**`PUT /api/admin/config`**

**请求：** 传入需更新的顶层 section（与现有配置深度合并）：

```json
{
  "config": {
    "llm": { "model": "deepseek-v4-flash", "temperature": 0.7 },
    "memory": { "l2_to_l3_importance": 0.55 }
  }
}
```

若 `api_key` / `admin_token` 传 `***` 或留空，保留文件中已有值。

**响应：**

```json
{
  "ok": true,
  "config": { "...": "掩码后的完整配置" },
  "restart_required_for": ["server.host", "server.port"]
}
```

保存后 LLM / 记忆 / 聊天 / 语音参数立即热更新；`server.host` / `server.port` 需重启服务生效。

---

### 3.16 主人档案

> 这些是**普通接口**（非 `/api/admin/*`），无需 `X-Admin-Token`，因为它们属于客户端「首次激活向导」流程。所有数据按 `robot_id` 隔离，与现有记忆/会话的隔离模型一致。一条 `robot_id` 至多对应一份主人档案。`OwnerProfile` 字段定义见 §2.8。

#### 注册/更新主人档案

**`PUT /api/robots/{robot_id}/owner`** — 幂等 upsert。已存在则覆盖，不存在则创建。

**请求体：**

```json
{
  "owner": {
    "nickname": "小明",
    "robot_name": "狗蛋",
    "gender": "male",
    "birthday": "1990-01-01",
    "face_registered": true
  }
}
```

**响应 200：**

```json
{
  "ok": true,
  "robot_id": "robot-xxx",
  "owner": {
    "nickname": "小明",
    "robot_name": "狗蛋",
    "gender": "male",
    "birthday": "1990-01-01",
    "face_registered": true
  }
}
```

| 字段 | 说明 |
|------|------|
| `ok` | 操作是否成功 |
| `robot_id` | 回显归属机器人 ID |
| `owner` | 服务端落库后的主人档案（已做字段校验与归一，如 `gender` 非法时置 `null`） |

#### 查询主人档案

**`GET /api/robots/{robot_id}/owner`**

**响应 200：** 同上，但不含 `ok`/`robot_id` 外层，直接返回 `owner` 对象（或返回 `{"owner": {...}}`，以服务端 `/docs` 为准）。

```json
{
  "nickname": "小明",
  "robot_name": "狗蛋",
  "gender": "male",
  "birthday": "1990-01-01",
  "face_registered": true
}
```

**响应 404：** 该 `robot_id` 未注册主人档案，`{"detail": "owner not found"}`。

#### 删除主人档案

**`DELETE /api/robots/{robot_id}/owner`** — 对应客户端「重新设置主人」/恢复出厂流程。仅删除主人档案本身，**不清空**该机器人下的记忆/对话/提醒（如需一并清除，另调 §3.13 `POST /api/admin/wipe`）。

**响应 200：**

```json
{
  "ok": true,
  "robot_id": "robot-xxx"
}
```

**响应 404：** 该 `robot_id` 未注册主人档案。

#### 错误

| HTTP | 场景 |
|------|------|
| 400 | `owner` 缺失或 `nickname` / `robot_name` 为空；`gender` 非法（除合法三值与 `null` 外） |
| 404 | `GET` / `DELETE` 时该 `robot_id` 无主人档案 |

#### 客户端同步策略

端侧采用**本地优先 + 后端 best-effort**：

- 向导完成时本地档案立即生效并进入主界面，**不阻塞**于网络；
- 完成时调 `PUT`，失败则静默记录「待同步」，后续后台重试；
- 重置时调 `DELETE`（best-effort），失败不阻断本地重置。

---

## 4. 客户端集成要点

### 4.1 Android

- Base URL 示例：`http://192.168.1.100:8000/`（末尾斜杠可有可无）
- 录音：16kHz 单声道 PCM → WAV → Base64 → `input.audio`
- **聊天 TTS（推荐）：**
  1. `ChatInput.skip_tts = true` 调 `POST /api/chat`
  2. 展示 `output.text` 后立刻 `POST /api/tts/stream`
  3. 解析 NDJSON：`meta` 后收到 `chunk` 即用 `AudioTrack`（PCM 16-bit mono）写入播放
- **兼容：** 若 `output.audio` 非空（未设 `skip_tts`），仍可用 `MediaPlayer` 播完整 MP3/WAV
- 设置页配置 `voice_id`，写入 `ChatInput.voice_id` 与流式 TTS 请求的 `voice_id`
- 流式请求体与 `TtsRequest` 相同：`text`、`voice`（取自 `output.voice`）、`voice_id`
- 语音 STT 失败时 `output.text` 为空，应提示「未能识别语音」
- 主动消息：`GET /api/proactive_messages?since_id=<last>`，播放走 `/api/tts/stream`

### 4.2 Web

- 静态页入口：`GET /`（同域调用 API，无 CORS 问题）
- 参考实现见 `frontend/index.html`：`buildChatInput` 默认 `skip_tts: true`，`playReplyAudio` → `playStreamingTts`

### 4.3 局域网访问

服务端需配置 `server.host: "0.0.0.0"`，客户端使用电脑局域网 IP，非 `127.0.0.1`。

### 4.4 端侧（XBot）对接与 FSM 映射

XBot 端侧在本地完成表情/身份/手势识别，把结果作为感知通道发给后端，再用后端回复驱动虚拟宠物：

**上行（端侧 → 后端）**

- 表情：使用 §2.1 定义的 7 类标准 key（如 `happy`、`sad`、`surprise`）。
- 身份（认识我）：放 `input.perception.identity`（喂大模型）；如需溯源回显另放顶层 `user_id`。
- 手势：放 `input.perception.gesture.type`（见 §2.4 取值），已进入大模型。
- 持续感知 / 主动陪伴：用 `POST /api/tick`（见 §3.7）周期上报在场/身份/表情/静默。

**下行（后端 → 端侧虚拟宠物 FSM）**

- 优先使用 `output.robot_state`：机器人回复直接携带**互动动作状态**，**保证是 FSM 9 态之一**（`idle`/`gazing`/`listening`/`thinking`/`happy`/`confused`/`sleepy`/`sleeping`/`waking`），端侧可直接据此切换虚拟形象，无需再做映射或校验。
- 兜底映射：仅当历史数据/旧响应没有 `robot_state` 时，可用 `output.facial_expression` 按下表（= `GET /api/schema` 的 `fsm_state_mapping`）推导：

| 后端表情 | 虚拟宠物状态 |
|----------|--------------|
| `happy` | `happy` |
| `neutral` | `idle`（用户在场时可用 `gazing`） |
| `sad` | `sleepy` |
| `angry` / `disgust` / `fear` / `surprise` | `confused` |

生命周期状态仍由端侧本地控制：等待后端响应时 `thinking`，录音中 `listening`，注视跟随沿用端侧主脸位置逻辑。

---

## 5. 语音引擎说明

| 能力 | 引擎 | 协议 | 对外 HTTP 接口 |
|------|------|------|----------------|
| STT | 阿里云 `qwen3-asr-flash-realtime` | WebSocket 实时识别 | `POST /api/stt`、`/api/chat`（`input.audio`） |
| TTS | 阿里云 `cosyvoice-v3-flash` | **WebSocket 实时合成** | **`POST /api/tts/stream`（推荐）**、`POST /api/tts`、可选 `inline_tts` |

`GET /api/health` 的 `tts_engine` 为 `dashscope-realtime` 表示 TTS 已启用。

`speech.enabled: false` 时：

- `/api/chat` 仍可用，但无 STT/TTS，`output.audio` 为 `null`
- `/api/stt`、`/api/tts`、`/api/tts/stream` 返回 `503`

### 5.1 客户端 TTS 策略（必读）

| 方式 | 首声延迟 | 适用 |
|------|----------|------|
| **`skip_tts` + `/api/tts/stream`** | **低**（LLM 结束后 ~500ms 可开口） | **官方 Web / Android 默认** |
| `inline_tts` 内嵌 `output.audio` | 较高（需等整段 MP3 合成） | 简易集成、调试 |
| `POST /api/tts` 批量 | 较高 | 不需边播的场景 |

**推荐流程：**

1. `POST /api/chat`，`input.skip_tts: true`
2. 用 `output.text`、`output.voice`、`input.voice_id` 调 `POST /api/tts/stream`
3. 按 NDJSON 行解析，`chunk` 到达即播放

`chat.inline_tts: false` 或请求中 `skip_tts: true` 时，`output.audio` 恒为 `null`，**必须**走 `/api/tts/stream` 或 `/api/tts`。

---

## 6. 错误码汇总

| HTTP | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 请求参数不合法、STT/TTS 处理失败 |
| 404 | 资源不存在 |
| 503 | 语音功能未启用 |

业务失败时（如 LLM 超时、STT 无结果），`/api/chat` 仍返回 **200**，`output.text` 为空字符串（静默），不会附带错误说明字段。

---

## 7. cURL 示例

```bash
# 健康检查
curl http://127.0.0.1:8000/api/health

# 新建会话
curl -X POST http://127.0.0.1:8000/api/session/new

# 文字聊天（推荐：跳过内嵌 TTS）
curl -X POST http://127.0.0.1:8000/api/chat \
  -H "Content-Type: application/json" \
  -d '{"input":{"text":"你好","skip_tts":true}}'

# 仅表情
curl -X POST http://127.0.0.1:8000/api/chat \
  -H "Content-Type: application/json" \
  -d '{"input":{"text":"","perception":{"facial_expression":"happy"},"skip_tts":true}}'

# Schema
curl http://127.0.0.1:8000/api/schema

# 流式 TTS（NDJSON 行流，-N 禁用缓冲）
curl -N -X POST http://127.0.0.1:8000/api/tts/stream \
  -H "Content-Type: application/json" \
  -d '{"text":"你好，我是 Pophie","voice_id":"gentle_female"}'

# 批量 TTS（兼容，返回完整 MP3）
curl -X POST http://127.0.0.1:8000/api/tts \
  -H "Content-Type: application/json" \
  -d '{"text":"你好","voice_id":"gentle_female"}'

# 注册/更新主人档案（首次激活向导完成时）
curl -X PUT http://127.0.0.1:8000/api/robots/robot-xxx/owner \
  -H "Content-Type: application/json" \
  -d '{"owner":{"nickname":"小明","robot_name":"狗蛋","gender":"male","birthday":"1990-01-01","face_registered":true}}'

# 查询主人档案
curl http://127.0.0.1:8000/api/robots/robot-xxx/owner

# 删除主人档案（重新设置主人）
curl -X DELETE http://127.0.0.1:8000/api/robots/robot-xxx/owner
```

---

## 8. OpenAPI

服务基于 FastAPI 构建，启动后可访问交互式文档：

- Swagger UI：`http://<host>:8000/docs`
- ReDoc：`http://<host>:8000/redoc`

本文档侧重**对接约定与示例**；字段的完整 JSON Schema 以运行时 `/docs` 为准。
