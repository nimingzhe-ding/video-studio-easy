#!/bin/sh
# 启动入口：先确认 whisper 模型在位，再交给 JVM
set -e

MODEL="${WHISPER_MODEL:-/data/models/ggml-small.bin}"
MODEL_DIR="$(dirname "$MODEL")"
mkdir -p "$MODEL_DIR"

# 模型不进镜像（约 500MB，且换模型不该重新构建）。
# 这里按需下载一次；下载失败只告警，让服务以「无本地 ASR」的状态继续启动，
# 因为云端 ASR 或纯画面分析仍然可用，不该因为一个可选模型整个起不来。
if [ ! -s "$MODEL" ]; then
  URL="${WHISPER_MODEL_URL:-https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$(basename "$MODEL")}"
  echo "[entrypoint] 本地模型不存在，尝试下载 $URL"
  if curl -fL --retry 3 --retry-delay 2 -o "$MODEL.part" "$URL"; then
    mv "$MODEL.part" "$MODEL"
    echo "[entrypoint] 模型下载完成：$MODEL"
  else
    rm -f "$MODEL.part"
    echo "[entrypoint] 警告：模型下载失败，本地语音识别将不可用（云端 ASR 仍可用）"
  fi
fi

exec java $JAVA_OPTS -jar /app/app.jar "$@"
