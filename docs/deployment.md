# 部署、升级与运行

## 必需服务

MySQL 8.4、Redis 7.4、RocketMQ 5.3.4、S3 兼容对象存储、Qdrant 和真实模型接口。应用运行 Java 21，镜像构建使用 Node 22。FFmpeg、ffprobe、Tesseract 和中英文语言包由运行镜像提供。

.env.example 只提供变量名称，不包含可直接使用的密码。所有服务使用独立凭证。不要将 .env、数据库快照或对象存储密钥提交到仓库。

Compose 中 S3 服务使用固定版本 pgsty/silo 和 pgsty/mc，保持 MinIO SDK 的 S3 接口。项目与版本来源：[Silo](https://github.com/pgsty/silo)、[客户端](https://github.com/pgsty/mc)。可以使用托管 S3 替代自建服务；连接凭证需要指定桶的读、写、列举和删除权限。

## 单节点部署

~~~sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
~~~

默认只有应用绑定主机 127.0.0.1:9090，供 HTTPS 反向代理访问。中间件端口仅在容器网络可见。代理需要允许大文件、Range 请求、WebSocket Upgrade 与 SSE，SSE 路径应关闭代理缓冲。

代理必须转发并覆盖 `X-Forwarded-For`：应用默认 `FORWARD_HEADERS_STRATEGY=native`，由 Tomcat 从该头取真实来源地址。登录与注册按来源 IP 限流，如果代理不转发此头，所有请求会被算成同一个来源，限流退化为全站共享一个桶。只有在代理不转发、且只能靠 `remoteAddr` 计数的环境下才改为 `none`。

SESSION_COOKIE_SECURE=true 适用于 HTTPS；本机 HTTP 验证时才设为 false。生产不要通过开放数据库和 Broker 端口解决连通问题。

首次启动创建 AUTH_ADMIN_USERNAME。AUTH_ADMIN_PASSWORD 至少 12 位，首次创建后不会在重启时覆盖已有密码。AUTH_REGISTRATION_ENABLED 默认 false。

应用以非 root 用户运行，根文件系统只读，/work 与 /tmp 为临时工作目录。默认 /work 限额 8 GiB，必须依据上传上限、分析并发、音轨和关键帧大小评估容量。临时空间不足会明确失败，不能自动切换成低质量分析。

Compose 里的两个初始化步骤有隐式依赖，改动时不要删掉：

- rocketmq-perms：命名卷由 root 创建，而 Broker 以 uid 3000 运行，必须先 chown，否则 Broker 会因不可写反复重启（日志里只有 logback 初始化，退出码 253）。
- rocketmq-init：mqadmin 失败时退出码仍是 0，不能用它判断成功；脚本以 topicList 里能查到 topic 作为唯一成功信号，并限制重试次数。
- minio-init：pgsty/mc 镜像没有 sed，桶策略用 printf 生成；创建策略与用户都做了幂等处理，重复启动不会中断。

对象桶策略允许该用户列举桶、读写与删除对象、放弃分段上传。改用托管 S3 时，按同样范围授权。

## 外部中间件连接

直接运行 jar 时，需要提供：

| 配置 | 用途 |
|---|---|
| DB_URL、DB_USERNAME、DB_PASSWORD | MySQL 数据源 |
| REDIS_HOST、REDIS_PORT、REDIS_PASSWORD | Redis 会话、锁、计数 |
| ROCKETMQ_NAME_SERVER | NameServer 列表 |
| ROCKETMQ_ACCESS_KEY、ROCKETMQ_SECRET_KEY | 外部 Broker 启用 ACL 时的凭证 |
| S3_ENDPOINT、S3_ACCESS_KEY、S3_SECRET_KEY、S3_BUCKET | 私有对象桶 |
| QDRANT_URL、QDRANT_API_KEY、QDRANT_COLLECTION | 向量库 |
| EMBEDDING_DIMENSIONS | 向量维度，必须与模型一致 |
| AI_API_KEY、AI_BASE_URL、LLM_MODEL | 真实模型接口 |
| ASR_URL、ASR_MODEL | 云端语音识别 |
| EMBEDDING_BASE_URL、EMBEDDING_API_KEY、EMBEDDING_MODEL | 向量模型；独立 Key 留空时沿用 AI_API_KEY |

更换向量模型或维度时，使用新集合并重建索引，不得原地删除旧集合。应用启动时会检查集合维度。

## 从旧演示版升级

升级前备份旧数据库、原始媒体和报告。新版本不会读取 H2，也不会自动将本地文件当作 S3 已存在的对象。

- 全新部署：Flyway 从 V1 初始表结构开始，随后执行 V2 用户与发件箱迁移。
- 已有 MySQL：核对表结构与 V1 完全一致后，由运维明确将 Flyway baseline 设为版本 1，再运行 V2。默认不自动 baseline，以免错误接管其他库。
- 旧 H2：先将数据导出并核对导入 MySQL；不要直接将 H2 文件挂到新容器。
- 为旧 media_asset 的 owner_id 明确指定归属用户。未归属的数据默认不可访问。
- 上传原件为 assets/{assetId}/source{扩展名}；关键帧为 assets/{assetId}/frames/job-{jobId}/{文件名}；Word 为 assets/{assetId}/report/report-{jobId}.docx。核对文件大小与抽样下载后再更新数据库中的对象键。
- 历史 embedding 需重建到新的 Qdrant 集合；不要直接复用维度或模型不同的向量。
- 未完成的旧内存队列没有可迁移消息。核对记录后显式重试，使用新发件箱投递。
- 演示模式产生的历史报告可以保留归档，但不应当作新版本通过校验的报告。

这些步骤涉及现有数据，需在目标环境明确执行；代码升级不会自动修改旧 H2 文件或推送历史数据。

## 重试、死信与观察

job_outbox 的 PENDING 记录表示尚未获 Broker 确认。投递器按退避间隔持续重试；监控积压数量、最老记录年龄和 attempts。

消费失败由 RocketMQ 重投。三次重试后，failed_delivery 记录任务与消息 ID，Broker 原消息保留在 %DLQ%video-studio-workers。先修复模型、存储或配置，再通过任务重试接口重新投递；重试会递增 generation，因此旧消息不能启动新一代任务。

不要直接删除死信或将全部失败消息无差别重放。重复外部模型请求仍可能产生费用。

### 执行者失联后的自愈

消费者进程死掉时，消息已被确认、任务却停在 RUNNING，前端会一直等待。StaleJobReaper 每 60 秒扫描一次，同时满足以下两个条件才回收：

1. 状态是 RUNNING，且 updated_at 超过 worker.stale-minutes（默认 15 分钟）没有更新；
2. Redisson 的 worker:job:<id> 锁无人持有——锁在说明 worker 还活着（看门狗持续续期），只是某一步耗时较长。

回收动作是重新排队并递增 generation，任务会从断点继续，不会重跑已完成的转写与抽帧。自动回收次数上限由 worker.max-automatic-requeue（默认 3）控制，超过后任务置为 FAILED 并写明原因，交给人工判断，避免「一跑就崩」的任务反复烧钱。把 worker.stale-minutes 设为 0 可关闭自动回收，适用于锁不可靠、需要人工介入的环境。

### 指标

| 指标 | 用途 |
| --- | --- |
| videostudio_outbox_pending | 未被 Broker 确认的发件箱记录数 |
| videostudio_outbox_oldest_age_seconds | 最老未投递记录年龄，区分瞬时抖动与链路中断 |
| videostudio_failed_delivery_total | 重试耗尽后写入死信记录的任务数 |
| videostudio_jobs_requeued_total | 重新排队的任务数，tag reason=stale / gave_up |
| videostudio_jobs_active | 当前排队与运行中的任务数 |

/actuator/health/liveness 用于进程存活，/actuator/health/readiness 用于依赖就绪。/actuator/prometheus 仅管理员可访问。监控应覆盖队列积压、失败率、S3 写入失败、Qdrant 错误、模型延迟和日 token 用量。

## 本地验证

前端端到端用例（client/tests）默认在 CI 里用 npx playwright install --with-deps chromium 安装浏览器。本机若已有 Chrome，可跳过下载并指向它：

~~~sh
cd client
PLAYWRIGHT_CHROMIUM_EXECUTABLE="C:\Program Files\Google\Chrome\Application\chrome.exe" npm run test:e2e
~~~

用例拦截了 /api/** 的返回，不需要启动后端和中间件；它覆盖登录 CSRF、会话建立、退出、手机端布局与报告 HTML 的 XSS 清理。后端集成用例（ProductionPersistenceIntegrationTest）依赖 Testcontainers，只在 Docker 可用时执行，否则自动跳过。

## 备份与高可用

Compose 提供单节点部署，没有承诺高可用。正式环境应独立管理数据库备份与恢复演练、对象桶版本和生命周期、Qdrant 快照、Redis 持久化和 RocketMQ 副本。Broker 配置同步刷盘，但单 Broker 无法抵御主机或磁盘整体损坏。

对外部署时，数据库、Redis、S3、Qdrant 和 Broker 放在私有网络，跨主机连接按基础设施策略启用 TLS / ACL，并将凭证交给密钥管理服务。升级镜像前核对变更和验证结果；发布流水线可进一步锁定经审核的镜像 digest。
