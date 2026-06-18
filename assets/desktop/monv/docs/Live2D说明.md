# Live2D 技术说明与商用指南

本文档结合本项目的实现方式，介绍 Live2D 是什么、能否商用、人物模型如何组织，以及表情系统如何工作。

> **免责声明**：商用许可以 Live2D 官方最新条款及各模型作者/版权方的授权为准。本文仅供技术理解与内部参考，不构成法律意见。

---

## 1. Live2D 是什么？

**Live2D** 是一种将 **2D 插画** 在运行时做 **网格变形（Mesh Deformation）** 的技术，让平面角色看起来有立体感，并能做眨眼、口型、头发物理、身体动作等。

和 3D 模型不同，Live2D 不建三维网格，而是：

1. 在 **Live2D Cubism Editor** 里对分层 PSD 切图、绑骨、设参数；
2. 导出 **运行时资源**（`.moc3`、贴图、动作、表情等）；
3. 在网页/App 里通过 **Cubism SDK**（或封装库如 `pixi-live2d-display`）加载并驱动。

### 本项目的架构

```
浏览器
  ├── PixiJS 7              → WebGL 渲染画布
  ├── pixi-live2d-display   → Live2D Cubism 4 的 JS 封装
  ├── live2dcubismcore      → Live2D 官方核心运行时
  └── 本项目代码
        ├── js/config.js    → 角色列表、表情映射、缩放等配置
        ├── js/live2d.js    → 加载模型、表情、动作、拖拽缩放
        └── js/app.js       → UI 控制面板
```

---

## 2. Live2D 能否商用？

商用要分 **两层许可** 来看，缺一不可：

| 层级 | 指什么 | 能否商用的关键 |
|------|--------|----------------|
| **① Cubism SDK / 运行时** | Live2D 官方提供的 `live2dcubismcore`、Editor 导出协议等 | 需符合 [Live2D SDK 许可协议](https://www.live2d.com/eula/) |
| **② 角色模型素材** | 每个 `.model3.json` 及其贴图、动作、表情 | 需符合 **该模型自己的授权**（作者/版权方/平台规则） |

**结论：SDK 允许商用 ≠ 模型可以商用。** 两者都要合规。

### 2.1 Cubism SDK（技术层）

- Live2D 提供 **Free / Pro** 等 SDK 方案，商用通常需注册开发者、确认对应 EULA。
- 网页端使用的 `live2dcubismcore.min.js` 属于官方 SDK 运行时，上线商业产品前应阅读：
  - [Live2D SDK 使用条款](https://www.live2d.com/eula/)
  - [Cubism SDK 下载与许可说明](https://www.live2d.com/download/cubism-sdk/)

一般个人学习、内部 Demo **不等于** 自动获得商业发行权，以官方最新条款为准。

### 2.2 角色模型（素材层）

每个模型的 ReadMe / 购买页 / Booth 页面会单独约定：

- 是否允许 **商用**
- 是否允许 **二次修改**
- 是否必须 **署名**
- 是否禁止用于 **AI 训练、色情、政治敏感** 等用途

#### 本项目当前模型情况（摘要）

| 模型 | 来源 | 商用提示 |
|------|------|----------|
| **Haru** | pixi-live2d-display 测试资源 | 官方示例数据，商用需查 Live2D Sample Data 许可 |
| **Mao** | Live2D 官方 Sample（Niziiro Mao PRO） | ReadMe 写明：一般用户/小规模企业可在接受「Free Material License Agreement」后 **商用** |
| **甘雨 / 藿藿 / 魔女** | 社区/Booth 等第三方模型 | 通常涉及 **游戏/动漫 IP 或画师版权**，**默认不可随意商用**，需原作者与 IP 方授权 |

> 第三方同人模型即使「免费下载」，也常见 **仅限个人非商用** 或 **直播/视频可用但不可做产品内置** 等限制，务必阅读模型包内说明。

### 2.3 商用前检查清单

- [ ] 已确认 Cubism SDK 许可覆盖你的发布渠道（Web / App / 线下等）
- [ ] 已阅读每个模型的 License / ReadMe / 购买协议
- [ ] 模型不涉及未授权 IP（如游戏角色、动漫形象）
- [ ] 如需商用，保留授权邮件、购买凭证、署名要求
- [ ] 上线前移除未授权测试模型，或替换为自有/已购商用模型

---

## 3. 人物模型是怎么实现的？

### 3.1 制作流程（Editor 侧）

```
原画 PSD（分层：脸、眼、发、身体…）
    ↓ Live2D Cubism Editor
绑定变形器（Deformer）+ 参数（Parameter）
    ↓ 可选：物理（Physics）、姿态（Pose）
导出 Runtime 文件
```

### 3.2 运行时文件结构

以本项目「魔女」模型为例，核心入口是 `魔女.model3.json`：

```json
{
  "Version": 3,
  "FileReferences": {
    "Moc": "魔女.moc3",
    "Textures": ["魔女.8192/texture_00.png", "魔女.8192/texture_01.png"],
    "Physics": "魔女.physics3.json",
    "DisplayInfo": "魔女.cdi3.json",
    "Expressions": [
      { "Name": "h", "File": "h.exp3.json" },
      { "Name": "sq", "File": "sq.exp3.json" }
    ],
    "Motions": {
      "Idle": [{ "File": "Scene1.motion3.json" }]
    }
  },
  "Groups": [
    { "Target": "Parameter", "Name": "EyeBlink", "Ids": ["ParamEyeLOpen", "ParamEyeROpen"] },
    { "Target": "Parameter", "Name": "LipSync", "Ids": ["ParamMouthOpenY"] }
  ]
}
```

| 文件 | 作用 |
|------|------|
| `.model3.json` | **入口清单**：索引 moc、贴图、表情、动作、物理等 |
| `.moc3` | **模型二进制**：网格、变形器、参数定义（不可手改） |
| `texture_*.png` | **贴图** |
| `.physics3.json` | **物理**：头发、裙摆等惯性摆动 |
| `.pose3.json` | **姿态**：多部件显示/隐藏切换（如 arm A/B） |
| `.motion3.json` | **动作**：随时间改变参数的关键帧动画 |
| `.exp3.json` | **表情**：一组参数目标值 |
| `.cdi3.json` | Editor 显示辅助信息 |

### 3.3 本项目如何加载模型

配置在 `js/config.js` 的 `MODEL_LIST` 中，每个角色包含：

```javascript
{
    id: "mao_pro",
    name: "Mao",
    url: "./models/mao_pro/runtime/mao_pro.model3.json",
    kScale: 0.45,              // 默认缩放
    initialXshift: -40,        // 初始水平偏移
    initialYshift: 40,         // 初始垂直偏移
    idleMotionGroupName: "Idle", // 待机动作组名
    expressions: [ /* 见下一节 */ ]
}
```

加载流程（`js/live2d.js`）：

```
PIXI.live2d.Live2DModel.from(config.url)
    → 解析 model3.json，拉取 moc3 / 贴图 / 物理等
    → 挂到 PixiJS Container（modelRoot）
    → 居中、应用 kScale、播放 Idle 动作
    → 绑定点击区域（HitArea）触发 Tap 动作
```

**注意**：必须通过 HTTP 服务访问（如 `python3 -m http.server`），不能直接 `file://` 打开，否则浏览器会拦截本地资源请求。

### 3.4 渲染与交互

- **渲染**：PixiJS WebGL 每帧绘制 Live2D 网格
- **Idle 动作**：`model.motion("Idle")` 循环播放待机
- **拖拽/缩放**：在 `modelRoot` 容器上变换，不影响模型内部参数
- **点击反馈**：模型若配置了 HitArea，`model.on("hit")` 可触发 `tapMotions` 里定义的动作

---

## 4. 表情细节是如何实现的？

Live2D 的「表情」不是换一张图片，而是 **修改一组模型参数** 到预设值，并可带淡入淡出。

### 4.1 表情文件 `.exp3.json`

「魔女」的 `h.exp3.json`（黑脸）示例：

```json
{
  "Type": "Live2D Expression",
  "Parameters": [
    {
      "Id": "Param69",
      "Value": 30,
      "Blend": "Add"
    }
  ]
}
```

Mao 官方示例表情则更复杂，会同时改多参数：

```json
{
  "Type": "Live2D Expression",
  "FadeInTime": 0.5,
  "FadeOutTime": 0.5,
  "Parameters": [
    { "Id": "ParamEyeLOpen", "Value": 1, "Blend": "Multiply" },
    { "Id": "ParamEyeLSmile", "Value": 0, "Blend": "Add" },
    { "Id": "ParamMouthForm", "Value": 0, "Blend": "Add" }
  ]
}
```

| 字段 | 含义 |
|------|------|
| `Id` | 模型参数名（在 Editor 里定义，如眼睛开合、眉毛角度） |
| `Value` | 该参数在表情下的目标值 |
| `Blend` | 混合方式：`Add` 叠加、`Multiply` 相乘等 |
| `FadeInTime` / `FadeOutTime` | 切换表情时的过渡时间（秒） |

Editor 里画师/建模师调好每个表情对应的参数组合，导出后即 `.exp3.json`。

### 4.2 在 model3.json 中注册表情

```json
"Expressions": [
  { "Name": "sq", "File": "sq.exp3.json" },
  { "Name": "h",  "File": "h.exp3.json" }
]
```

- `Name`：运行时调用的 **表情 ID**（字符串或索引，取决于 SDK 封装）
- `File`：对应表情数据文件

若模型包缺少 `Expressions` 字段，需要在 `model3.json` 中 **手动补全**（本项目对魔女等模型做过此类补全）。

### 4.3 本项目表情切换的整体流程

从用户点击按钮到角色变脸，数据经过 **四层**，每层职责不同：

```
用户操作（点击按钮 / 按数字键 1~9）
    ↓
js/app.js          UI 层：找到 expressionKey，更新按钮高亮
    ↓
js/config.js       配置层：key → value / animate 映射
    ↓
js/live2d.js       渲染层：调用 SDK、管理动画循环
    ↓
pixi-live2d-display → model.expression() / coreModel.setParameterValueById()
    ↓
Live2D 运行时       读取 .exp3.json，插值混合参数，每帧渲染
```

**时序说明：**

1. 页面加载时，`app.js` 根据 `CURRENT_MODEL.expressions` 动态生成表情按钮。
2. 用户点击「快乐」→ `app.switchExpression("joy")`。
3. `live2d.js` 的 `setExpressionByKey` 在配置里查到 `{ key: "joy", value: "joy", ... }`。
4. 调用 `model.expression("joy")`，SDK 去 `model3.json` 的 `Expressions` 里找 `Name: "joy"`，加载 `joy.exp3.json`。
5. 运行时按 `FadeInTime` 把眉毛、嘴巴、道具参数等平滑过渡到目标值。
6. 若该表情配置了 `animate` 字段，则在表情应用后启动额外的参数动画（如说话口型）。

### 4.4 配置层：UI 映射（config.js）

模型自带的表情 ID 往往不直观（如 `sq`、`CHIDAI`），因此在 `config.js` 的每个角色项里配置 `expressions` 数组，做 **UI → 运行时** 的映射。

以魔女为例：

```javascript
{
    id: "monv",
    name: "魔女",
    defaultExpression: "calm",   // 加载模型后默认应用的表情
    expressions: [
        { key: "calm",   value: "calm",   label: "平静",   icon: "😌" },
        { key: "joy",    value: "joy",    label: "快乐",   icon: "😊" },
        { key: "thinking", value: "thinking", label: "思考中", icon: "🤔", animate: "thinking" },
        { key: "speaking", value: "speaking", label: "说话中", icon: "💬", animate: "speaking" },
        { key: "singing",  value: "singing",  label: "唱歌中", icon: "🎤", animate: "singing" }
    ]
}
```

| 字段 | 作用 |
|------|------|
| `key` | 前端按钮内部标识，`dataset.expression` 与快捷键索引都依赖它 |
| `value` | 传给 `model.expression()` 的表情名或索引 |
| `label` / `icon` | 控制面板按钮上的文字与图标 |
| `reset: true` | 配合 `value: null`，表示「恢复默认脸」而非加载某个 exp 文件 |
| `animate` | （可选）启动 `live2d.js` 中的运行时参数动画，见 4.7 节 |
| `defaultExpression` | 模型加载完成后自动应用的表情；`null` 表示不叠加任何表情 |

**两种 `value` 写法：**

| 方式 | 适用模型 | 示例 | SDK 调用 |
|------|----------|------|----------|
| **按索引** | Haru、Mao 等官方样例 | `value: 0, 1, 2…` | `model.expression(0)` |
| **按名称** | 甘雨、魔女等第三方模型 | `value: "joy"` | `model.expression("joy")` |

索引顺序与 `model3.json` 里 `Expressions` 数组的下标一致；名称则与其中 `Name` 字段一一对应。

**恢复默认脸：**

甘雨、藿藿等模型用 `reset: true` + `value: null`：

```javascript
{ key: "neutral", value: null, label: "默认", icon: "😐", reset: true }
```

此时不走 `model.expression()`，而是调用 `expressionManager.resetExpression()` 清掉当前表情叠加。

### 4.5 UI 层：按钮与快捷键（app.js）

`createExpressionButtons()` 在每次切换角色后执行，根据当前角色的 `expressions` 数组生成按钮：

```javascript
expressions.forEach((expression, index) => {
    button.dataset.expression = expression.key;
    button.title = `快捷键 ${index + 1}`;
    button.addEventListener("click", () => this.switchExpression(expression.key));
});
```

`switchExpression(expressionKey)` 做三件事：

1. 调用 `renderer.setExpressionByKey(expressionKey, CURRENT_MODEL.expressions)`
2. 记录 `this.currentExpression` 供 UI 状态使用
3. 给对应按钮加上 `active` 样式

键盘快捷键：按数字键 `1` ~ `9` 会切换到 `expressions` 数组对应下标的表情（与按钮顺序一致）。

### 4.6 渲染层：切换与重置（live2d.js）

#### 4.6.1 加载模型时的默认表情

`loadModel(config)` 在模型挂载到画布后：

```javascript
const defaultExpression = config.defaultExpression ?? 0;
if (defaultExpression === null) {
    this.resetExpression();
} else {
    this.setExpression(defaultExpression);
}
```

魔女默认 `defaultExpression: "calm"`，因此一加载就是平静脸。切换角色时会先 `stopExpressionAnimation()` 清掉上一个角色的口型动画。

#### 4.6.2 按 key 切换（主入口）

```javascript
setExpressionByKey(expressionKey, expressions) {
    const item = expressions.find((entry) => entry.key === expressionKey);
    if (!item) return;

    this.stopExpressionAnimation();          // 先停掉上一表情可能带的循环动画

    if (item.reset || item.value === null) {
        this.resetExpression();
        return;
    }

    this.setExpression(item.value);          // model.expression(value)
    if (item.animate) {
        this.startExpressionAnimation(item.animate);
    }
}
```

#### 4.6.3 重置表情

```javascript
resetExpression() {
    this.stopExpressionAnimation();
    const manager = this.model.internalModel?.motionManager?.expressionManager;
    if (manager?.resetExpression) {
        manager.resetExpression();
        return;
    }
    this.model.expression("");
}
```

#### 4.6.4 SDK 底层对象关系

理解调试时可用：

```
Live2DModel (pixi-live2d-display)
  └── internalModel
        ├── coreModel          → setParameterValueById() 直接改参数
        └── motionManager
              ├── expressionManager  → 管理 .exp3.json 表情混合
              └── 动作播放、Idle 等
```

`model.expression("joy")` 走 **expressionManager**；运行时动画（口型等）走 **coreModel** 直接写参数，见下一节。

切换时 Live2D 会在当前参数与目标参数之间 **插值过渡**（受 `FadeInTime` 影响），所以静态表情变化是平滑的。

### 4.7 运行时表情动画（animate 字段）

部分表情仅靠 `.exp3.json` 的静态快照不够（例如说话要张嘴、唱歌要摆装饰），本项目在 `live2d.js` 中增加了 **Ticker 循环动画**。

配置方式：在 `config.js` 的表达式项上加 `animate: "类型名"`：

```javascript
{ key: "speaking", value: "speaking", label: "说话中", animate: "speaking" }
```

`animate` 的值与 `tickExpressionAnimation()` 里的 `switch` 分支对应：

| animate 值 | 静态表情（.exp3.json）负责 | 运行时动画负责 |
|------------|---------------------------|----------------|
| `thinking` | 小幽灵、皱眉等 | 眼球左右看、头部轻微晃动 |
| `speaking` | 睁眼、嘴型基底 | `ParamMouthOpenY` 正弦波模拟张嘴 |
| `singing` | 话筒手势、眯眼笑 | 嘴巴张合 + `Param34/35/36` 飘动装饰 |

实现要点：

```javascript
// 在 Pixi Ticker 的 LOW 优先级执行，确保在 SDK 更新完表情之后再改参数
PIXI.Ticker.shared.add(this.expressionAnimHandler, undefined, PIXI.UPDATE_PRIORITY.LOW);

tickExpressionAnimation() {
    const t = (performance.now() - this.expressionAnimStart) / 1000;
    // 例：说话口型
    core.setParameterValueById("ParamMouthOpenY", 0.15 + Math.abs(Math.sin(t * 11)) * 0.7);
}
```

**生命周期：**

- 切换到带 `animate` 的表情 → `startExpressionAnimation()`
- 切换到其他表情 / 重置 / 换模型 / 销毁渲染器 → `stopExpressionAnimation()`

若要新增一种动态表情，需要同时改三处：`xxx.exp3.json`（静态基底）、`config.js`（加 `animate`）、`live2d.js`（`tickExpressionAnimation` 加分支）。

### 4.8 完整示例：新增「快乐」表情（魔女）

**第一步：准备表情文件** `models/魔女/joy.exp3.json`

```json
{
  "Type": "Live2D Expression",
  "Parameters": [
    { "Id": "ParamMouthForm", "Value": 1, "Blend": "Add" },
    { "Id": "Param59", "Value": 30, "Blend": "Add" }
  ]
}
```

`Param59` 在该模型里是「星星眼」道具参数，只在快乐表情里开启，其它表情（如唱歌）不启用，避免眼内出现不需要的黄色星星。

**第二步：在 model3.json 注册**

```json
{ "Name": "joy", "File": "joy.exp3.json" }
```

`Name` 必须与 `config.js` 里的 `value` 一致。

**第三步：在 config.js 加 UI 映射**

```javascript
{ key: "joy", value: "joy", label: "快乐", icon: "😊" }
```

刷新页面后控制面板会出现新按钮，点击即调用 `model.expression("joy")`。

### 4.9 如何查参数该填什么？

第三方模型的参数名不直观，可用以下方式对照：

1. **看 `xxx.cdi3.json`**：里面有参数 Id 与中文备注，如 `Param59 → 星星`、`Param64 → cw（小幽灵）`。
2. **看同模型已有 `.exp3.json`**：如 `hdj.exp3.json` 只改 `Param65`，说明蝴蝶结对应这个参数。
3. **看 `xxx.vtube.json`**（若有）：VTube Studio 配置里 Hotkey 的 `Name` 字段标注了表情用途，如「话筒手势」对应 `zs2.exp3.json`。
4. **在 Cubism Editor 打开工程**：最准确，但需要有源文件。

### 4.10 表情 vs 动作

| | 表情（Expression） | 动作（Motion） |
|--|-------------------|----------------|
| 文件 | `.exp3.json` | `.motion3.json` |
| 特点 | 参数快照，可切换/叠加；本项目部分表情叠加 Ticker 动画 | 随时间变化的关键帧动画 |
| 典型用途 | 生气脸、害羞脸、道具显示、说话口型 | 挥手、转身、Idle 呼吸 |
| 本项目入口 | 控制面板「表情」区 | `idleMotionGroupName: "Idle"` + 点击 `tapMotions` |
| 调用方式 | `model.expression(name)` | `model.motion("Idle")` |

表情与动作 **可同时存在**：魔女加载后一直播放 `Idle` 待机动作，切换表情只改面部表情参数，两者由 SDK 在每帧合并计算。

部分模型（如 Mao PRO）还有「魔法特效动作」等与常规表情分开的资源，需看模型 ReadMe。

---

## 5. 如何新增一个角色？

1. 将模型 Runtime 文件夹放入 `models/你的角色名/`
2. 确认 `xxx.model3.json` 中 `Expressions`、`Motions` 完整（缺了需手动补，见 4.2 节）
3. 在 `js/config.js` 的 `MODEL_LIST` 追加一项，填写 `url`、`kScale`、`defaultExpression`、`expressions` 映射
4. 启动本地服务器，在控制面板切换角色验证

**仅新增表情（不换角色）** 按 4.8 节三步走：写 `.exp3.json` → 注册 `model3.json` → 配置 `config.js`。

若表情需要持续动效（说话、思考等），额外在 `config.js` 加 `animate` 并在 `live2d.js` 的 `tickExpressionAnimation` 里实现对应分支（见 4.7 节）。

---

## 6. 参考链接

- [Live2D 官网](https://www.live2d.com/)
- [Cubism SDK 下载](https://www.live2d.com/download/cubism-sdk/)
- [Live2D 官方示例数据与许可](https://www.live2d.com/en/download/sample-data/)
- [pixi-live2d-display（本项目使用的封装库）](https://github.com/guansss/pixi-live2d-display)
- [Open LLM Vtuber Live2D 文档（项目参考来源）](https://docs.llmvtuber.com/docs/user-guide/live2d/)

---

## 7. 一句话总结

- **Live2D** = 2D 插画的参数化变形动画技术。
- **能否商用** = SDK 许可 + 每个模型素材许可，**两者都要看**。
- **人物模型** = `model3.json` 索引的一整套 moc / 贴图 / 物理 / 动作资源，由 Cubism SDK 在运行时驱动。
- **表情切换** = `app.js`（UI）→ `config.js`（key/value 映射）→ `live2d.js`（`model.expression()` + 可选 Ticker 动画）→ Live2D 运行时插值渲染。
