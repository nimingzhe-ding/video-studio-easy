CREATE TABLE app_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(16) NOT NULL DEFAULT 'USER',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Legacy rows remain unassigned until an operator explicitly claims them.
ALTER TABLE media_asset ADD COLUMN owner_id BIGINT NULL, ADD KEY idx_media_owner (owner_id,id),
 ADD CONSTRAINT fk_media_owner FOREIGN KEY (owner_id) REFERENCES app_user(id);
CREATE TABLE job_outbox (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 job_id BIGINT NOT NULL,
 generation INT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 sent_at DATETIME(3) NULL,
 last_error VARCHAR(512) NULL,
 UNIQUE KEY uk_dispatch (job_id,generation),
 KEY idx_outbox_pending (status,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE analysis_job ADD COLUMN generation INT NOT NULL DEFAULT 1;
CREATE TABLE failed_delivery (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 job_id BIGINT NOT NULL,
 generation INT NOT NULL,
 message_id VARCHAR(128) NOT NULL,
 reason VARCHAR(512) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_failed_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
