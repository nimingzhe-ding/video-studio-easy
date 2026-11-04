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
| 长音频理解 | ffmpeg 抽音轨 → 16k 单声道 → 一次 `-f segment` 切成 60 秒片段 → 云端 ASR 或本地 whisper.cpp 多路并发转写 → 按毫秒时间戳拼回（10 分钟音频 58s → 31s） |
| 说话人分离 | 纯本地声学聚类：按转写片段抽 25ms 帧估基频（自相关 + 倍频保护），一维层次聚类把片段归到不同讲者，回填「讲者 A / 讲者 B」，报告、字幕、上下文都带说话人 |
| 视频画面理解 | 场景切换抽帧（首帧 + 场景分 + 最长间隔兜底）→ 感知哈希 + 文本两级去重 → 画面文字并发识别 |
| 定向视觉描述 | 只对「OCR 一个字都没读到」的帧调用视觉模型写一句描述，默认关闭、按配额限量，让纯画面也能进证据链 |
| 时间轴对齐 | 语音与画面文字统一落到同一毫秒时间轴，生成 `[mm:ss]` 引用锚点 |
| 上下文分块 | 默认 5 分钟一块，合并转写与 OCR，产出摘要、关键词、画面关键词，可选 embedding |
| 混合检索 | `0.60 × 语义相似度 + 0.25 × 关键词命中 + 0.15 × 画面关键词命中`，向量不可用时自动退化为纯词项检索 |
| 受控 Agent | Planner 规划提纲 → Executor 逐条写结论 → Critic 校验，结论必须绑定可机器校验的时间戳证据 |
| 有界上下文 | 按条目选入而非整段截断：超预算的条目跳过、继续尝试后面更短的条目，被省略的条目显式说明 |
| 证据校验 | 确定性校验器比对「声称的时间戳 / 引用的原文 / 结论与证据是否一致」，对不上就退回重写 |
| 分模式报告 | 通用理解 / 学习笔记 / 内容评审 / 二次创作四种模式，各自有不同的提纲与关注点 |
| 自动模式判定 | 目标留空或选「自动判断」时，后端用模型（无 key 时用关键词规则）把自然语言目标归到四种模式之一，并记录判定来源与理由 |
| 证据复用 | 同一素材的第二个任务直接复用已提取的转写、画面文字与分块，只重新检索与分析，省掉最贵的两步 |
| 多轮补证 | 校验不通过时按 Critic 点名的时间戳回捞片段、按缺口重新检索，扩的是证据而不是重写措辞 |
| 文档导出 | Markdown 直接展示；Word（.docx）一键下载，报告里按时间顺序插入引用到的关键帧；转写可导出 SRT / VTT / TXT 字幕 |
| 追问 | 基于报告 + 检索到的证据继续对话，回答同样带时间戳 |
| 实时对话 | 浏览器麦克风/摄像头直通云端 realtime 模型（WebSocket 中转，避免把 key 暴露给前端） |
| 无 key 可跑 | 未配置大模型时自动降级为确定性模板，转写、OCR、检索、报告、导出全部照常工作 |
| 大文件分片上传 | 超过 32 MB 自动切 8 MB 分片逐片 PUT，服务端只记「哪些片已收到」，断网或刷新后可续传；单片失败自动重试 3 次 |
| 可点回的界面 | 原片播放器 + `[mm:ss]` 点击跳转、报告目录、转写搜索高亮、关键帧灯箱、明暗主题、移动端抽屉 |

---

## 技术栈

- **后端**：Java 21 + Spring Boot 3.5.16（Web / JDBC / WebSocket / Validation），Apache POI 生成 Word
- **存储**：H2 文件库（默认，零依赖）；DDL 兼容 MySQL，改 datasource 即可切换
- **前端**：Vue 3 + Vite 5 单页应用，`marked` 渲染 Markdown，无 UI 框架，样式为手写设计系统（明/暗双主题 + 响应式）
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

界面上的常用操作：

- 左侧拖入文件即可上传，上传后点「分析『文件名』」用当前模式与目标开跑
- 主区顶部的播放器可以直接拖进度条；报告、追问、转写里的 `[mm:ss]` 点击即跳到对应位置
- 报告右侧有自动生成的目录；「转写与画面」支持关键词搜索高亮、只看语音/只看画面、关键帧灯箱
- 右上角可切换明暗主题（选择记在 `localStorage`），窄屏下左侧栏收进抽屉
- 任务卡片悬停可取消或删除；删除任务只清理它自己的证据与文件，同素材的其他任务不受影响

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
| `UPLOAD_CHUNK_SIZE` | `8388608` | 分片大小（8 MB） |
| `UPLOAD_MAX_CHUNKS` | `20000` | 单个文件最多分片数 |
| `UPLOAD_RETENTION_HOURS` | `24` | 未完成分片会话的保留时长，超时由定时任务清理 |
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
| `ASR_CONCURRENCY` | `3` | 语音转写并发路数（本地 whisper 每路会加载一份模型，内存紧张时调小） |
| `OCR_CONCURRENCY` | `3` | 画面文字识别并发路数 |
| `CONTEXT_SEGMENT_SECONDS` | `60` | 时间轴最小证据段长度 |
| `SPEAKER_SEPARATION_ENABLED` | `true` | 是否做说话人分离（纯本地、无额外费用；单人音频会自动跳过标注） |
| `SPEAKER_SPLIT_SEMITONES` | `4.0` | 判定「两个讲者」的最小音高差（半音）。4 半音≈大三度；音色接近的场合调小到 3.0 更容易分开 |

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
| `VISION_MODEL` | `qwen-vl-max` | 视觉模型（用于关键帧描述） |
| `VISION_CAPTION_ENABLED` | `false` | 是否对「OCR 读不到文字」的帧调用视觉模型写描述；开启后会按帧计费 |
| `VISION_CAPTION_MAX_FRAMES` | `24` | 单个任务最多描述多少帧（0 表示不限制之外的全关） |
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
| `REPORT_MAX_FIGURES` | `8` | Word 报告里最多插入几张关键帧（`0` 关闭插图） |

---

## 使用流程

1. **上传**：把视频/音频拖到左侧栏，等待探测完成（显示时长、分辨率、编码）
2. **设定目标**：填写「你想从这段材料里得到什么」，例如
   `把这段课程整理成可以复习的笔记，重点保留公式推导和例题`
3. **选择模式**：默认「自动判断」（后端按目标归类并显示判定理由），也可手动指定
   通用理解 / 学习笔记 / 内容评审 / 二次创作
4. **开始分析**：右侧「执行日志」实时显示阶段与进度（SSE）
5. **看报告**：报告页渲染 Markdown，每条结论都带 `[mm:ss]`，点击即可跳到原片对应位置，可复制或下载 Word（Word 里会在「视频证据」之后插入对应的关键帧截图）
   - 同一素材再跑一次会自动复用已提取的转写与画面证据，只重新分析（几秒出结果）
6. **追问**：切到「追问」，例如「第三节提到的那个阈值是多少？」，回答同样带时间戳
7. **核对证据**：「转写与画面」页可按「仅语音 / 仅画面文字」过滤，逐条回看

---

## 工作原理

```
上传 ─ ffprobe ─┬─ 抽音轨(16k mono) ─ 60s 切片 ─ ASR(云端→whisper) ─┐  ← 同素材已有证据时整段复用
                └─ 场景抽帧 ─ 指纹去重 ─ OCR ─────────────────────┤
                                                                  ├─ 时间轴对齐
                                                                  ↓
                                          5min 上下文分块（摘要/关键词/embedding）
                                                                  ↓
                                            混合检索 Top-K 分块 + 证据段
                                                                  ↓
                      Planner(提纲) → Executor(结论+证据) → Critic(确定性校验)
                                                 ↑ 不过则按缺口回捞片段 + 重检索
                                                                  ↓
                                     Markdown / Word 报告 + 追问 + 实时对话
```

### 关键阶段的实现要点

**抽帧**：`select='eq(n,0) + gte(t-prev_selected_t,gap) * (gt(scene,thr) + gte(t-prev_selected_t,interval))'`
——首帧必取；场景切换点必须距上一帧至少 `gap` 秒（去抖）；超过 `interval` 秒强制兜底。
时间戳取自 `showinfo` 打印的 `pts_time`，**不是按序号推算**，保证镜头疏密不均时依然对齐。

**去重**：纯感知哈希在「深色背景 + 一行标题」的课件上完全失效（8×8 网格下不同标题哈希一致）。
因此改成两级：先用 dHash + 灰度签名平均差做廉价的快筛，再比对文本归一化结果，
相同则丢弃。宁可多 OCR 一张，也不要漏掉一次翻页。描述模式下比对的是「文字优先、描述兜底」，
所以两帧画面不同但描述相同的过渡页也只留第一次出现的时刻。

**说话人分离**：只靠音高的话，一句话内的起伏能到 5 个半音，跨讲者的中位差可能只有 3.75 个半音——
两者重叠，所以不能用「单帧阈值」，而是先用自相关估基频、取每段的中位数，再做**一维层次聚类**
（每次合并中心最近的两组，直到最近距离超过 `SPEAKER_SPLIT_SEMITONES`）。
两个易错点写进了代码注释：全局最大自相关峰常落在**两倍周期**上（175Hz 会被判成 87.5Hz），
所以改成「取最早的、达到最大峰 85% 的局部峰」；按时间顺序贪心聚类会被后续片段拉动质心、
把两个人并成一个，所以改成全局最近优先。
测不到音高的片段（气声、停顿）继承相邻讲者，总时长短于 1.2 秒的组会被并入相邻组——
但只看时长不看片段数，因为访谈里嘉宾只插一句 1.5 秒也是真人。

**定向视觉描述**：默认关闭。开启后只对「OCR 一个字都没读到」的帧调用视觉模型，
因为课件、录屏、会议共享的绝大多数帧文字都能被本地 OCR 拿到，再调模型是纯浪费。
描述作为 `VISION` 来源的证据参与校验，同样要逐字核对——模型编一句描述再拿它当证据是过不了的。
配额由 `VISION_CAPTION_MAX_FRAMES` 控制，长视频不会因为一次分析账单一飞冲天。

**证据校验**：Critic 不只看模型自己说的话。`EvidenceValidator` 会独立校验：
结论必须能在原始片段里找到对应文本子串、声称的时间戳必须真的落在某条证据的时间范围内、
引用的来源必须是 ASR / OCR / VISION。任何一条不满足，就把失败的条目并回 feedback 进入下一轮。

**校验失败后补证据**：第二轮不是简单「把批评喂回去重写」。系统会按 Critic 点名的时间戳
从**该素材的全部**证据里回捞附近片段（容差取 `max(60s, 片段时长)`），并把
「目标 + 反馈 + 缺失要求 + 无依据论断」拼成一次新的检索再取一轮结果。扩的是证据集，不是措辞。

**有界上下文**：长视频整片转写动辄几十万字，所以每轮只喂 `agent.max-context-chars`（默认 24000）以内的
证据。超预算的条目**跳过并继续看后面的条目**，而不是遇到超长就停——否则一条 600 字的转写就能把
后面几十条短证据全挤出上下文。被省略的条目会显式告诉模型「另有 N 条已省略」，让它知道信息有损。

**自动模式判定**：目标留空或选「自动判断」时，`ModeRouter` 先用模型归类，失败则回退关键词规则，
结果与理由（如「目标偏向知识梳理与复习，选学习笔记」）一起落在任务上，界面直接展示。
路由永远不阻断主流程，也永远只产出四种具体模式之一。

**证据复用**：转写与 OCR 只取决于素材内容、与本次目标无关。同一素材的第二个任务会直接复用
此前成功任务留下的转写、画面文字与分块（帧文件复制到本次任务目录，避免源任务被删后 404），
只重新做检索与分析。

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
| `GET` | `/api/media/{id}/info` | 播放器用的轻量信息 |
| `GET` | `/api/media/{id}/stream` | 原始文件流，支持 `Range` 拖动播放 |
| `GET` | `/api/media/{id}/thumbnail?at=` | 封面抽帧（缓存） |
| `GET` | `/api/media/{id}/frame?t=&w=` | 任意时刻抽帧预览（缓存） |
| `GET` | `/api/media/{id}/download` | 下载原始文件 |
| `DELETE` | `/api/media/{id}` | 级联删除媒体与其全部任务、文件 |
| `POST` | `/api/jobs` | 提交分析任务 `{assetId, goal, mode, title}` |
| `GET` | `/api/jobs` | 任务列表 |
| `GET` | `/api/jobs/{id}` | 任务详情（含报告与事件） |
| `GET` | `/api/jobs/{id}/events?after=` | 增量事件 |
| `GET` | `/api/jobs/{id}/stream?after=` | **SSE** 实时事件流（`hello` / `event` / `done`） |
| `GET` | `/api/jobs/{id}/transcript?from=&size=` | 转写分页 |
| `GET` | `/api/jobs/{id}/evidence` | 画面文字证据 |
| `GET` | `/api/jobs/{jobId}/frames/{seq}` | 第 `seq` 条证据的关键帧图片 |
| `GET` | `/api/jobs/{id}/report` | 报告 JSON |
| `GET` | `/api/jobs/{id}/report/markdown` | 报告 Markdown |
| `GET` | `/api/jobs/{id}/report/docx` | 报告 Word 下载（含关键帧插图） |
| `GET` | `/api/jobs/{id}/report/subtitle?format=srt` | 字幕导出（`srt` / `vtt` / `txt`） |
| `POST` | `/api/jobs/{id}/cancel` | 取消任务 |
| `POST` | `/api/jobs/{id}/reanalyze` | 复用证据重新分析 `{goal, mode}` |
| `DELETE` | `/api/jobs/{id}` | 删除任务及其证据与文件 |
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
│       │   ├── service/          # 任务调度、分析流水线、追问、模式路由、证据复用
│       │   └── web/              # REST / SSE / WebSocket
│       └── resources/
│           ├── application.properties
│           ├── db/schema.sql
│           └── prompts/          # 系统策略与各角色提示词（可独立调整）
├── client/                       # Vue 3 + Vite 单页前端
│   └── src/
│       ├── App.vue               # 布局、上传、媒体库、任务列表、播放器、标签页
│       ├── api.js                # REST / XHR 上传 / SSE 封装
│       ├── format.js             # 时间、大小、状态与引擎的中文文案
│       ├── markdown.js           # Markdown 渲染 + [mm:ss] 时间戳胶囊 + 搜索高亮
│       ├── styles.css            # 手工设计系统（明暗主题、响应式）
│       └── components/           # JobCard / ReportView / TranscriptView / ChatPanel / RealtimePanel
├── tools/vision-ocr/             # macOS Vision OCR 命令行（可选）
└── docs/                         # 设计文档
server/src/test/java/             # 路由规则与上下文预算的单元测试
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
- 画面理解默认只到「识别屏幕上的文字」；开启 `VISION_CAPTION_ENABLED` 后才对无文字的帧补一句视觉描述，
  且只描述、不检测物体与动作，也不做跨帧的事件推理
- 自动模式判定在有模型时依赖一次额外的模型调用；无 key 时退化为关键词规则，判不准的目标会落到通用理解
- 证据复用按「素材 + 成功任务」匹配，因此删掉所有成功任务后，下一次分析会重新转写与抽帧
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
curl -o report.docx http://127.0.0.1:9090/api/jobs/1/report/docx   # 报告 + 关键帧插图

# 5. 导出字幕（srt / vtt / txt）
curl -o subtitle.srt "http://127.0.0.1:9090/api/jobs/1/report/subtitle?format=srt"
```
