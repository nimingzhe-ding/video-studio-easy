# Video Studio

将长视频和长音频整理为带时间戳证据的结构化报告，支持素材管理、转写、画面文字识别、报告追问、Word 与字幕导出。

当前代码采用统一的服务端部署架构。MySQL、Redis、RocketMQ、S3 兼容对象存储、Qdrant 和真实模型接口均为运行依赖。没有 H2 模式、内存向量检索、离线报告模板或本地 Whisper 回退路径。依赖不可用、模型输出不合法或证据校验失败时，任务明确失败并保留错误信息。

## 技术栈

| 层次 | 技术 |
|---|---|
| 前端 | Vue 3、Vite 8、Marked、DOMPurify |
| 后端 | Java 21、Spring Boot 3.5、Spring JDBC、Spring Security |
| 账户 | BCrypt 密码、Redis 共享会话、HttpOnly Cookie、CSRF、用户资源隔离 |
| 数据库 | MySQL 8.4，Flyway 版本化迁移 |
| 任务 | RocketMQ 5.3.4，MySQL 事务发件箱，Redisson 消费互斥、重试与死信 |
| 文件 | S3 兼容对象存储；Compose 使用固定版本的 PGSTY Silo |
| 检索 | Qdrant，Embedding＋关键词＋画面文字混合检索 |
| 媒体 | 服务端 FFmpeg、ffprobe、Tesseract；云端 ASR |
| 模型 | DashScope 兼容接口，默认 Qwen；可配置兼容的模型服务 |
| 运维 | Actuator、Micrometer、Prometheus 指标、容器健康检查 |

## 部署

要求 Docker Compose v2，构建环境使用 JDK 21 和 Node.js 22.12 以上。

1. 将 .env.example 复制为 .env，填写独立的数据库、Redis、对象存储、Qdrant 和管理员凭证，以及真实 AI_API_KEY。
2. 生产入口配置 HTTPS 反向代理，保持 SESSION_COOKIE_SECURE=true。仅本机 HTTP 验证时显式设为 false。
3. 执行：

~~~sh
docker compose config --quiet
docker compose up -d --build
~~~

默认应用仅发布在 127.0.0.1:9090。数据库、消息队列和对象存储端口不发布到主机。首次启动创建管理员，注册默认关闭。

Compose 是可部署的单节点拓扑，不代表高可用集群。正式环境可将相同配置指向托管 MySQL、Redis、S3、Qdrant 与 RocketMQ。具体配置、备份、死信处理和旧数据迁移见 [部署说明](docs/deployment.md)。

## 任务可靠性

提交分析时，任务与发件箱记录在同一个 MySQL 事务内写入。后台只有收到 RocketMQ 的发送确认后才标记已发送。发送失败会保留待投递记录并退避重试。

消费者按任务加带自动续期的分布式锁，忽略已完成、已取消及旧重试代次的消息。失败使用 RocketMQ 的有限重投，耗尽后保留原始消息到死信队列，并记录 failed_delivery。手动重试沿用任务 ID、递增代次并复用已持久化的转写与画面证据。

执行者进程中途消失时，消息已被确认、任务却停在 RUNNING。定时清理器以「超过 15 分钟没有进度更新 + 分布式锁无人持有」两个条件识别这类任务，重新排队并从断点继续；自动回收次数有上限，超过后置为失败交由人工处理。

系统采用至少一次投递。进程在外部模型调用之后、结果持久化之前退出时，模型调用可能重复，不能将其视为外部计费的恰好一次保证。

## 数据与临时文件

媒体原件、上传分片、关键帧和 Word 报告以对象存储为持久来源，数据库保存对象键。API 与消费者无需共享宿主机数据目录。

FFmpeg 和 Tesseract 仍需临时文件路径；这些工作副本放在 WORK_ROOT，容器使用临时文件系统，后台回收超过 24 小时且无活动任务的媒体工作目录。它们不是持久数据的替代存储。

## 验证

~~~sh
cd server
mvn verify
cd ../client
npm ci
npm test
npm run build
npm run test:e2e
cd ../extension
node --test detect.test.js
~~~

后端覆盖鉴权、CSRF、越权访问、队列投递与重复消费、模型失败、索引错误、证据校验边界和预算控制。MySQL / Redis 集成测试需要 Docker；无法连接 Docker 时会显式跳过。

前端有单测与浏览器测试两层：单测跑纯函数（格式化、时间戳解析、上传标识派生、模式标签），不需要浏览器；浏览器测试通过测试专用 HTTP 响应检查登录与会话流程，不调用模型服务。扩展的链接识别规则同样有单测。

真实环境的报告样例见 [docs/samples/](docs/samples/)。

文档：[架构](docs/architecture.md) · [API](docs/api.md) · [部署](docs/deployment.md) · [与 DOVideo-AI 的对比](docs/comparison.md) · [真实报告样例](docs/samples/)
