# 模型资源托管（开发/测试）

XBot 的重型模型（ASR paraformer 237MB + 声纹 CAM++ 28MB）已从 APK 移出，改为首次启动从 CDN 下载到 `filesDir/xbot_models/`。本文档说明如何托管这些文件并配置 app。

## 当前默认：七牛云 Kodo 对象存储

选七牛云的原因：**国内访问延迟最低**，实名认证用户每月有免费额度（标准存储空间 + CDN 回源流量 + 请求数，先抵扣免费额度再按量计费），适合 265MB 模型集在开发期反复下载。

### 1. 创建存储空间（bucket）

1. 注册七牛云账号 → 完成实名认证（个人/企业）。
2. 控制台 → **对象存储 Kodo** → **空间管理** → 新建空间，名字例如 `xbot-models`，存储区域选离你最近的（如华东）。
3. **绑定访问域名**（重要）：
   - 七牛会分配一个**测试域名**（形如 `xxx.qnssl.com` / `xxx.qiniudn.com`），但**仅 HTTP 且 30 天过期**——开发临时可用，不建议长期。
   - **推荐（生产/稳定开发）**：绑定自有域名 `models.yourdomain.com`，开启 HTTPS（需在七牛上传 SSL 证书或用七牛免费证书）。控制台 → 空间 → **域名管理** → 自定义 CDN 加速域名。

> ⚠️ Android 9+ 默认不允许明文 HTTP。若用 HTTP 测试域名，需在 `AndroidManifest.xml` 的 `<application>` 加 `android:usesCleartextTraffic="true"`（仅开发期）。生产务必用 HTTPS。

### 2. 安装 qshell 并配置密钥

1. 下载 qshell：https://github.com/qiniu/qshell/releases （选对应平台版本），解压后放到 PATH，或记住路径。
2. 配置账户密钥（个人中心 → 密钥管理 → 拿 AccessKey / SecretKey）：

```bash
qshell account <AccessKey> <SecretKey>
```

### 3. 上传模型 + 生成 manifest

把待上传的模型放进仓库的 `models-upload/`（已镜像 assets 结构），然后：

```bash
BUCKET=xbot-models ./scripts/upload-models-qiniu.sh
```

脚本会：
- 计算 `models-upload/` 下每个文件的 `size` + `sha256`，生成 `manifest.json`；
- 大文件（>10MB，含 165MB encoder）用 `rput` 分片断点续传上传，小文件用 `fput`；
- 把所有文件（按相对路径作为 key，保留斜杠镜像目录结构）和 `manifest.json` 上传到 bucket 根。

> 改了模型后重跑脚本即可；可设 `VERSION=2 BUCKET=... ./scripts/...` 递增清单版本号（app 据此可强制重下）。
> 若 qshell 不在 PATH，可设环境变量：`QSHELL=/path/to/qshell ./scripts/...`

### 4. 把公开域名填进 app

打开 `app/src/main/java/com/xbot/android/core/ResourceManager.kt`，改 `BASE_URL`：

```kotlin
const val BASE_URL = "https://models.yourdomain.com/"   // 七牛绑定域（含末尾斜杠）
```

重新装 app 即可。首次启动会进入下载页。

### 5. 七牛额度与费用提醒

- 免费额度（存储/回源流量/请求数）每月抵扣，超额按量计费。具体数值以官方价格页为准：
  https://www.qiniu.com/prices/kodo
- **HTTPS 流量**需绑定自有域名 + 证书；HTTP 回源流量在免费额度内。
- 开发期反复下载 265MB 模型，注意监控流量消耗，避免意外计费。可在七牛控制台设置用量告警。

---

## 切换到其它平台（只改 BASE_URL）

下载逻辑只依赖一个 HTTPS 目录 + `manifest.json`，换平台无需改代码。

### Cloudflare R2（海外，出口流量永久免费）

10GB 免费存储 + **出口流量永久免费**（国内访问偏慢/不稳）。建 bucket → 开 `r2.dev` 公开子域 → 用 `wrangler r2 object put` 上传。`BASE_URL = "https://pub-xxxxxxxx.r2.dev/"`。

### GitHub Releases（2GB 内单文件，零基础设施）

每个文件 < 2GB 时可用（encoder.int8.onnx = 165MB，达标）。注意 Releases 的 asset 路径是扁平的（不支持子目录），用 Releases 时需把 manifest 的 `path` 设为扁平文件名。

### 自建 nginx（完全可控，可加鉴权）

把 `models-upload/` 内容放到 web 目录：

```nginx
server {
    listen 80;
    server_name models.local;
    location / { root /var/www/xbot-models; autoindex on; }
}
```

`BASE_URL = "http://models.local/"`。内网开发期最简单。

---

## manifest.json 格式

```json
{
  "version": 1,
  "assets": [
    { "path": "voice/sherpa-onnx-streaming-paraformer-bilingual-zh-en/encoder.int8.onnx", "size": 165462184, "sha256": "81a7..." },
    { "path": "voice/sherpa-onnx-streaming-paraformer-bilingual-zh-en/decoder.int8.onnx", "size": 71664561,  "sha256": "f3cc..." },
    { "path": "voice/sherpa-onnx-streaming-paraformer-bilingual-zh-en/tokens.txt",        "size": 75756,     "sha256": "59ab..." },
    { "path": "voice/sherpa-onnx-3dspeaker-campplus-zh-cn-16k-common/speaker.onnx",       "size": 28281138,  "sha256": "f682..." }
  ]
}
```

- `path`：相对 CDN 根的路径，与 app 内 `filesDir/xbot_models/` 镜像布局一致。
- `size`：字节数；下载后强制校验。
- `sha256`：可选（提供则校验）。165MB 算 sha 较慢，默认脚本会算并写入，app 可只靠 size 判定（保留 sha 字段不影响）。

## 开发期手动校验

app 设置页 → **模型资源** → 可看到「已就绪 / 未下载 / 校验未通过」状态，并可在「重新下载」清除就绪标记回到下载页（已下文件保留，走断点续传）。也可在设备上清空 app 数据触发首次下载。
