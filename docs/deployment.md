# 部署与中间件

这份文档说明两条运行路径、每个中间件的作用与降级行为，以及容器部署时的注意事项。

## 1. 两条路径，同一份代码

| | 单机模式（默认） | 容器模式 |
| --- | --- | --- |
| 启动 | `cd server && mvn spring-boot:run` | `docker compose up -d --build` |
| 数据库 | H2 文件库（`MODE=MySQL`） | MySQL 8.4 |
| 对象存储 | 本地目录 `data/media` | MinIO |
| 向量检索 | 内存余弦 | Milvus |
| 缓存 | 无 | Redis |
| 外部依赖 | 只要 ffmpeg / whisper / tesseract | 六个容器 |

切换只靠环境变量，代码里没有任何 `if (profile == ...)` 之类的分支。

## 2. 为什么三个中间件都做了「接口 + 两个实现」

`storage/ObjectStore`、`retrieval/VectorStore` 都是接口，各有本地实现与中间件实现：

```
ObjectStore   ← LocalObjectStore（空实现，数据就在本地盘）
              ← MinioObjectStore（写入桶，读取时按需拉回本地）

VectorStore   ← MemoryVectorStore（逐块算余弦）
              ← MilvusVectorStore（HNSW 索引，按 job_id 过滤）
```

选择实现的地方是 `ObjectStoreConfig` / `VectorStoreConfig` 两个 `@Bean`，未启用时**连客户端都不创建**
——不创建客户端就不会在启动时白白多一次网络超时。

这么分的理由不是「架构洁癖」，而是三个具体的约束：

1. **ffmpeg / whisper / tesseract 只能吃本地文件路径**。所以本地盘不是「临时目录」，它是工作区；
   对象存储是持久层，负责源文件、入库关键帧、Word 报告的备份与跨机器共享。
2. **单素材的分块量级在几百**。10 小时课程按 5 分钟切也就 120 块，
   内存里算余弦是毫秒级；换 Milvus 真正买到的是「读 top-k 而不是读全部」。
3. **要把「装不装向量库」变成部署期决定**，而不是写死在代码里。

## 3. 降级行为（重要）

三个开关打开但中间件连不上时，行为如下——**都会打警告，都不会让服务起不来**：

| 场景 | 表现 |
| --- | --- |
| MinIO 连不上 | 启动时探测 bucket 失败即把 `ready=false`，之后所有 `put` 直接返回 false；本地盘照常工作 |
| Milvus 连不上 | `semanticScores` 返回空表，`HybridRetriever` 的语义分退化为 0，只靠关键词打分 |
| Redis 连不上 | 读缓存失败即回落数据库（缓存只放可重建数据） |
| MySQL 连不上 | **不降级**——数据库是唯一真相源，起不来就该失败 |

容器编排里中间件比应用晚就绪是常态，所以除了数据库，其余都允许「先起来、后重连」。

## 4. 容器部署

```bash
cp .env.example .env       # 至少改掉 MYSQL_PASSWORD 与 MINIO_ROOT_PASSWORD
docker compose up -d --build
```

启动顺序由 `depends_on` + healthcheck 控制：MySQL 与 MinIO 是 `service_healthy`，
其余是 `service_started`（Milvus 自己会重试连 etcd 与 MinIO）。

镜像构建分四个阶段：

1. `client`：Node 22 构建前端，产出静态文件；
2. `server`：Maven 构建后端 jar；
3. `whisper`：从源码编译 whisper.cpp（约 3-6 分钟）；
4. 运行镜像：JRE 21 + ffmpeg + tesseract（含 `chi_sim`）+ 中文字体 + 前三个阶段的产物。

### 4.1 whisper 模型不进镜像

`ggml-small.bin` 约 465MB，而且换模型不该触发重新构建，所以：

- 模型放在 `/data/models`（`app-data` 卷）；
- 首次启动时 `docker/entrypoint.sh` 按 `WHISPER_MODEL_URL` 下载；
- 下载失败只告警，服务以「无本地 ASR」状态继续启动（云端 ASR 或纯画面分析仍可用）。

### 4.2 MinIO 镜像的坑

**MinIO 官方镜像已经从 Docker Hub 下架**：`minio/minio`、`quay.io/minio/minio`、`bitnami/minio`
在 2026 年都返回 `manifest unknown` 或 `access denied`。`elestio/minio` 能拉但只有 x86 架构
（在 arm64 上直接 `Exec format error`）。

所以 compose 里默认用社区维护的 `pgsty/minio`，它带 `/usr/bin/mc`，healthcheck 可以用
`mc ready local`。如果换成别的镜像，记得同步改 healthcheck。

### 4.3 Milvus 的必需参数

Milvus standalone 不自带存储，必须显式告诉它 etcd 与 MinIO 在哪，否则起不来：

```yaml
environment:
  ETCD_ENDPOINTS: etcd:2379
  MINIO_ADDRESS: minio:9000
  MINIO_ACCESS_KEY_ID: ...
  MINIO_SECRET_ACCESS_KEY: ...
  COMMON_STORAGETYPE: minio
```

典型的报错是 `failed to check blob bucket exist` 与 `find no available datacoord`。

## 5. 只维护一份 schema

`server/src/main/resources/db/schema.sql` 是纯 MySQL 方言（`LONGTEXT` / `DATETIME(3)` /
`ENGINE=InnoDB` / `DEFAULT CHARSET=utf8mb4` / 内联 `KEY`）。

这份文件在 H2（`MODE=MySQL`）与真实 MySQL 8.4 上**都能建表**——已逐张验证：
10 张表、索引、自增主键、`BOOLEAN`、`DATETIME(3)`、中文写入全部正常。
所以不存在「开发用 H2 脚本、生产用 MySQL 脚本」这种迟早会漂移的双份维护。

`config/SchemaUpgrade.java` 负责给已存在的库补列（`ALTER TABLE ... ADD COLUMN`），
因为 `CREATE TABLE IF NOT EXISTS` 不会修改已有表。它读 JDBC 元数据判断列是否存在时，
同时尝试原样与大写表名——H2 把标识符存成大写，MySQL 存成小写。

## 6. 实测记录

在 arm64 的 Ubuntu 24.04 虚拟机上验证过：

- 六个容器全部起来，宿主可直连 3306 / 6379 / 9000 / 19530；
- 应用连 MySQL，启动时 10 张表自动建好；
- 上传 76 秒视频跑完整条流水线：转写 3 段、关键帧 4 条、分块 1 个；
- Milvus 首次写入时按实际向量维度（16）建集合，检索时按 `job_id` 过滤；
- MinIO 桶里能看到 `assets/1/source.mp4`、`assets/1/frames/job-1/*.jpg`、`assets/1/report/report-1.docx`；
- 删除任务后，该任务在桶里的关键帧与报告被一并清理（`deleteByPrefix`）。
