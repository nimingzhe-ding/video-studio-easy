# 架构说明

> 目标：把长视频/长音频变成**可检索、可追溯、可继续追问**的结构化文档。
> 本文说明第一版 demo 的分层、数据流、状态机与关键实现取舍。

## 1. 总览

```
┌──────────────── 前端（Vue 3 + Vite）────────────────┐
│ 上传 / 目标 / 模式    报告(Markdown/Word)    追问      │
│ 执行日志(SSE)         证据浏览            实时对话(WS) │
└───────────────┬─────────────────────┬───────────────┘
                │ REST / SSE          │ WebSocket
┌───────────────▼─────────────────────▼───────────────┐
│                  web 层（Controller）                │
│  MediaController  JobController  JobEventController  │
│  ChatController   ReportController  RealtimeProxy    │
└───────────────┬─────────────────────────────────────┘
                │
┌───────────────▼──────────── service 层 ─────────────┐
│  JobService（提交/取消/线程池）                       │
│  AnalysisPipeline（阶段编排 + 事件发射）              │
│  FollowUpService（追问）  MediaIngestService（上传）  │
└───┬────────┬────────┬────────┬────────┬─────────────┘
    │        │        │        │        │
 media/    asr/     ocr/   context/  retrieval/  agent/  report/
 ffmpeg    云端+whisper OCR    对齐/分块  混合检索   P-E-C   docx
    │        │        │        │        │        │        │
    └────────┴────────┴────────┼────────┴────────┴────────┘
                               │
                    repository/（JdbcTemplate）
                               │
                    H2 / MySQL（8 张表）
```

设计原则：

1. **单一进程可跑通**：不依赖消息队列、向量库、对象存储，`mvn spring-boot:run` + `npm run build` 即完整可演示。
2. **证据先行**：所有下游（检索、Agent、追问）只消费「带时间戳的证据」，不消费原始文件。
3. **可降级**：云端 ASR 不可用 → 本地 whisper；没有大模型 → 确定性模板；没有向量 → 词项检索。

## 2. 数据流

### 2.1 上传与探测

```
POST /api/media (multipart)
  → MediaIngestService.ingest
      → mediaRepository.insert（先占位拿自增 id）
      → Files.copy 到 {storage}/media/{assetId}/source{ext}
      → mediaRepository.updateStoragePath（回写真实路径）
      → MediaProbe（ffprobe -print_format json）
      → updateDerived（时长/宽高/fps/编码/metaJson）
```

> 踩坑记录：最初只写了 insert 占位、没有回写 `storage_path`，导致流水线拿到空字符串去 `Path.of("")`，
> 报 `ffprobe 解析失败: 退出码 1；: No such file or directory`。现在落盘后必须回写。

### 2.2 分析流水线

`AnalysisPipeline.run(jobId)` 按阶段推进，每进入一个阶段都会：

1. `jobRepository.updateProgress`（阶段 + 进度基数）
2. `jobEventRepository.append` 发一条 `stage` 事件（SSE 实时推送）

| 阶段 | 代码 | 产物 |
| --- | --- | --- |
| `PROBE` | `MediaProbe` | 媒体元信息 |
| `AUDIO` | `AudioExtractor.extract` | `audio/job-{id}/audio.wav`（16k mono） |
| `TRANSCRIBE` | `TranscriptionService` | `transcript_segment` 行 |
| `FRAMES` | `FrameExtractor.extract` | `frames/job-{id}/scene-%04d.jpg` |
| `OCR` | `VisualEvidenceService` | `visual_evidence` 行 |
| `SEGMENT` | `ChunkBuilder` 的时间轴对齐部分 | 统一毫秒时间轴 |
| `CHUNK` | `ChunkBuilder.build` + `ChunkEnricher.enrich` | `context_chunk` 行（含摘要/关键词/embedding） |
| `RETRIEVE` | `HybridRetriever.search` | Top-K 分块 + 证据段 |
| `PLAN` | `AnalysisAgent.makePlan` | 提纲（1..5 项） |
| `EXECUTE` | `AnalysisAgent.execute` | Draft（结论/证据/小节） |
| `CRITIC` | `EvidenceValidator.verify` + 模型 critique | 通过/失败 + 反馈 |
| `REPORT` | `ReportComposer.markdown` + `DocxExporter` | `analysis_report` 行 + `.docx` 文件 |
| `DONE` | `jobRepository.markSucceeded` | 状态终态 |

**任务级目录隔离**：`audio/`、`asr/`、`frames/` 全部按 `job-{jobId}` 分子目录。
否则同一素材并发跑两个任务时，后一个任务会清空前一个任务的帧目录，
导致前一个任务的 OCR 证据「串味」。

### 2.3 证据模型

两类证据，统一用毫秒时间戳对齐：

```java
record TranscriptSegment(long startMs, long endMs, String text,
                         String speaker, double confidence, String source)

record VisualEvidence(long timeMs, String framePath, String ocrText,
                      String caption, String hash, String source)
```

展示时统一渲染成 `[mm:ss]` 引用锚点（`Times.formatBracket`），
报告里的引用正则：`\[(\d{1,2}:\d{2}(?::\d{2})?)\]`。

## 3. 关键实现

### 3.1 抽帧：三段式 select 表达式

```
select = eq(n,0)                                   # 首帧必取
       + gte(t-prev_selected_t, gap) * (           # 与上一帧至少间隔 gap 秒
             gt(scene, threshold)                  # 场景切换点
           + gte(t-prev_selected_t, interval)      # 或超过 interval 秒强制兜底
         )
```

- `gap = max(2, ceil(durationMs / 1000 / maxCount))` 秒
- `interval = max(frameFallbackIntervalSeconds, ceil(durationMs / 1000 / maxCount))` 秒
- 时间戳来自 `showinfo` 的 `pts_time`，**必须挂 stderr 回调**（ffmpeg 的 showinfo 打在 stderr 上，
  只挂 stdout 会静默退化成按序号推算，这是本项目真实踩过的坑）

阈值默认 `0.006`。实测静态幻灯片/白板类视频的场景分常常低到 0.01 以下，
阈值取 0.35 时整段视频一帧都抽不到。去重的责任交给后面的两级去重，而不是靠抬高阈值。

### 3.2 去重：像素快筛 + 文本判重

纯感知哈希在这个场景会失效：深色背景 + 中间一行标题的四张不同幻灯片，
在 8×8 网格下 dHash 完全相同（汉明距离全为 0），16×16 才有区分度，
但任何单一阈值都无法同时容纳「同章 0.09」和「跨章 0.53」。

最终方案两级：

1. **快筛**：`dHash` 相同且 24×14 灰度签名平均绝对差 `< 0.05` → 直接跳过，连 OCR 都省掉
2. **文本判重**：OCR 文本 `normalizeWhitespace` 后与上一张保留帧相同 → 丢弃（保留首次出现）

空白 OCR 结果的帧（纯人像、纯风景）不构成可核验证据，直接丢弃，避免污染检索。

### 3.3 语音识别：云端优先 + 本地回退

```
for each 60s slice:
    try 云端 /audio/transcriptions (OpenAI 兼容, 最多 2 次)
    on failure → whisper-cli -m model -f slice -oj
    合并：word 级时间戳 + 片偏移 → 全局毫秒
最后 merge()：间隔 < 800ms 且前句 < 80 字的碎片合并
```

> 关键坑：`whisper-cli` 带 `-nt`（不打印时间戳）时，`-oj` 输出的 `transcription[].offsets`
> 会塌成整片（60 秒只给一条 `from=0, to=29960`），必须去掉 `-nt`。

### 3.4 分块与检索

- 分块窗口：`agent.chunk-seconds`（默认 300s）
- 每块产出：`summary`（模型摘要或确定性首句）、`keywords`、`visualKeywords`、`embedding`
- 打分：

```
chunk.score = 0.60 * cosine(question, chunk.embedding)
            + 0.25 * termScore(keywords, question)
            + 0.15 * termScore(visualKeywords, question)
```

`termScore` 是中英混排的命中率（中文取单字 + 相邻二字，英文按单词），
向量维度不一致或为空时该项直接为 0，等价于退化为词项检索。

### 3.5 Agent：Planner / Executor / Critic

```
planRetrieval(question) → RetrievalIntent(semanticQuery, keywords, visualKeywords)
        ↓ HybridRetriever.search
makePlan(goal, context) → Plan(understoodGoal, tasks[1..5])
        ↓ 最多 agent.max-rounds 轮
execute(goal, plan, context) → Draft(conclusions, evidence[], sections[])
        ↓
critique(draft) = 模型 verdict ⇊ EvidenceValidator.verify
        ↓ passed ? break : 带 feedback 重写
ReportComposer.markdown → 落库 + docx
```

**确定性校验**（`EvidenceValidator.verify`）不依赖模型：

| 规则 | 失败含义 |
| --- | --- |
| 模式要求的必需小节都存在 | 提纲没写全 |
| `evidence.source` 含 `ASR` 或 `OCR` | 引用了不存在的来源 |
| 时间戳落在某条 `TranscriptSegment` 的 `[start, end)` 内，或某条 OCR 帧的 ±2s 内 | 时间戳是编的 |
| `normalize(content)` 是原始证据文本的子串 | 引用的原文被改写过 |
| 每条 conclusion 至少有一条 `normalize(claim) == normalize(conclusion)` 且已核验的证据 | 有结论没有证据支撑 |

`normalize` = 小写 + 去掉所有标点/符号/空白，用来忽略全角半角与标点差异。
未通过时报告顶部会加一行提示，并附校验记录。

### 3.6 事件流

- 落库：`job_event`（`seq` 单调递增）
- 推送：`GET /api/jobs/{id}/stream` 返回 `SseEmitter`
  - 先发 `hello`，再回放 `after` 之后的历史事件（避免刷新丢事件）
  - 任务未终止时，`sse-pump` 线程池每 700ms 轮询新增事件
  - 终止时发 `done`（含 `status` / `progress` / `error`）并 complete
- 前端：`EventSource` 监听 `event` / `done`，断线 3 秒重连

## 4. 数据模型

| 表 | 作用 | 关键字段 |
| --- | --- | --- |
| `media_asset` | 媒体元信息 | `kind`、`duration_ms`、`storage_path`、`meta_json` |
| `analysis_job` | 分析任务 | `mode`、`status`、`stage`、`progress`、`notices`、`degraded` |
| `job_event` | 事件流水 | `seq`、`stage`、`type`、`progress`、`message` |
| `transcript_segment` | 语音证据 | `start_ms`、`end_ms`、`text`、`source` |
| `visual_evidence` | 画面证据 | `time_ms`、`ocr_text`、`frame_path`、`hash` |
| `context_chunk` | 上下文分块 | `summary`、`keywords`、`visual_keywords`、`embedding` |
| `analysis_report` | 报告 | `markdown`、`docx_path`、`trace_json` |
| `chat_message` | 追问历史 | `role`、`content`、`citations_json` |

所有 `CREATE TABLE` 都是 `IF NOT EXISTS`，H2 与 MySQL 语法兼容（`CLOB` 在 MySQL 侧可映射为 `LONGTEXT`）。

## 5. 并发与恢复

- `JobService` 用固定 2 线程的守护线程池，`ConcurrentHashMap<Long, Future<?>>` 记录运行中任务
- 启动时 `jobRepository.failZombies` 把上次进程崩溃遗留的 `PENDING` / `RUNNING` 标为失败
- 取消：`cancel` 先置 `CANCELED` 再 `future.cancel(true)`，流水线在下个阶段检查状态
- SSE 与流水线解耦：前端刷新、断线重连都不影响任务本身

## 6. 未做但值得做的

- 视觉模型描述关键帧（当前只识别屏幕文字）
- 说话人分离（`speaker` 字段已预留）
- 真正的增量 embedding 与向量库（当前是内存内暴力检索）
- 多任务共享同一份「证据提取」结果，避免重复抽帧/转写
