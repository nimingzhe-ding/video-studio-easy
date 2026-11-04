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

小于 32 MB 走一次性 multipart；更大的文件走分片（见 2.1.1）。

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

#### 2.1.1 分片上传

```
POST /api/uploads {uploadId, name, totalBytes}
  → 校验上限 → totalChunks = ceil(totalBytes / chunkSize)
  → 同一 uploadId 但大小变了 → 清空旧分片重新开始（幂等）
PUT  /api/uploads/{id}/chunks/{index}   # 每片先写 .tmp 再 Files.move 原子改名
POST /api/uploads/{id}/complete
  → 查缺片，缺则 409 并返回缺失序号（前端补传后重试）
  → 按序号拼接 → 校验字节数 → Files.move 进 media/{assetId}/source{ext}
  → MediaIngestService.ingestFile（探测、回填派生字段）
  → markFinished + 删除整个 uploads/{uploadId} 目录
```

设计取舍：

- **`uploadId` 由客户端派生**（文件名 + 大小 + 修改时间），所以刷新页面、换标签页都能接上同一个会话，
  服务端不需要额外的「查找我的上传」接口。
- **进度用真实字节算**：`SELECT COALESCE(SUM(size_bytes),0) FROM upload_chunk`，
  早期版本用「分片数 × chunkSize」估算，最后一片必然虚高。
- **幂等靠 `upload_chunk` 主键**：`(upload_id, chunk_index)` 冲突就覆盖，
  `received` 只在「本来没有这一片」时才累加，所以网络重试不会让计数虚增。
- **已完成的会话记录不立刻删**：`complete` 重试时还要靠它返回同一个 `assetId`；
  只把磁盘目录回收，记录交给 `UploadJanitor` 在保留期后清掉。

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
一次 ffmpeg -f segment 切成 60s 片段（-segment_list csv 顺便拿到每片起止时间）
→ 线程池并发转写（asr-concurrency，默认 3）
    for each slice: try 云端 /audio/transcriptions (最多 2 次)
                    on failure → whisper-cli -m model -f slice -oj
→ 按提交顺序 future.get() 收集，再按 startMs 排序
→ word 级时间戳 + 片偏移 → 全局毫秒
最后 merge()：间隔 < 800ms 且前句 < 80 字的碎片合并
```

两个细节：

- 切片从「每片一个 ffmpeg 进程」改成**一次** `-f segment`：
  76 秒音频从 4 次进程降到 1 次（0.054s），10 分钟音频同样 0.054s。
- 并发**不能**改变结果：收集时严格按提交顺序 `get()`，再按 `startMs` 排序，
  所以 `ASR_CONCURRENCY=1` 与 `=3` 的转写文本逐字相同（实测 27 段一致）。

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

**校验未通过时不是简单重写**。模型缺的通常是证据而不是措辞，所以第二轮开始前会做两件事：

1. **按时间戳回捞**：`ContextRenderer.recall(critique.requiredTimestamps, 全量证据, margin)`
   从**该素材的全部**转写/OCR 里把 Critic 点名的时间点附近片段捞回来（`margin = max(60s, 片段时长)`，
   容差要留够是因为模型给的时刻常落在片段边界外几百毫秒）；
2. **按缺口重检索**：把「目标 + feedback + missingRequirements + unsupportedClaims」拼成一句新的查询再走一次
   `HybridRetriever.search`，命中结果并入证据集（去重后按时间排序）。

两条都只扩大证据、不改结论，所以「多轮」不会靠改写措辞来骗过校验。

**上下文预算按条目选入**（`ContextRenderer.render`）：超预算的条目被**跳过并继续尝试后面的条目**，
而不是遇到超长就 `break`。片段长度差异很大（一句 20 字 vs 一整段 600 字），一旦 break，
一条长片段就能让后面几十条短证据全都进不了上下文；同时末尾不再做硬截断，避免把半句话喂给模型。
被跳过的条目会以「另有 N 条片段因篇幅或条数上限省略」显式说明，模型因此知道信息有损。

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

### 3.6 自动模式路由与跨任务证据复用

**模式路由**（`service/ModeRouter.java`）：客户端把 `mode` 置空或传 `"AUTO"` 时，后端在任务落库前先判定模式。

```java
Decision route(String goal)
  ├─ 目标为空           → GENERAL("未提供分析目标，按通用模式分析")
  ├─ llm.enabled()      → 模型判定，只认带引号的 "LEARNING" 这类严格命中
  └─ 否则 / 失败 / 非法 → 关键词规则 guess(goal) 取命中数最高者
```

三条约束写进了实现：

- **只产出具体模式**。任务上的 `mode` 一定是四个业务模式之一，没有「待路由」中间态，
  否则读写两端会用不同的键，前端永远查不到结果。
- **绝不阻断主流程**。模型不可用、超时、返回非法值一律回退关键词规则，本类对外不抛异常。
- **判定可追溯**。任务上落 `mode_source`（`USER` / `AUTO` / `RULE`）与 `mode_reason`（中文理由），
  界面上直接显示「学习笔记 · 规则判定」和判定理由，用户能看懂为什么被分到这一类。

反过来，`mode` 传了值但拼错时会**报错**而不是静默变成通用理解——`AnalysisMode.of()` 会吞掉非法值，
所以 `JobController.parseMode` 显式比对枚举名后抛 400。

**跨任务证据复用**（`service/EvidenceService.java`）：同一素材的第二个任务不必重新转写与识别。

```
findReusable(assetId, selfJobId)
  └─ 该素材上最近的 SUCCEEDED 任务 → 读它的转写/画面/分块
       ├─ 有转写 → 直接落库，跳过抽音与 ASR
       ├─ 有画面 → 复制帧文件到本次任务的 frames/job-{id}/ 再落库，跳过抽帧与 OCR
       └─ 两者都在 → 连分块一起复用，跳过摘要与向量化
```

复用的三条实现细节：

- **昂贵且与目标无关**：转写与 OCR 只取决于素材内容，10 分钟音频约 30 秒，与「用什么模式分析」无关。
- **帧文件要复制而不是共用路径**：任务可以被单独删除，沿用源路径会让复用方的关键帧 404；单帧 20-40KB，代价可接受。
- **帧丢失要降级**：`framesAvailable()` 检查文件可读性，目录被清理过就只复用转写、重新抽帧，并发一条 warning。

### 3.7 事件流

- 落库：`job_event`（`seq` 单调递增）
- 推送：`GET /api/jobs/{id}/stream` 返回 `SseEmitter`
  - 先发 `hello`，再回放 `after` 之后的历史事件（避免刷新丢事件）
  - 任务未终止时，`sse-pump` 线程池每 700ms 轮询新增事件
  - 终止时发 `done`（含 `status` / `progress` / `error`）并 complete
- 前端：`EventSource` 监听 `event` / `done`，断线 3 秒重连

### 3.8 字幕导出

`report/SubtitleWriter.java` 是纯函数工具类，不依赖 Spring，三种格式共用同一套「可用片段」过滤：

```java
long start = Math.max(0, segment.startMs());
long end = Math.max(start + 200, segment.endMs());          // 零长度补足 200ms
if (previousEnd >= 0 && start < previousEnd + GAP_MS) {      // GAP_MS = 20
    start = previousEnd + GAP_MS;                            // 重叠前推，不裁掉内容
    end = Math.max(start + 200, end);
}
```

- **为什么要留 20ms 空隙**：whisper 相邻片段经常首尾相接（前一段 `end=53360`、后一段 `start=53360`），
  部分播放器会把零间隔判定为重叠冲突，表现为后一条闪现即消失。前推起点而不是缩短结束时间，是因为
  字幕宁可晚 20ms 也不能丢字。
- **为什么丢掉空片段**：静音片、纯音乐片转写常返回空串，写进 SRT 就是一条只有时间轴的空白字幕。
- **正文必须压成一行**：`Texts.normalizeWhitespace(...).replace("\n", " ")`——SRT 允许换行，
  但换成两行后部分播放器只显示第一行。
- 时间轴格式写死在下游播放器的容错边界内：SRT `HH:MM:SS,mmm`、VTT `HH:MM:SS.mmm` 且首行必须是 `WEBVTT`。

### 3.9 定向视觉描述（caption）

`llm/FrameCaptioner.java` 是唯一调用视觉模型的地方，由 `config/AiProperties.visionCaptionEnabled()` 控制：
**默认关闭，只对 OCR 一个字都没读到的帧触发，且有帧数配额**。

三个约束，每个都对应一次实际的取舍：

1. **为什么默认关**。视觉模型按帧计费，一小时课程抽出的帧数是几百量级，全量调用成本不可控。
   参考项目 DOVideo-AI 在访谈文档里的立场是「对低置信度或强视觉任务定向调用，而不是所有关键帧全量调用」，
   这里直接照做。
2. **为什么只挑空帧**。课件、录屏、会议共享的绝大多数帧文字都能被本地 OCR 拿到，模型再描述一遍是纯浪费。
   空帧原先是被直接丢弃的（纯人像、纯风景没有任何可引用内容），有了描述之后才第一次有机会进入证据链。
3. **为什么要能通过校验**。描述被当作 `VISION` 来源的原始证据，`EvidenceValidator` 会逐字核对
   `normalize(caption).contains(normalize(content))`。否则模型完全可以在第二轮把一句自己编的画面描述
   当成证据引用，校验环节就成了摆设。

实现上有两个容易写错的位置：

```java
// 1. 判重必须「文字优先、描述兜底」，否则两帧不同画面但描述相同的过渡页会各留一条重复证据
String key = captionOnly ? Texts.normalizeWhitespace(caption) : normalized;

// 2. 描述要放在文本判重之前算，而不是在丢弃空帧之后
Map<Path, String> captions = captionBlankFrames(scannedFrames, progress);
```

配额按**时间顺序**取前 N 个空帧而不是随机取，保证同一素材重跑时描述的帧一致、可复现。
描述结果写进 `visual_evidence.caption`，同时进入 `ChunkBuilder.joinOcr`（前缀标 `画面描述：`）
与 `ContextRenderer` 的 `== 画面文字（OCR）与画面描述（VISION）==` 小节，因此能参与检索与 embedding。

### 3.10 Word 报告的插图

`report/DocxExporter.java` 有两个重载：`export(markdown, target)` 和 `export(markdown, target, figures)`。
图片**不写进 Markdown**——同一份 Markdown 还要给前端渲染，写死服务器绝对路径会让浏览器去请求 `file://`。
插图走旁路清单 `record Figure(long timeMs, Path image, String caption)`，只在 Word 里落到文档中。

- **插在哪**：遇到第 2 级标题 `视频证据` 时置位，等到这段列表结束、下一个标题出现之前写入图片段落。
  放早了会打断证据列表，放文档末尾又和对应结论隔了十几页。
- **挑哪些帧**：先取草稿里真的被 `OCR` / `VISION` 证据引用的帧（时间戳 2 秒容差，ASR 段起点常比抽帧时刻晚一点），
  名额还有剩就按时间均匀取样补足——只放被引用的会漏掉没写进结论的章节，全放又会让几百帧淹没正文。
  上限是 `agent.max-report-figures`（默认 8，`0` 关闭）。
- **失败不致命**：单张图读不出来只跳过这一张；一张都插不进去时连「关键帧（N 张）」标题都不写，
  避免出现一个空标题。报告的主体是文字，不能因为少一张截图就整份失败。
- **尺寸**：宽 420px，高度按原图宽高比算（`ImageIO.read` 拿尺寸，`Units.toEMU` 换算），
  这样 16:9 的横屏录屏和 3:4 的竖屏手机录像都不会被拉变形。

同时在 `trace.docxFigures` 里记下插图数量，前端「下载 Word」按钮据此显示「· N 图」。

顺带修掉了一个静默失效的老问题：有序列表分支原来写 `run.setText(整行)` 之后又调 `appendInline(p, "")`，
等于有序列表里的 `**粗体**` 从来没被解析过；现在拆成「编号 + 走 `appendInline` 的正文」。

## 4. 数据模型

| 表 | 作用 | 关键字段 |
| --- | --- | --- |
| `media_asset` | 媒体元信息 | `kind`、`duration_ms`、`storage_path`、`meta_json` |
| `analysis_job` | 分析任务 | `mode`、`status`、`stage`、`progress`、`notices`、`degraded` |
| `job_event` | 事件流水 | `seq`、`stage`、`type`、`progress`、`message` |
| `transcript_segment` | 语音证据 | `start_ms`、`end_ms`、`text`、`source` |
| `visual_evidence` | 画面证据 | `time_ms`、`ocr_text`、`caption`、`frame_path`、`hash` |
| `context_chunk` | 上下文分块 | `summary`、`keywords`、`visual_keywords`、`embedding` |
| `analysis_report` | 报告 | `markdown`、`docx_path`、`trace_json` |
| `chat_message` | 追问历史 | `role`、`content`、`citations_json` |

所有 `CREATE TABLE` 都是 `IF NOT EXISTS`，H2 与 MySQL 语法兼容（`CLOB` 在 MySQL 侧可映射为 `LONGTEXT`）。

## 5. 并发与恢复

- `JobService` 用固定 2 线程的守护线程池，`ConcurrentHashMap<Long, Future<?>>` 记录运行中任务
- 任务内部还有两级并发：`asr-concurrency`（转写）与 `ocr-concurrency`（画面文字），默认都是 3
- 画面证据是「串行指纹快筛 → 并行 OCR → 串行文本判重」三段式：
  去重依赖前一帧的状态，所以只有 OCR 这一步能并行
- 分片上传的清理交给 `UploadJanitor`（默认每小时第 17 分），清理未完成会话与残留目录
- 启动时 `jobRepository.failZombies` 把上次进程崩溃遗留的 `PENDING` / `RUNNING` 标为失败
- 取消：`cancel` 先置 `CANCELED` 再 `future.cancel(true)`，流水线在下个阶段检查状态
- SSE 与流水线解耦：前端刷新、断线重连都不影响任务本身

## 6. 前端结构

单页 Vue 3 应用，没有引入 UI 框架，样式是一套手写设计系统（`client/src/styles.css`，
`--token` 双层：`:root` 暗色 + `:root[data-theme='light']` 亮色，布局用 grid-areas
`topbar / sidebar main`）。

| 文件 | 职责 |
| --- | --- |
| `App.vue` | 布局与状态中枢：上传、媒体库、任务列表、播放器、五个标签页、主题与抽屉 |
| `api.js` | REST 封装、XHR 上传（带进度）、SSE 订阅（返回关闭函数） |
| `format.js` | 时长/大小/相对时间的格式化，以及模式、状态、阶段、引擎的中文文案 |
| `markdown.js` | Markdown 渲染 + `[mm:ss]` 转成可点胶囊 + 目录抽取 + 搜索高亮（手写扫描，避免正则注入） |
| `components/JobCard.vue` | 任务卡片：状态色条、阶段与进度、悬停取消/删除 |
| `components/ReportView.vue` | 报告正文、右侧目录、导出按钮、证据校验徽章、元信息 |
| `components/TranscriptView.vue` | 转写与画面证据：过滤、搜索高亮、关键帧灯箱、时间戳联动 |
| `components/ChatPanel.vue` | 追问对话：建议问题、Markdown 回答、引用时间戳可点 |
| `components/RealtimePanel.vue` | 实时对话：麦克风采集、波形、状态与转写回显 |

两个容易踩的点：

1. **时间轴联动只有一条通路**：任何组件都不直接持有播放器，统一 `emit('seek', 'mm:ss')`
   给 `App.vue`，由它 `video.currentTime = parseClock(clock)`。这样报告、追问、转写三处的
   时间戳行为永远一致。
2. **0 秒是合法时间点**：`formatDuration` 早期用 `if (!ms)` 判空，把 `00:00` 显示成 `--:--`；
   现在只把 `null/undefined/NaN/负数` 当缺失。

后端为界面新增的能力（播放、封面、关键帧、删除）：

- `GET /api/media/{id}/stream` 支持单区间 `Range`，`video` 标签才能拖动进度条
- `GET /api/media/{id}/thumbnail|frame` 抽帧后按时间点缓存到磁盘，重复请求不再调 ffmpeg
- `GET /api/jobs/{jobId}/frames/{seq}` 让证据卡片直接显示关键帧，不用暴露服务器路径
- `DELETE /api/jobs/{id}` 与级联的 `DELETE /api/media/{id}` 由 `CleanupService` 统一处理，
  按 `job-{id}` 目录粒度删除，避免误删同素材其他任务的帧与音频

## 7. 未做但值得做的

- 视觉模型描述关键帧（当前只识别屏幕文字）
- 说话人分离（`speaker` 字段已预留）
- 真正的增量 embedding 与向量库（当前是内存内暴力检索）
- 多任务共享同一份「证据提取」结果，避免重复抽帧/转写（已部分完成：见 3.6）
