# 运行在容器里：后台服务 + 前端静态资源 + 本地媒体工具
#
# 三条原则：
#   1. 前端在构建阶段编译成静态文件，运行镜像里不带 node；
#   2. ffmpeg / tesseract 走系统包，whisper.cpp 从源码编（Ubuntu 没有现成包）；
#   3. 模型与数据都在卷里，镜像本身可以随手删掉重建。

# ---------- 1. 前端构建 ----------
FROM node:22-bookworm-slim AS client
WORKDIR /build/client
COPY client/package.json client/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY client/ ./
RUN npm run build

# ---------- 2. 后端构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS server
WORKDIR /build
COPY server/pom.xml ./
# 先只拉依赖，源码改动时这一层仍然能命中缓存
RUN mvn -B -q dependency:go-offline
COPY server/src ./src
RUN mvn -B -q -DskipTests package

# ---------- 3. whisper.cpp 编译 ----------
# 单独一阶段，是为了让运行镜像能复用 cmake 的产物而不带整套工具链
FROM ubuntu:24.04 AS whisper
ARG WHISPER_REF=v1.9.5
RUN apt-get update \
 && apt-get install -y --no-install-recommends git cmake build-essential ca-certificates \
 && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch ${WHISPER_REF} https://github.com/ggerganov/whisper.cpp /src
WORKDIR /src
# 容器里没有 GPU，关掉 Metal / CUDA，只留 CPU 后端。
# 用静态库（BUILD_SHARED_LIBS=OFF）而不是拷一堆 .so：新版 whisper.cpp 的构建目录
# 布局随版本变过，硬拷 *.so* 很容易在某次升级后突然找不到文件。
# 注意这里每个步骤都不加 "|| true"——构建失败就该让整次 build 失败，
# 而不是带着一个少了 whisper 的运行镜像继续往下走。
# whisper-cli 属于 examples 目标（关掉 EXAMPLES 就没有这个可执行文件），
# 所以这里必须保持 EXAMPLES=ON，只编译 whisper-cli 这一个目标即可。
RUN cmake -B build -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
      -DWHISPER_BUILD_SERVER=OFF \
      -DGGML_METAL=OFF -DGGML_CUDA=OFF -DGGML_VULKAN=OFF \
 && cmake --build build --config Release -j"$(nproc)" --target whisper-cli
RUN test -x build/bin/whisper-cli && mkdir -p /out/bin && cp build/bin/whisper-cli /out/bin/

# ---------- 4. 运行镜像 ----------
FROM eclipse-temurin:21-jre-noble

# ffmpeg 提供抽音与抽帧；tesseract 提供画面文字识别（含中英文语言包）；
# fonts-noto-cjk 让 drawtext 之类的中文渲染不会变成方块
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ffmpeg tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng \
      fonts-noto-cjk curl ca-certificates tini \
 && rm -rf /var/lib/apt/lists/*

# whisper.cpp 静态链接了 ggml，运行时只依赖 glibc / libstdc++ / libgomp，JRE 镜像里都有
COPY --from=whisper /out/bin/whisper-cli /opt/whisper/whisper-cli
# 启动时自检一次：编出来的二进制跑不起来时，问题应该在这行暴露，而不是等第一次转写
RUN /opt/whisper/whisper-cli --help > /dev/null 2>&1 || (echo "whisper-cli 无法执行" && exit 1)

COPY --from=server /build/target/video-studio-easy-server-*.jar /app/app.jar
# 前端产物放在 jar 同级，WebConfig 会把它当作静态资源目录
COPY --from=client /build/client/dist /app/client/dist

# 模型不在镜像里：whisper 模型约 500MB，且换模型不该触发重新构建。
# 默认指向卷，首次启动由启动脚本按需下载。
ENV VSE_MODEL_DIR=/data/models \
    WHISPER_COMMAND=/opt/whisper/whisper-cli \
    WHISPER_MODEL=/data/models/ggml-small.bin \
    WHISPER_LANGUAGE=zh \
    OCR_COMMAND=tesseract \
    OCR_LANGUAGES=chi_sim+eng \
    STORAGE_ROOT=/data/media \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=9090 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"

RUN mkdir -p /data/models /data/media && chmod 777 /data
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

VOLUME ["/data"]
EXPOSE 9090

# tini 负责转发信号：没有它，docker stop 会让 JVM 变成僵尸进程，等满 10 秒才被 SIGKILL
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
