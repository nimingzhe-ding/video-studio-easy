# 与 DOVideo-AI 的当前差异

本文描述生产化改造后的代码，不再沿用演示版“缺少中间件”的对比。

| 方面 | DOVideo-AI（df4fa83） | Video Studio 当前实现 |
|---|---|---|
| 主框架 | Java 21、Spring Boot、Vue 3 | 同类主框架 |
| 数据访问 | MyBatis-Plus、MySQL、Flyway | Spring JDBC、MySQL、Flyway |
| 身份 | 自写密码与 Redis 会话 | Spring Security、BCrypt、Redis Session、CSRF |
| 队列 | RocketMQ | RocketMQ＋事务发件箱 |
| 向量检索 | Qdrant | Qdrant |
| 对象存储 | MinIO | S3 兼容接口，Compose 使用 PGSTY Silo |
| 模型接入 | LangChain4j＋硅基流动默认模型 | JDK HTTP 网关＋DashScope 默认模型 |
| 链接导入 | yt-dlp、Chrome 扩展 | 目前为文件与分片上传 |
| 文档产出 | 网页结构化分析 | Markdown、Word 插图、SRT/VTT/TXT |
| 说话人标注 | 未实现 | 音高聚类近似分离 |
| 默认失败行为 | 有向量检索降级策略 | 不允许模型模板或内存检索替代失败服务 |

基础链路都包含时间轴证据、混合检索、Planner / Executor / Critic 和确定性校验。当前代码不依赖 Spring AI，也没有声称 JDK HTTP 客户端等同于完整 Agent 框架。

DOVideo-AI 来源：[仓库](https://github.com/nimingzhe-ding/DOVideo-AI/tree/df4fa83)。
