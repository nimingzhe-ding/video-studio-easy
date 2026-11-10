-- API 令牌与 Webhook 订阅。

-- 只存令牌的 SHA-256 摘要：服务端被拖库时，明文令牌不可还原。
-- prefix 保留前几位用于「这是哪个令牌」的人工识别，长度不足以还原原文。
CREATE TABLE api_token (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    name         VARCHAR(128) NOT NULL,
    prefix       VARCHAR(16)  NOT NULL,
    token_hash   CHAR(64)     NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    last_used_at DATETIME(3)  NULL,
    revoked_at   DATETIME(3)  NULL,
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_token_user (user_id, id),
    CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Webhook：任务到达终态时回调。secret 用于 HMAC 签名，
-- 接收方据此确认请求确实来自本服务，而不是任意构造的 POST。
CREATE TABLE webhook (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    url         VARCHAR(1024) NOT NULL,
    secret      VARCHAR(128) NOT NULL,
    events      VARCHAR(128) NOT NULL DEFAULT 'job.succeeded,job.failed',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(3)  NOT NULL,
    KEY idx_webhook_user (user_id, id),
    KEY idx_webhook_enabled (enabled),
    CONSTRAINT fk_webhook_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
