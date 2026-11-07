CREATE TABLE storage_cleanup (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 kind VARCHAR(24) NOT NULL,
 object_key VARCHAR(1024) NOT NULL,
 reference_id BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(3) NOT NULL,
 lease_token VARCHAR(36) NULL,
 last_error VARCHAR(512) NULL,
 created_at DATETIME(3) NOT NULL,
 finished_at DATETIME(3) NULL,
 KEY idx_cleanup_pending (status,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
