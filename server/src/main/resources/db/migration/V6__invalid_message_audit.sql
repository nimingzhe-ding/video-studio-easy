ALTER TABLE failed_delivery
    ADD COLUMN failure_kind VARCHAR(24) NOT NULL DEFAULT 'TASK_FAILURE',
    ADD COLUMN payload_sha256 CHAR(64) NULL,
    ADD COLUMN resolved_at DATETIME(3) NULL,
    ADD COLUMN resolved_by BIGINT NULL;

ALTER TABLE failed_delivery
    DROP INDEX idx_failed_delivery_pending,
    ADD KEY idx_failed_delivery_pending (replayed_at, resolved_at, created_at);
