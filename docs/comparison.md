# 与 DOVideo-AI 的差异

对比口径：

- **参考项目**：DOVideo-AI `main` @ `df4fa83`（2026-09-30），浅克隆后按代码核对，README 只用于交叉验证。
- **本项目**：`codex/production-rocketmq`，含生产化改造与容器编排修复。
- 表格里每一行都以代码为准；“README 声称”不算证据。

一句话定位：参考项目更像 **Agent 框架 + 效果评测**，本项目更像 **可部署的产品 + 严格证据链**。

## 一、任务链路

| 能力 | 参考项目（df4fa83） | 本项目 | 结论 |
| --- | --- | --- | --- |
| 提交到消息队列 | `AnalysisDispatchService` 直接投 RocketMQ，靠“内容指纹 + 目标”加锁防重复 | 任务与 `job_outbox` 同事务写入，Broker 确认后才标记已发送 | 本项目更可靠 |
| 消费幂等 | Redisson 锁 + 幂等结果查询 | Redisson 锁 + 状态与 generation 双校验，旧代次消息直接确认 | 相当 |
| 投递失败重试 | RocketMQ 有限重投；失败入表，管理端可 replay | RocketMQ 有限重投；落 `failed_delivery`，**无查询与重投接口** | 参考领先 |
| 执行者失联 | 未见自动回收 | `StaleJobReaper`：抢锁成功 + 15 分钟无进度才重新排队，自动重跑有上限 | 本项目 |
| 分片上传 | 5 MB 分片，Redis 记录进度，MinIO 合并 | 8 MB 分片，MySQL 记进度 + 分片本身入 S3，跨实例可续传 | 相当 |
| 任务断点 | MySQL 为真源 + Redis 热缓存，持久化 VideoContext、分块、计划与 Critic 状态 | 按阶段落 `job_checkpoint`，复用已产出的转写与画面证据 | 参考恢复粒度更细 |

## 二、身份、租户与成本

| 能力 | 参考项目 | 本项目 | 结论 |
| --- | --- | --- | --- |
| 认证 | 自写 `AuthInterceptor` + Redis 会话；开放注册 | Spring Security 会话 + BCrypt + CSRF；注册默认关闭，首次部署由环境变量建管理员 | 本项目基线更标准 |
| 数据归属 | 按 `userId` 过滤 | `owner_id` + `OwnerGuard` 拦截器按 `/api/media`、`/api/jobs` 前缀统一校验 | 相当 |
| 每用户限流 | Redisson `RRateLimiter`：`limit:ai:user:<id>` + `limit:ai:global` | 提交与上传按用户、追问按任务限流（每分钟），登录注册按 IP | 相当 |
| 成本预算 | 无 token 预算 | 任务级 + 全局日 token 预算 | 各有侧重 |
| 用户级成本隔离 | 用户的 AI 调用速率受限 | **日预算键没有用户维度**，单个用户可吃光全站额度 | 本项目缺 |
| 管理端 | 失败任务列表 + `POST /{id}/replay` | 仅用户列表与创建 | 参考领先 |
| 对外开放 | 无 API token | 无 API token | 都没做 |

## 三、上下文与证据

| 能力 | 参考项目 | 本项目 | 结论 |
| --- | --- | --- | --- |
| 时序结构 | 统一 `VideoSegment`（语音 + OCR + 关键帧 + 时间戳） | 转写、画面、分块分表存储，统一毫秒时间轴与 `[mm:ss]` 锚点 | 相当，取舍不同 |
| 混合检索 | 关键词 + Qdrant 语义召回 TopK | `0.60 语义 + 0.25 关键词 + 0.15 画面关键词` 加权 | 相当 |
| 服务不可用 | 明确降级：Qdrant/Embedding 失败退关键词、模式路由回退 `GENERAL`、ASR/OCR 单路失败保留另一路 | 严格失败：模型、向量或证据校验不过即任务失败 | 目标不同，见下 |
| 说话人 | 无 | 音高聚类近似分离 | 本项目 |
| Agent 评测 | `AgentEvaluationService` + 离线评测 runner + telemetry 计数 | 无 | 参考领先 |

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

1. **失败任务管理接口**：`FailedAnalysisTaskService` + 管理端 replay，运维不必进数据库。
2. **模式注册表**：`ModeRegistry` / `ModeProfile`，新增分析模式不需要改 Java 枚举。
3. **Agent 离线评测**：可回归地衡量提示词与检索改动是否变好。
4. **链接导入与演示模式**：降低首次上手门槛。

## 六、我们有、参考项目没有

1. **事务发件箱 + 代次**：任务不会因进程退出而丢，旧消息也不能启动新一代任务。
2. **执行者失联自愈**：进程死掉后任务自动从断点继续，而不是永久停在 RUNNING。
3. **严格失败策略**：没有离线模板、关键词兜底或本地 whisper 回退。
4. **文档级交付物**：Word 插图报告、字幕三格式、说话人分离。
5. **标准运维面**：Prometheus 指标、就绪探针、CI 校验链路。

## 七、验证状态

本机用真实中间件（MySQL 8.4 / Redis / RocketMQ / MinIO / Qdrant）跑通过：Flyway 两个迁移、readiness 四依赖全绿、CSRF 登录、上传落 `assets/1/source.mp4`、发件箱 `PENDING → SENT`、消费幂等与断点续跑（4 次尝试均从「音频抽取」继续）、失败落 `failed_delivery`、`video_chunks` 集合自动创建。

尚未验证：**模型成功出报告**的完整链路（本机无真实 API key，任务按要求停在 ASR 鉴权失败）、多副本压测与 SLO 数据。

## 八、结论

- **已具备**：可部署的产品形态——可靠投递、多用户隔离、对象存储与向量库、可观测与失败可解释。
- **尚不具备平台能力**：用户级配额、对外开放接口、可扩展的模式注册、失败任务运维台。

按投入产出排序，补齐顺序建议为：用户级配额与用量可见 → 失败任务查询与重投 → API token 与 webhook → 模式注册化。

参考项目来源：[仓库](https://github.com/nimingzhe-ding/DOVideo-AI/tree/df4fa83)。
