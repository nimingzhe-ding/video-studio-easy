ALTER TABLE failed_delivery
    ADD COLUMN replayed_at DATETIME(3) NULL,
    ADD COLUMN replayed_by BIGINT NULL,
    ADD KEY idx_failed_delivery_pending (replayed_at, created_at);
