# API 参考

- Base URL：`http://127.0.0.1:9090`
- 统一响应体：`{ "ok": true, "data": ..., "message": null }`
- 失败：`ok=false`，`message` 为中文可读原因，HTTP 状态码 400 / 404 / 409 / 413 / 500
- 除 SSE / 文件下载 / WebSocket 外均为 JSON

## 1. 媒体

### `POST /api/media` 上传

`multipart/form-data`，字段名 `file`。

```bash
curl -F "file=@demo.mp4" http://127.0.0.1:9090/api/media
```

```json
{
  "ok": true,
  "data": {
    "id": 1,
    "name": "demo.mp4",
    "kind": "VIDEO",
    "sizeBytes": 1749299,
    "durationMs": 76060,
    "width": 1280,
    "height": 720,
    "fps": 24.99,
    "videoCodec": "h264",
    "audioCodec": "aac",
    "storagePath": ".../media/1/source.mp4",
    "metaJson": "{\"hasVideo\":true,\"hasAudio\":true}"
  }
}
```

上传即完成 ffprobe 探测；探测失败不会拒绝上传，而是把原因写进 `metaJson.probeError`。

### `GET /api/media?limit=50`

媒体列表，`limit` 范围 1..200。

### `GET /api/media/{id}`

单个媒体详情，不存在返回 404。

### `GET /api/media/{id}/jobs`

该媒体下的所有分析任务。

### `DELETE /api/media/{id}`

级联删除：先取消运行中的任务，再清理该媒体下的全部任务、事件、转写、画面证据、上下文分块、报告、追问记录与磁盘目录（`data/media/{id}`）。

### `GET /api/media/{id}/info`

给播放器用的轻量信息。

```json
{ "id": 1, "name": "demo.mp4", "kind": "VIDEO", "durationMs": 76060, "sizeBytes": 1749299, "playable": true, "stream": "/api/media/1/stream" }
```

### `GET /api/media/{id}/stream`

原始文件流，支持单区间 `Range` 请求（`200` / `206`），带 `Accept-Ranges`、`Content-Range`，浏览器 `video` 标签可直接拖动进度条。

### `GET /api/media/{id}/thumbnail?at=1`

抽取指定秒数的画面作为封面，宽度 480。结果按时间点缓存到 `data/media/{id}/thumb-<秒*10>.jpg`，重复请求不再调用 ffmpeg。

### `GET /api/media/{id}/frame?t=30&w=960`

任意时间点抽帧预览，`w` 为宽度，缓存到 `preview-<t*10>-<w>.jpg`。

### `GET /api/media/{id}/download`

以附件形式下载原始文件，文件名按 RFC 5987 用 `filename*=UTF-8''` 编码。

### `GET /api/jobs/{jobId}/frames/{seq}`

返回该任务第 `seq` 条画面证据对应的关键帧 JPEG（`seq` 从 0 开始，与 `/api/jobs/{id}/evidence` 的顺序一致），供前端在证据卡片里直接展示。

---

## 1.5 分片上传（大文件 / 断点续传）

超过 32 MB 的文件建议走分片；前端会自动选择，也可以直接调接口。

### `GET /api/uploads/policy`

返回当前生效的分片大小，避免前端写死。

```json
{ "ok": true, "data": { "chunkSize": 8388608 } }
```

### `POST /api/uploads` 创建/复用会话

```json
{ "uploadId": "up-1a2b3c4d-43732475", "name": "lecture.mp4", "totalBytes": 43732475 }
```

`uploadId` 由客户端生成并保持稳定（前端用「文件名 + 大小 + 修改时间」派生）。
同一个 `uploadId` 重复调用是幂等的：已上传的分片不会被重置，
响应里的 `receivedIndexes` 就是断点续传要跳过的片。

```json
{
  "ok": true,
  "data": {
    "uploadId": "up-1a2b3c4d-43732475", "name": "lecture.mp4",
    "totalBytes": 43732475, "totalChunks": 6, "chunkSize": 8388608,
    "received": 2, "receivedBytes": 16777216, "percent": 38,
    "finished": false, "assetId": null,
    "receivedIndexes": [0, 1], "missingIndexes": [2, 3, 4, 5]
  }
}
```

### `PUT /api/uploads/{uploadId}/chunks/{index}` 上传一片

`multipart/form-data`，字段名 `file`。重复上传同一片是幂等的。

### `POST /api/uploads/{uploadId}/complete` 合并

- 缺片时返回 **409**，`message` 里带缺失序号，前端补传后重试即可；
- 合并后校验字节数与声明一致，再搬进 `data/media/{assetId}/`；
- 同一个会话重复调用返回 `"reused": true` 与同一个 `assetId`。

```json
{ "ok": true, "data": { "reused": false, "assetId": 6, "media": { "id": 6, "name": "lecture.mp4", "durationMs": 76060 } } }
```

### `GET /api/uploads/{uploadId}` 查询进度

返回结构同创建会话，可随时刷新 `receivedIndexes`。

### `DELETE /api/uploads/{uploadId}` 取消

删除会话记录与已落盘的分片。

> 未完成的会话默认保留 24 小时，由 `UploadJanitor` 每小时清理一次。

---

## 2. 分析任务

### `POST /api/jobs` 提交

```json
{
  "assetId": 1,
  "goal": "把这段课程整理成可以复习的笔记",
  "mode": "LEARNING",
  "title": "第一章 长视频理解"
}
```

- `goal` 可空，空则按模式使用默认目标
- `mode` ∈ `GENERAL` | `LEARNING` | `REVIEW` | `CREATION`，也可留空或传 `"AUTO"` 交给后端自动判定
- `mode` 传了非法值会返回 400（不会静默变成「通用理解」）
- 返回创建好的 `AnalysisJob`，任务在后台线程池中异步执行

`AnalysisJob` 上额外有两个字段说明模式是怎么来的：

| 字段 | 取值 | 含义 |
| --- | --- | --- |
| `modeSource` | `USER` | 调用方显式指定 |
| | `AUTO` | 模型根据 `goal` 判定 |
| | `RULE` | 模型不可用或没给出合法值，走关键词规则兜底 |
| `modeReason` | 中文短句 | 判定理由，如「目标偏向知识梳理与复习，选学习笔记」 |

```json
{
  "id": 14, "mode": "LEARNING", "modeSource": "RULE",
  "modeReason": "目标偏向知识梳理与复习，选学习笔记", "status": "PENDING"
}
```

> 同一素材上的第二个任务会自动复用此前任务已提取的转写、画面文字与分块，
> 只重新做检索与分析。事件流里能看到 `复用任务 9 的转写（3 段），跳过语音识别` 这类日志。

四种模式的定位：

| 模式 | 关注点 | 典型小节 |
| --- | --- | --- |
| `GENERAL` | 讲了什么 | 一句话概览 / 内容脉络 / 关键结论 / 重点片段 / 值得追问的问题 |
| `LEARNING` | 学得会 | 核心概念清单 / 知识点详解 / 示例与演示片段 / 复习自测问题 / 延伸阅读线索 |
| `REVIEW` | 站不站得住 | 议题与背景 / 讨论要点 / 结论与决策 / 待办与责任人 / 风险与遗留问题 |
| `CREATION` | 能不能二次创作 | 内容骨架 / 金句与钩子 / 可二次创作素材 / 节奏与结构分析 / 选题建议 |

### `GET /api/jobs?limit=50`

任务列表（默认 50，最多 200）。

### `GET /api/jobs/{id}`

```json
{
  "ok": true,
  "data": {
    "job": { "id": 5, "status": "SUCCEEDED", "stage": "DONE", "progress": 100, ... },
    "report": { "title": "...", "markdown": "...", "docxPath": "...", "traceJson": "..." },
    "events": [ { "seq": 1, "stage": "PROBE", "message": "解析媒体信息" } ]
  }
}
```

### `GET /api/jobs/{id}/events?after=0`

增量事件（不含 SSE，适合轮询兜底）。

### `GET /api/jobs/{id}/stream?after=0` — SSE

```
event: hello
data: {"jobId":5,"after":0}

event: event
data: {"seq":1,"stage":"PROBE","type":"stage","progress":5,"message":"解析媒体信息",...}

event: done
data: {"status":"SUCCEEDED","progress":100,"error":""}
```

- 连接后先回放 `after` 之后的历史事件，所以刷新页面不会丢进度
- 任务已终止时立即发 `done` 并关闭
- 服务端每 700ms 轮询新事件，客户端断线后 3 秒自动重连

### `GET /api/jobs/{id}/transcript?from=0&size=200`

```json
{ "ok": true, "data": { "total": 3, "items": [
  { "startMs": 0, "endMs": 3400, "text": "...", "speaker": "讲者 A", "source": "whisper" }
] } }
```

| 字段 | 说明 |
| --- | --- |
| `speaker` | 说话人标签（`讲者 A` / `讲者 B` …）。只有一个讲者、或关掉了 `SPEAKER_SEPARATION_ENABLED` 时为 `null` |
| `source` | 转写引擎，`whisper` 或 `cloud-asr` |

说话人标签由本地声学聚类得出（见 `docs/architecture.md` 3.11），因此同一段音频的
标签是稳定的（讲者 A 永远是先开口的人），但不保证跨任务一致：同一素材的每个任务
各跑一次聚类，分组编号可能不同。证据复用会连同标签一起复用，所以同一素材的
后续任务标签与来源任务一致。

### `GET /api/jobs/{id}/evidence`

画面证据列表（`timeMs`、`ocrText`、`caption`、`framePath`、`hash`、`source`）。

| 字段 | 说明 |
| --- | --- |
| `ocrText` | 本地 OCR 读到的屏幕文字；这一帧没有文字时为空串 |
| `caption` | 视觉模型写的一句画面描述；只在开启 `VISION_CAPTION_ENABLED` 且该帧没有文字时才有值 |
| `source` | 识别引擎，如 `vision-ocr`；描述帧的 `ocrText` 为空、`caption` 有值 |

两条约束：

- 只有「既没有文字也没有描述」的帧会被丢弃——纯人像画面不会以空文本进入检索
- 判重对文字与描述一视同仁：同一屏重复出现时只留第一次出现的时刻

### `POST /api/jobs/{id}/cancel`

取消运行中的任务。已终止的任务返回 409。

### `POST /api/jobs/{id}/reanalyze`

复用已有证据（转写 / OCR）重新跑 Agent 与报告，不重新抽帧转写。

```json
{ "goal": "换个角度讲讲证据校验", "mode": "REVIEW" }
```

### `DELETE /api/jobs/{id}`

删除单个任务：运行中会先取消，然后清理事件、转写、画面证据、上下文分块、报告、追问记录，以及该任务独有的关键帧、音频切片与 Word 文件（磁盘目录按 `frames/job-{id}`、`asr/job-{id}`、`audio/job-{id}` 隔离，不会影响同素材的其他任务）。

---

## 3. 报告

### `GET /api/jobs/{id}/report`

报告 JSON（`title` / `markdown` / `docxPath` / `mode` / `traceJson`）。

### `GET /api/jobs/{id}/report/markdown`

`Content-Type: text/markdown; charset=UTF-8`，可直接在浏览器打开或 `curl` 保存。

### `GET /api/jobs/{id}/report/docx`

`Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document`，
`Content-Disposition: attachment; filename*=UTF-8''...`（中文文件名已做 RFC 5987 编码）。

文件不存在返回 404「报告文件不存在，请重新生成」。

Word 里除了完整的报告正文，还会在「## 视频证据」小节之后插入关键帧截图：优先放被 `OCR` / `VISION` 证据
引用到的帧（时间戳 2 秒容差），剩余名额按时间均匀取样；最多 `REPORT_MAX_FIGURES` 张（默认 8，`0` 关闭）。
插图数量记在报告的 `traceJson.docxFigures` 里。

单张图缺失只会少一张，不会让整份导出失败。

### `GET /api/jobs/{id}/report/subtitle?format=srt`

把该任务的转写导出成字幕文件，`format` 可选 `srt`（默认）/ `vtt` / `txt`。

| format | Content-Type | 说明 |
| --- | --- | --- |
| `srt` | `application/x-subrip;charset=UTF-8` | 序号从 1 开始，时间轴用逗号毫秒 `00:00:01,200` |
| `vtt` | `text/vtt;charset=UTF-8` | 首行 `WEBVTT`，时间轴用句点毫秒 `00:00:01.200` |
| `txt` / `text` | `text/plain;charset=UTF-8` | 每段一行，`[mm:ss] 讲者：正文` |

```
Content-Disposition: attachment; filename*=UTF-8''video-studio-15.srt
```

```srt
1
00:00:00,000 --> 00:00:03,400
大家好，欢迎来到 video-studio-easy。

2
00:00:30,160 --> 00:00:53,360
按场景切换抽取画面……
```

导出时的两条硬约束（`report/SubtitleWriter.java`）：

- 空文本与零长度片段丢弃，否则播放器会出现空白字幕；
- 相邻片段至少留 20 ms 空隙（前一条 `end + 20ms` 与后一条 `start` 取较大者），
  重叠时间轴会被部分渲染引擎判为冲突而闪一下消失。

错误：

| 情况 | 状态码 | message |
| --- | --- | --- |
| 任务不存在 | 404 | `任务不存在: {id}` |
| 该任务没有转写 | 404 | `该任务还没有可导出的转写内容` |
| 不支持的格式 | 400 | `不支持的字幕格式: ass（可选 srt / vtt / txt）` |

---

## 4. 追问

### `POST /api/jobs/{id}/chat`

```json
{ "question": "第三节提到的那个阈值是多少？" }
```

```json
{ "ok": true, "data": { "answer": "根据 [00:40] 的画面文字 …" } }
```

- 未配置大模型时返回**基于检索结果的确定性回答**，并以引用块标注：
  `> 当前未配置大模型（AI_API_KEY），以下是基于已有证据的检索结果，不是生成式回答。`
- 答案中的 `[mm:ss]` 引用会被抽取并存进 `citations_json`

### `GET /api/jobs/{id}/chat?limit=50`

追问历史（时间正序，最多保留最近 8 条作为上下文）。

### `DELETE /api/jobs/{id}/chat`

清空追问历史。

---

## 5. 运行环境

### `GET /api/jobs/runtime`

返回结构与其余接口一致（`{ok, data, message}`），`data` 为：

```json
{
  "aiEnabled": false,
  "realtimeEnabled": false,
  "model": "qwen-plus",
  "embeddingModel": "text-embedding-v3",
  "visionModel": "qwen-vl-max",
  "visionCaptionEnabled": false,
  "objectStorage": "local",
  "objectStorageEnabled": false,
  "vectorStore": "memory",
  "vectorStoreEnabled": false,
  "status": 5
}
```

`objectStorage` / `vectorStore` 是当前实际生效的实现名（`local` / `minio`、`memory` / `milvus`），
用来在界面上确认「这次到底用的是本地实现还是中间件」——开关打开了但中间件连不上时，
这里的值仍然会是 `minio` / `milvus`，需要看日志里的降级警告。

前端用来提示「当前未配置大模型」。`visionCaptionEnabled` 同时受 `cloudEnabled()` 与
`VISION_CAPTION_ENABLED` 约束——没有 key 时即使打开开关也不会真的调模型。

> 该接口曾经有两个坑：把配置类写成方法参数（Spring MVC 会按请求参数重新绑定，永远读不到真实配置），
> 以及返回裸 `Map` 没有包 `ApiResponse`（前端统一取 `payload.data`，拿到的永远是 `undefined`）。
> 现在配置走构造器注入、响应统一包一层。

---

## 6. 实时对话（WebSocket）

### `WS /ws/realtime`

浏览器 ⇄ 服务端 ⇄ 云端 realtime 模型的双向中转。

- 浏览器不需要也不应该持有 API key，`Authorization: Bearer` 由服务端补上
- 文本帧与二进制帧都双向转发（PCM 音频走二进制）
- 上游地址追加 `?token=<key>`（部分厂商的 realtime 端点这样鉴权）
- 未配置 `REALTIME_URL` / `REALTIME_API_KEY` 时 `realtimeEnabled=false`，前端会禁用该面板

前端采集：`getUserMedia` → `AudioContext`（16 kHz）→ `ScriptProcessor`（4096）→ Int16 PCM → 二进制帧；
回放：24 kHz Int16 → `AudioBufferSourceNode`。

---

## 7. 错误码约定

| 状态码 | 场景 | 示例 message |
| --- | --- | --- |
| 400 | 参数缺失/非法 | `缺少参数：assetId` |
| 404 | 资源不存在 | `任务不存在` / `报告文件不存在，请重新生成` |
| 409 | 状态冲突 | `任务已结束` / `媒体下有任务在运行` |
| 413 | 上传过大 | `文件超过上传大小限制` |
| 500 | 未预期异常 | `服务内部错误：NullPointerException` |

任务**内部**的失败不会体现为 HTTP 错误，而是 `job.status = FAILED` 且 `job.error` 给出原因，
同时事件流里会有一条 `type=error` 的事件。
