# API

所有业务 API 需要登录，并返回 { ok, data, message }。资源不存在或不属于当前用户时返回 404。修改请求必须携带 GET /api/auth/csrf 返回的 CSRF Header；会话使用 HttpOnly SESSION Cookie。

## 账户

- GET /api/auth/csrf：返回 token、headerName、registrationEnabled。
- POST /api/auth/login：{ username, password }；成功后轮换会话 ID，客户端重新取得 CSRF token。
- GET /api/auth/me：当前用户 id、username、role。
- POST /api/auth/logout：清除会话；客户端重新取得 CSRF token。
- POST /api/auth/register：仅部署启用注册时可用。用户名 3–64 位，密码 12–72 位。

## 管理员账户管理

- GET /api/admin/users：列出账户，仅 ADMIN 可用。
- POST /api/admin/users：{ username, password }，创建普通账户；注册关闭时仍可由管理员开通。不会返回密码哈希。

## 管理员：失败投递

重试耗尽的任务会写入 failed_delivery，原始消息由 Broker 留在死信队列。

- GET /api/admin/failed-deliveries?pendingOnly=true&limit=50：列出失败投递。默认只返回尚未重投过的记录。
- POST /api/admin/failed-deliveries/{id}/replay：重新投递该任务。递增 generation 并写发件箱，与用户重试走同一条状态机；同一条记录只能重投一次，重复调用返回 409。

重投不检查当日模型额度：死信清理不应该被额度挡住。原消息不重放——它带着旧代次，重放会被消费端忽略。

## 素材与上传

- GET /api/media?limit=50：仅当前用户的素材。
- POST /api/media：multipart file。
- GET /api/media/{id}、GET /api/media/{id}/jobs、DELETE /api/media/{id}。
- POST /api/uploads：{ uploadId, name, totalBytes }。服务端按用户命名空间隔离上传标识。
- PUT /api/uploads/{uploadId}/chunks/{index}：multipart file；分片长度必须符合会话定义。
- GET /api/uploads/{uploadId}、POST /api/uploads/{uploadId}/complete、DELETE /api/uploads/{uploadId}。
- GET /api/uploads/policy：当前 chunkSize。

## 任务

- POST /api/jobs：{ assetId, goal, mode, title }。任务和待投递记录同事务提交。
- GET /api/jobs?limit=50、GET /api/jobs/{id}。
- POST /api/jobs/{id}/cancel、POST /api/jobs/{id}/retry。
- POST /api/jobs/{id}/reanalyze：{ goal, mode }，创建新任务。
- DELETE /api/jobs/{id}：正在执行或停止中的任务需等待消费者释放执行锁后删除。
- GET /api/jobs/{id}/events?after=0、GET /api/jobs/{id}/stream?after=0：增量事件与 SSE。
- GET /api/jobs/{id}/transcript、GET /api/jobs/{id}/evidence。

## 报告与追问

- GET /api/jobs/{id}/report：报告信息。
- GET /api/jobs/{id}/report/markdown、GET /api/jobs/{id}/report/docx。
- GET /api/jobs/{id}/report/subtitle?format=srt：支持 srt、vtt、txt。
- POST /api/jobs/{id}/chat：{ question }；模型异常直接失败，不返回模板。
- GET /api/jobs/{id}/chat、DELETE /api/jobs/{id}/chat。
- GET /api/jobs/{id}/frames/{seq}：证据帧。
- GET /api/media/{id}/stream、/download、/thumbnail、/frame、/info。

## 运维

/api/jobs/runtime 返回当前已配置的模型和存储类型，不包含密钥。/actuator/health/readiness 返回依赖就绪情况。/actuator/prometheus 需要管理员身份。
