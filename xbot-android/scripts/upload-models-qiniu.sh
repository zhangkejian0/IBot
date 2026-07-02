#!/usr/bin/env bash
# ============================================================================
# 上传可下载模型资源到七牛云 Kodo 对象存储，并生成/上传 manifest.json
#
# 用法：
#   BUCKET=xbot-models ./scripts/upload-models-qiniu.sh
#
# 前置：
#   1. 下载 qshell（https://github.com/qiniu/qshell/releases），放到 PATH，或当前目录
#   2. qshell account <AccessKey> <SecretKey>      # 个人中心 → 密钥管理
#   3. 在七牛控制台已创建对象存储空间（bucket），见 docs/MODEL_HOSTING.md
#   4. 建议为 bucket 绑定自有域名 + HTTPS（测试域名仅 HTTP 且 30 天过期）
#
# 该脚本：
#   - 遍历 models-upload/ 下所有文件，按相对路径作为 key 上传到 bucket
#     （七牛 key 保留斜杠，故镜像 assets 目录结构）
#   - 大文件（>10MB，如 encoder.int8.onnx 165MB）用 rput 分片上传；小文件用 fput
#   - 计算每个文件的 size + sha256，生成 manifest.json
#   - 上传 manifest.json 到 bucket 根
# 之后把公开访问域名填入 app 的 ResourceManager.BASE_URL（见文档）。
# ============================================================================
set -euo pipefail

BUCKET="${BUCKET:-xbot-models}"
QSHELL="${QSHELL:-qshell}"          # qshell 可执行路径/命令名
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/models-upload"

if [ ! -d "$SRC" ]; then
  echo "❌ 找不到目录: $SRC"
  echo "   把待上传的模型文件放进 models-upload/（保持 assets 镜像结构）。"
  exit 1
fi

if ! command -v "$QSHELL" >/dev/null 2>&1; then
  echo "❌ 未找到 qshell。请从 https://github.com/qiniu/qshell/releases 下载，"
  echo "   放到 PATH，或设环境变量 QSHELL 指向其路径。"
  echo "   首次使用需先: $QSHELL account <AccessKey> <SecretKey>"
  exit 1
fi

# 生成 manifest.json（version 用环境变量 VERSION，默认 1）。
VERSION="${VERSION:-1}"
MANIFEST="$SRC/manifest.json"

echo "📦 正在生成 manifest.json ..."
# 用 python 生成 JSON（跨平台、可算 sha256）。
python - "$SRC" "$VERSION" "$MANIFEST" <<'PY'
import hashlib, json, os, sys
src, version, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
assets = []
for dirpath, _, files in os.walk(src):
    for fn in files:
        if fn == "manifest.json":
            continue
        full = os.path.join(dirpath, fn)
        rel = os.path.relpath(full, src).replace("\\", "/")
        size = os.path.getsize(full)
        h = hashlib.sha256()
        with open(full, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        assets.append({"path": rel, "size": size, "sha256": h.hexdigest()})
        print(f"   {rel}  ({size} bytes)")
manifest = {"version": version, "assets": assets}
with open(out, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
print(f"✅ manifest 写入: {out}  ({len(assets)} 项, v{version})")
PY

echo ""
echo "☁️  上传到七牛 bucket: $BUCKET"
# 逐文件上传：>10MB 用 rput（分片断点续传），否则 fput。
# qshell fput/rput 参数：<Bucket> <Key> <LocalFile> [--overwrite]
BIG_THRESHOLD=$((10 * 1024 * 1024))   # 10MB

cd "$SRC"
find . -type f ! -name manifest.json | while read -r f; do
  key="${f#./}"                       # 相对路径作为 key，保留斜杠镜像目录结构
  size=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f")
  if [ "$size" -gt "$BIG_THRESHOLD" ]; then
    echo "   ↑ (rput) $key"
    $QSHELL rput "$BUCKET" "$key" "$f" --overwrite >/dev/null
  else
    echo "   ↑ (fput) $key"
    $QSHELL fput "$BUCKET" "$key" "$f" --overwrite >/dev/null
  fi
done

echo "   ↑ manifest.json"
$QSHELL fput "$BUCKET" "manifest.json" "$MANIFEST" --overwrite >/dev/null

echo ""
echo "🎉 上传完成。"
echo "   现在把公开访问域名填入 app 的 ResourceManager.BASE_URL："
echo "     https://<你的七牛绑定域>/"
echo "   （详见 docs/MODEL_HOSTING.md）"
