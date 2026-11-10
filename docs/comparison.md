# 与 DOVideo-AI 的差异

对比口径：

- **参考项目**：DOVideo-AI `main` @ `df4fa83`（2026-09-30），浅克隆后按代码核对，README 只用于交叉验证。
- **本项目**：`main`，含生产化改造与容器编排修复。
- 表格里每一行都以代码为准；“README 声称”不算证据。

一句话定位：参考项目更像 **Agent 框架 + 效果评测**，本项目更像 **可部署的产品 + 严格证据链**。

## 一、任务链路

| 能力 | 参考项目（df4fa83） | 本项目 | 结论 |
| --- | --- | --- | --- |
| 提交到消息队列 | `AnalysisDispatchService` 直接投 RocketMQ，靠“内容指纹 + 目标”加锁防重复 | 任务与 `job_outbox` 同事务写入，Broker 确认后才标记已发送 | 本项目更可靠 |
| 消费幂等 | Redisson 锁 + 幂等结果查询 | Redisson 锁 + 状态与 generation 双校验，旧代次消息直接确认 | 相当 |
| 投递失败重试 | RocketMQ 有限重投；失败入 `failed_analysis_task`，管理端可 replay，同一条只能重放一次 | RocketMQ 有限重投；落 `failed_delivery`，管理端可查询与重投，`replayed_at` 保证幂等 | 相当 |
| 毒消息可观测 | 缺字段时写哨兵值落台账，占位记录拒绝重放 | `jobId` 非法时脱敏后写入 `failed_delivery` 并直接确认，管理端可见 | 相当 |
| 错误信息脱敏 | `sanitizeError` 用三条正则抹掉 Bearer / api_key / sk- 前缀 | `Secrets.scrub` 六类模式，覆盖 Bearer、命名参数、厂商前缀、URL 查询参数与 JSON 字段；仓储层再兜一次 | 本项目覆盖更广 |
| 执行者失联 | 未见自动回收 | `StaleJobReaper`：抢锁成功 + 15 分钟无进度才重新排队，自动重跑有上限 | 本项目 |
| 分片上传 | 5 MB 分片，Redis 记录进度，MinIO 合并 | 8 MB 分片，MySQL 记进度 + 分片本身入 S3，跨实例可续传 | 相当 |
| 任务断点 | MySQL 为真源 + Redis 热缓存，持久化 VideoContext、分块、计划与 Critic 状态 | 按阶段落 `job_checkpoint`，复用已产出的转写与画面证据 | 参考恢复粒度更细 |

## 二、身份、租户与成本

| 能力 | 参考项目 | 本项目 | 结论 |
| --- | --- | --- | --- |
| 认证 | 自写 `AuthInterceptor` + Redis 会话；开放注册 | Spring Security 会话 + BCrypt + CSRF；注册默认关闭，首次部署由环境变量建管理员 | 本项目基线更标准 |
| 数据归属 | 按 `userId` 过滤 | `owner_id` + `OwnerGuard` 拦截器按 `/api/media`、`/api/jobs` 前缀统一校验 | 相当 |
| 每用户限流 | Redisson `RRateLimiter`：`limit:ai:user:<id>` + `limit:ai:global` | 提交与上传按用户、追问按任务限流（每分钟），登录注册按 IP | 相当 |
| 成本预算 | `agent.budget.max-estimated-tokens` / `max-estimated-cost`，单任务估算上限 | 单任务 + 全站每日 + 单用户每日三层 token 预算 | 本项目维度更全 |
| 用户级成本隔离 | 无，只有用户级速率限制 | 用户每日额度用尽只挡住该用户，不影响其他人 | 本项目 |
| 管理端 | 失败任务列表 + `POST /{id}/replay` | 账户列表与创建 + 失败投递列表与重投 | 相当 |
| 对外开放 | 无 API token、无 webhook | API 令牌（Bearer）+ 任务终态 webhook，带 HMAC 签名 | 本项目 |

## 三、上下文与证据

| 能力 | 参考项目 | 本项目 | 结论 |
| --- | --- | --- | --- |
| 时序结构 | 统一 `VideoSegment`（语音 + OCR + 关键帧 + 时间戳） | 转写、画面、分块分表存储，统一毫秒时间轴与 `[mm:ss]` 锚点 | 相当，取舍不同 |
| 分析模式 | `ModeRegistry` 持 `EnumMap`，构造器里 `register(...)`；新增模式仍要改枚举与 Java | `modes.json` + `ModeRegistry`，外置文件可覆盖或追加，不改代码 | 本项目可扩展性更强 |
| 混合检索 | 关键词 + Qdrant 语义召回 TopK | `0.60 语义 + 0.25 关键词 + 0.15 画面关键词` 加权 | 相当 |
| 服务不可用 | 明确降级：Qdrant/Embedding 失败退关键词、模式路由回退 `GENERAL`、ASR/OCR 单路失败保留另一路 | 严格失败：模型、向量或证据校验不过即任务失败 | 目标不同，见下 |
| 说话人 | 无 | 音高聚类近似分离 | 本项目 |
| Agent 评测 | `AgentEvaluationService` + 4 条 golden case 的离线 runner + telemetry 计数 | 无 | 参考领先 |

“严格失败”不是能力缺失，而是产品取舍：参考项目优先保证主链路不断，本项目优先保证“不产出看起来正常但用错提纲的报告”。代价是单点故障会直接暴露给用户。

## 四、交付与运维

| 能力 | 参考项目 | 本项目 | 结论 |
| --- | --- | --- | --- |
| 导出 | `AudioExportService`（音频片段） | Word（按时间顺序插图）+ SRT/VTT/TXT | 本项目 |
| 可观测 | `AgentTelemetry` 自定义指标与轨迹 | Actuator + Micrometer/Prometheus + liveness/readiness + 发件箱积压/最老年龄/死信/重排队指标 | 本项目更标准 |
| 部署 | Compose + `scripts/dev-up.sh` | Compose（S3 兼容存储、Broker 卷属主与初始化都有显式处理）+ CI 校验前端、e2e 与镜像构建 | 本项目 |
| 录入方式 | yt-dlp 解析在线链接（可选） | 仅文件与分片上传 | 参考领先 |
| 演示模式 | 前端 `?demo` 内置样例数据 | 无 | 参考领先 |

## 五、参考项目有、我们没有

1. **Agent 离线评测的完整形态**：它有 golden case 的离线 runner 与用户反馈满意度统计；
   我们只有确定性质量评分（`DraftEvaluator`），不测模型主观判断，也没有反馈回路。
2. **Chrome 扩展的深度**：它的侧边栏能展示进度，并支持点击时间戳跳回当前标签页；
   我们的扩展只做提交，不做播放器控制。

## 六、我们有、参考项目没有

1. **事务发件箱 + 代次**：任务不会因进程退出而丢，旧消息也不能启动新一代任务。
2. **执行者失联自愈**：进程死掉后任务自动从断点继续，而不是永久停在 RUNNING。
3. **严格失败策略**：没有离线模板、关键词兜底或本地 whisper 回退。
4. **文档级交付物**：Word 插图报告、字幕三格式、说话人分离。
5. **标准运维面**：Prometheus 指标、就绪探针、CI 校验链路。
6. **用户级成本隔离**：单用户每日额度，防止一个人吃光全站额度。
7. **失败投递运维台**：管理端可列出并重投死信任务，不必进数据库。
8. **配置驱动的分析模式**：新增模式加一份 JSON，不改 Java。参考项目的 `ModeRegistry` 是构造器里硬编码的 `EnumMap`。
9. **错误信息脱敏覆盖更广**：除了它的三类模式，还覆盖 URL 查询参数、JSON 字段与 S3 签名参数，且在仓储层兜底。
10. **产物质量评分入库**：每次分析都把可比较的确定性评分写进 trace，改动提示词后能直接对比。
11. **测试规模**：后端 140 个用例 + 前端 28 个单测 vs 26 + 5。

## 七、验证状态

本机用真实中间件（MySQL 8.4 / Redis / RocketMQ / MinIO / Qdrant）跑通过：Flyway 迁移、readiness 四依赖全绿、CSRF 登录、上传落 `assets/1/source.mp4`、发件箱 `PENDING → SENT`、消费幂等与断点续跑（4 次尝试均从「音频抽取」继续）、失败落 `failed_delivery`、`video_chunks` 集合自动创建。

尚未验证：**模型成功出报告**的完整链路（本机无真实 API key，任务按要求停在 ASR 鉴权失败）、多副本压测与 SLO 数据、失败投递重投在真实 Broker 上的端到端表现。

## 八、结论

- **已具备**：可部署的产品形态——可靠投递、多用户隔离与成本隔离、对象存储与向量库、可观测与失败可解释、死信可运维、模式可配置、链接导入、产物质量可度量、对外开放接口。
- **尚不具备**：带用户反馈回路的 Agent 评测。

剩余投入产出最高的一项是「用户反馈回路」：让用户对报告打分，把满意度与 `DraftEvaluator` 的确定性分数一起看，才能判断提示词改动是否真的改善了产出。

参考项目来源：[仓库](https://github.com/nimingzhe-ding/DOVideo-AI/tree/df4fa83)。
