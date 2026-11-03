#!/usr/bin/env bash
# 在 macOS 上构建本地 OCR 小工具（Vision 框架，无需 tesseract）。
# 产物：<repo>/.tools/vocr
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/tools/vision-ocr/vocr.m"
OUT="$ROOT/.tools/vocr"
# 说明：CommandLineTools 自带的最新 SDK（如 MacOSX27.0）与当前 clang 链接器存在 TBD 架构不兼容，
# 因此优先挑选带版本号且可正常链接的 SDK。
SDK=""
for candidate in /Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk \
                 /Library/Developer/CommandLineTools/SDKs/MacOSX26.sdk \
                 /Library/Developer/CommandLineTools/SDKs/MacOSX15.4.sdk; do
  if [ -d "$candidate" ]; then SDK="$candidate"; break; fi
done
if [ -z "$SDK" ]; then
  SDK="$(xcrun --show-sdk-path 2>/dev/null || echo /Library/Developer/CommandLineTools/SDKs/MacOSX.sdk)"
fi

mkdir -p "$ROOT/.tools"
echo "==> 使用 SDK: $SDK"
clang -fobjc-arc -O2 -isysroot "$SDK" -target arm64-apple-macos11.0 \
      -framework Foundation -framework Vision -framework CoreGraphics -framework ImageIO \
      -o "$OUT" "$SRC"

echo "==> 构建完成: $OUT"
"$OUT" || true
