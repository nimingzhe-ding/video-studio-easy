# video-studio-easy

把**长视频 / 长音频**变成**可检索、可追溯、可继续追问**的结构化文档。

上传一个几十分钟的课程录像、会议录音或访谈，系统自动完成语音识别、关键帧画面文字识别、
时间轴对齐与分块，再用「规划 → 执行 → 校验」的受控 Agent 生成带时间戳证据的 Markdown /
Word 报告，并支持围绕报告继续追问。

> 第一版 demo 的目标是**跑得起来、看得见证据链**，而不是堆中间件。
> 一个 Spring Boot 进程 + 一个 Vue 页面 + 本地 ffmpeg/whisper/OCR 即可完整体验；
> 没有配大模型 key 时自动降级为确定性离线模板，全流程依然可跑通。

---

## 目录

- [核心特性](#核心特性)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [环境变量](#环境变量)
- [使用流程](#使用流程)
- [工作原理](#工作原理)
- [REST API](#rest-api)
- [项目结构](#项目结构)
- [设计取舍](#设计取舍)
- [已知限制](#已知限制)
- [文档](#文档)

---

## 核心特性

| 能力 | 说明 |
| --- | --- |
| 长音频理解 | ffmpeg 抽音轨 → 16k 单声道 → 按 60 秒切片 → 云端 ASR 或本地 whisper.cpp，逐片并行转写后按毫秒时间戳拼回 |
| 视频画面理解 | 场景切换抽帧（首帧 + 场景分 + 最长间隔兜底）→ 感知哈希 + OCR 文本两级去重 → 画面文字识别 |
| 时间轴对齐 | 语音与画面文字统一落到同一毫秒时间轴，生成 `[mm:ss]` 引用锚点 |
| 上下文分块 | 默认 5 分钟一块，合并转写与 OCR，产出摘要、关键词、画面关键词，可选 embedding |
| 混合检索 | `0.60 × 语义相似度 + 0.25 × 关键词命中 + 0.15 × 画面关键词命中`，向量不可用时自动退化为纯词项检索 |
| 受控 Agent | Planner 规划提纲 → Executor 逐条写结论 → Critic 校验，结论必须绑定可机器校验的时间戳证据 |
| 证据校验 | 确定性校验器比对「声称的时间戳 / 引用的原文 / 结论与证据是否一致」，对不上就退回重写 |
| 分模式报告 | 通用理解 / 学习笔记 / 内容评审 / 二次创作四种模式，各自有不同的提纲与关注点 |
| 文档导出 | Markdown 直接展示，Word（.docx）一键下载 |
| 追问 | 基于报告 + 检索到的证据继续对话，回答同样带时间戳 |
| 实时对话 | 浏览器麦克风/摄像头直通云端 realtime 模型（WebSocket 中转，避免把 key 暴露给前端） |
| 无 key 可跑 | 未配置大模型时自动降级为确定性模板，转写、OCR、检索、报告、导出全部照常工作 |

---

## 技术栈

- **后端**：Java 21 + Spring Boot 3.5.16（Web / JDBC / WebSocket / Validation），Apache POI 生成 Word
- **存储**：H2 文件库（默认，零依赖）；DDL 兼容 MySQL，改 datasource 即可切换
- **前端**：Vue 3 + Vite 5，`marked` 渲染 Markdown
- **本地媒体工具**：ffmpeg / ffprobe（抽音轨、抽帧、切片）、whisper.cpp（本地语音识别）、
  Tesseract 或 macOS Vision CLI（画面文字识别）
- **模型网关**：任意 OpenAI 兼容接口（默认 DashScope 兼容模式：`qwen-plus` / `qwen-vl-max` /
  `text-embedding-v3` / `qwen-audio-asr` / `qwen3-omni-flash-realtime`）

---

## 快速开始

### 1. 准备本地工具

任选其一，只要保证命令可执行并在 `PATH` 中（或用环境变量指定绝对路径）：

| 工具 | 用途 | 必需 |
| --- | --- | --- |
| `ffmpeg` + `ffprobe` | 抽音轨、切片、抽关键帧 | 是 |
| `whisper-cli`（whisper.cpp） | 本地语音识别（无云端 ASR 时） | 二选一 |
| 云端 ASR（OpenAI 兼容 `/audio/transcriptions`） | 语音识别 | 二选一 |
| `tesseract` 或自建 Vision CLI | 画面文字识别 | 建议 |

macOS 上可以用仓库自带的脚本编译一个基于 Vision 框架的 OCR 命令行（无需装 tesseract）：

```bash
./tools/vision-ocr/build.sh
# 产物：.tools/vocr，用法：.tools/vocr <image> [lang]
```

### 2. 启动后端

```bash
cd server
mvn spring-boot:run
# 默认监听 http://127.0.0.1:9090
```

### 3. 启动前端（开发模式）

```bash
cd client
npm install
npm run dev
# 打开 http://127.0.0.1:5173，/api 与 /ws 已代理到 9090
```

生产/演示模式可以只跑后端：`npm run build` 后，`client/dist` 会被自动托管在 `9090`，
浏览器直接访问 `http://127.0.0.1:9090` 即可。

### 4. 配置大模型（可选）

```bash
export AI_API_KEY=sk-xxx
export AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export LLM_MODEL=qwen-plus
```

不配置也能跑：报告走离线模板，页面上会明确提示「当前未配置大模型」。

---

## 环境变量

配置文件 `server/src/main/resources/application.properties` 里所有项都可用环境变量覆盖，
常用的如下。

### 存储与上传

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `9090` | 服务端口 |
| `SERVER_ADDRESS` | `127.0.0.1` | 监听地址 |
| `STORAGE_ROOT` | `./data` | 媒体、帧、音频、报告的落盘根目录 |
| `MAX_UPLOAD_BYTES` | `2147483648` | 单文件上传上限（2 GB） |
| `DB_URL` | `jdbc:h2:file:./data/db/video-studio;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1` | 数据库连接串 |

### 本地媒体工具

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FFMPEG_PATH` | `ffmpeg` | ffmpeg 可执行文件 |
| `FFPROBE_PATH` | `ffprobe` | ffprobe 可执行文件 |
| `OCR_COMMAND` | `tesseract` | OCR 命令；指向含 `vocr` 的路径时自动按 Vision 方式调用 |
| `OCR_LANGUAGES` | `chi_sim+eng` | tesseract 语言包 |
| `FRAME_SCENE_THRESHOLD` | `0.006` | 场景切换阈值（静态课件真实场景分常低于 0.01，所以取得很低） |
| `FRAME_FALLBACK_INTERVAL_SECONDS` | `20` | 最长抽帧间隔兜底 |
| `FRAME_MAX_COUNT` | `400` | 单视频抽帧上限 |
| `FRAME_WIDTH` | `960` | 抽帧缩放宽度 |
| `ASR_SEGMENT_SECONDS` | `60` | 音频切片长度 |
| `CONTEXT_SEGMENT_SECONDS` | `60` | 时间轴最小证据段长度 |

### 语音识别

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ASR_URL` | DashScope compatible-mode `/audio/transcriptions` | 云端 ASR 端点 |
| `ASR_MODEL` | `qwen-audio-asr` | 云端 ASR 模型 |
| `ASR_TIMEOUT_SECONDS` | `300` | 云端 ASR 超时 |
| `WHISPER_COMMAND` | 空 | 本地 whisper 命令（空则禁用本地回退） |
| `WHISPER_MODEL` | 空 | ggml 模型路径，如 `ggml-small.bin` |
| `WHISPER_LANGUAGE` | `auto` | 语言，中文建议 `zh` |
| `WHISPER_THREADS` | `4` | 线程数 |
| `WHISPER_TIMEOUT_SECONDS` | `1800` | 单次识别超时 |

> 云端优先、本地回退：云端不可用或返回空文本时自动切到 whisper，事件流里会写明用的是哪个引擎。

### 模型网关

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AI_API_KEY` | 空 | 留空则进入离线模板模式 |
| `AI_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容 base url |
| `LLM_MODEL` | `qwen-plus` | 主模型 |
| `VISION_MODEL` | `qwen-vl-max` | 视觉模型（可选，用于帧描述） |
| `EMBEDDING_MODEL` | `text-embedding-v3` | 向量模型 |
| `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` | 同 `AI_*` | 向量服务可单独配置 |
| `LLM_TIMEOUT_SECONDS` | `180` | 单次调用超时 |
| `LLM_MAX_RETRIES` | `3` | 重试次数（仅 429 / 5xx） |

### 实时对话

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `REALTIME_ENABLED` | `true` | 是否启用 `/ws/realtime` 中转 |
| `REALTIME_URL` | 空 | 上游 WebSocket 地址 |
| `REALTIME_API_KEY` | 空 | 上游 key（只在服务端使用） |
| `REALTIME_MODEL` | `qwen3-omni-flash-realtime` | 实时模型 |

### Agent

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_MAX_ROUNDS` | `2` | Critic 未通过时的最大重写轮数 |
| `AGENT_MAX_DURATION_MS` | `180000` | 单任务分析时长上限 |
| `AGENT_MAX_CONTEXT_CHARS` | `24000` | 单次提示词的证据上下文预算 |
| `CHUNK_SECONDS` | `300` | 上下文分块长度 |
| `RETRIEVAL_TOP_K` | `3` | 检索返回的分块数 |
| `RETRIEVAL_MAX_SEGMENTS` | `8` | 单个分块最多携带的证据段数 |

---

## 使用流程

1. **上传**：把视频/音频拖到左侧栏，等待探测完成（显示时长、分辨率、编码）
2. **设定目标**：填写「你想从这段材料里得到什么」，例如
   `把这段课程整理成可以复习的笔记，重点保留公式推导和例题`
3. **选择模式**：通用理解 / 学习笔记 / 内容评审 / 二次创作
4. **开始分析**：右侧「执行日志」实时显示阶段与进度（SSE）
5. **看报告**：报告页渲染 Markdown，每条结论都带 `[mm:ss]`，可复制或下载 Word
6. **追问**：切到「追问」，例如「第三节提到的那个阈值是多少？」，回答同样带时间戳
7. **核对证据**：「转写与画面」页可按「仅语音 / 仅画面文字」过滤，逐条回看

---

## 工作原理

```
上传 ─ ffprobe ─┬─ 抽音轨(16k mono) ─ 60s 切片 ─ ASR(云端→whisper) ─┐
                └─ 场景抽帧 ─ 指纹去重 ─ OCR ─────────────────────┤
                                                                  ├─ 时间轴对齐
                                                                  ↓
                                          5min 上下文分块（摘要/关键词/embedding）
                                                                  ↓
                                            混合检索 Top-K 分块 + 证据段
                                                                  ↓
                            Planner(提纲) → Executor(结论+证据) → Critic(确定性校验)
                                                                  ↓
                                     Markdown / Word 报告 + 追问 + 实时对话
```

### 关键阶段的实现要点

**抽帧**：`select='eq(n,0) + gte(t-prev_selected_t,gap) * (gt(scene,thr) + gte(t-prev_selected_t,interval))'`
——首帧必取；场景切换点必须距上一帧至少 `gap` 秒（去抖）；超过 `interval` 秒强制兜底。
时间戳取自 `showinfo` 打印的 `pts_time`，**不是按序号推算**，保证镜头疏密不均时依然对齐。

**去重**：纯感知哈希在「深色背景 + 一行标题」的课件上完全失效（8×8 网格下不同标题哈希一致）。
因此改成两级：先用 dHash + 灰度签名平均差做廉价的快筛，再比对 OCR 文本归一化结果，
相同则丢弃。宁可多 OCR 一张，也不要漏掉一次翻页。

**证据校验**：Critic 不只看模型自己说的话。`EvidenceValidator` 会独立校验：
结论必须能在原始片段里找到对应文本子串、声称的时间戳必须真的落在某条证据的时间范围内、
引用的来源必须是 ASR 或 OCR。任何一条不满足，就把失败的条目并回 feedback 进入下一轮。

**离线降级**：没有 API key 时不会报错，而是走确定性模板：结论取自转写、证据取自时间轴、
各模式的小节由提纲模板填充。这样在 CI 或没有额度的环境里也能验证整条链路。

---

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/media` | 上传媒体（multipart，字段 `file`） |
| `GET` | `/api/media` | 媒体列表（`limit` 1..200） |
| `GET` | `/api/media/{id}` | 媒体详情 |
| `GET` | `/api/media/{id}/jobs` | 该媒体的任务 |
| `DELETE` | `/api/media/{id}` | 删除媒体 |
| `POST` | `/api/jobs` | 提交分析任务 `{assetId, goal, mode, title}` |
| `GET` | `/api/jobs` | 任务列表 |
| `GET` | `/api/jobs/{id}` | 任务详情（含报告与事件） |
| `GET` | `/api/jobs/{id}/events?after=` | 增量事件 |
| `GET` | `/api/jobs/{id}/stream?after=` | **SSE** 实时事件流（`hello` / `event` / `done`） |
| `GET` | `/api/jobs/{id}/transcript?from=&size=` | 转写分页 |
| `GET` | `/api/jobs/{id}/evidence` | 画面文字证据 |
| `GET` | `/api/jobs/{id}/report` | 报告 JSON |
| `GET` | `/api/jobs/{id}/report/markdown` | 报告 Markdown |
| `GET` | `/api/jobs/{id}/report/docx` | 报告 Word 下载 |
| `POST` | `/api/jobs/{id}/cancel` | 取消任务 |
| `POST` | `/api/jobs/{id}/reanalyze` | 复用证据重新分析 `{goal, mode}` |
| `POST` | `/api/jobs/{id}/chat` | 追问 `{question}` |
| `GET` / `DELETE` | `/api/jobs/{id}/chat` | 追问历史 / 清空 |
| `GET` | `/api/jobs/runtime` | 运行环境（是否配置了大模型 / 实时模型） |
| `WS` | `/ws/realtime` | 浏览器音视频 ⇄ 云端 realtime 模型中转 |

统一响应体：

```json
{ "ok": true, "data": {}, "message": null }
```

失败时 `ok=false`，`message` 为中文可读原因，HTTP 状态码对应 400/404/409/413/500。

---

## 项目结构

```
video-studio-easy/
├── server/                       # Spring Boot 后端
│   └── src/main/
│       ├── java/com/videostudio/
│       │   ├── common/           # ApiResponse / Texts / Times / Json / Http / Sql
│       │   ├── config/           # 配置属性、CORS、静态资源、WebSocket
│       │   ├── domain/           # 领域模型（媒体、任务、证据、分块、报告）
│       │   ├── repository/       # JdbcTemplate 数据访问
│       │   ├── media/            # ffmpeg/ffprobe 封装、抽帧、去重、存储
│       │   ├── asr/              # 云端 ASR + whisper 本地回退 + 分片合并
│       │   ├── ocr/              # OCR 命令封装 + 关键帧证据编排
│       │   ├── context/          # 时间轴对齐、上下文分块、摘要与 embedding
│       │   ├── retrieval/        # 混合检索
│       │   ├── llm/              # OpenAI 兼容网关（chat / vision / embed）
│       │   ├── agent/            # Planner / Executor / Critic / 证据校验 / 报告合成
│       │   ├── report/           # Markdown → Word
│       │   ├── service/          # 任务调度、分析流水线、追问
│       │   └── web/              # REST / SSE / WebSocket
│       └── resources/
│           ├── application.properties
│           ├── db/schema.sql
│           └── prompts/          # 系统策略与各角色提示词（可独立调整）
├── client/                       # Vue 3 + Vite 前端
├── tools/vision-ocr/             # macOS Vision OCR 命令行（可选）
└── docs/                         # 设计文档
```

---

## 设计取舍

第一版刻意**没有**引入参考项目里的 RocketMQ / Redisson / MySQL + Redis 双 checkpoint / Qdrant / MinIO：

| 组件 | 参考项目做法 | 本项目的做法 | 理由 |
| --- | --- | --- | --- |
| 消息队列 | RocketMQ 分发分析阶段 | 固定线程池 + 数据库状态机 | 单机 demo 不需要跨进程重试语义，状态落库已能做崩溃恢复 |
| 向量库 | Qdrant | 分块 embedding 存 JSON 列，内存内余弦 | 单视频分块量级在几百，暴力检索毫秒级 |
| 对象存储 | MinIO | 本地目录 + 任务级隔离 | 少一个必须部署的依赖 |
| 缓存 | Redis | 无（H2 + 内存） | 避免引入第二个数据源 |

保留的是真正决定产品体验的部分：**时间戳证据链**。报告里的每一条结论都必须指回原片，
Critic 会用确定性规则去核对，这是「总结工具」和「可信分析工具」的分界线。

---

## 已知限制

- 本地 whisper 的标点与专有名词识别质量取决于模型大小（`base` 明显弱于 `small`/`medium`），
  中文同音错较多时建议换更大模型或改用云端 ASR
- 纯人像、无文字画面、无语音的片段不会产生证据；当语音与画面文字**同时为空**时任务会明确失败并提示原因
- 画面理解目前只到「识别屏幕上的文字」，还没有接入视觉模型做场景/人物描述
- 单机版本没有鉴权与多租户，不要直接暴露到公网
- H2 适合演示与单机使用；数据量大或需要并发写时请切 MySQL

---

## 文档

- [架构说明](docs/architecture.md)
- [API 参考](docs/api.md)
- [提示词设计](docs/prompts.md)

---

## License

MIT

---

## Ubuntu / 容器内自测

在无显示器的服务器上可以只跑后端 + 命令行验证，不需要浏览器：

```bash
# 1. 上传
curl -s -F "file=@demo.mp4" http://127.0.0.1:9090/api/media

# 2. 提交分析（返回任务 id）
curl -s -X POST http://127.0.0.1:9090/api/jobs \
  -H 'Content-Type: application/json' \
  -d '{"assetId":1,"mode":"LEARNING","goal":"整理成可复习的笔记"}'

# 3. 看进度（SSE）
curl -N http://127.0.0.1:9090/api/jobs/1/stream

# 4. 取报告
curl -s http://127.0.0.1:9090/api/jobs/1/report/markdown
curl -o report.docx http://127.0.0.1:9090/api/jobs/1/report/docx
```
