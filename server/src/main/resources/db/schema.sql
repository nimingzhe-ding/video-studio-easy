-- video-studio-easy schema
--
-- 这份 DDL 只写 MySQL 方言（LONGTEXT / DATETIME(3) / ENGINE=InnoDB / KEY 索引），
-- 本地 H2 用 MODE=MySQL 也能完整执行（已验证 10 张表 + 索引 + 自增主键一致）。
-- 保持单一方言而不是两套脚本，是因为两边一旦分叉，容器里才暴露的字段差异最难查。

CREATE TABLE IF NOT EXISTS media_asset (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(512)  NOT NULL,
    kind          VARCHAR(16)   NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    duration_ms   BIGINT        NOT NULL DEFAULT 0,
    width         INT NULL,
    height        INT NULL,
    fps           DOUBLE NULL,
    video_codec   VARCHAR(64) NULL,
    audio_codec   VARCHAR(64) NULL,
    storage_path  VARCHAR(1024) NOT NULL,
    audio_path    VARCHAR(1024) NULL,
    frame_dir     VARCHAR(1024) NULL,
    meta_json     LONGTEXT NULL,
    created_at    DATETIME(3)   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS analysis_job (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    mode          VARCHAR(32)   NOT NULL,
    mode_source   VARCHAR(16) NULL,
    mode_reason   VARCHAR(255) NULL,
    title         VARCHAR(512) NULL,
    focus         LONGTEXT NULL,
    status        VARCHAR(16)   NOT NULL,
    stage         VARCHAR(32)   NOT NULL,
    progress      INT           NOT NULL DEFAULT 0,
    error         LONGTEXT NULL,
    degraded      BOOLEAN       NOT NULL DEFAULT FALSE,
    notices       LONGTEXT NULL,
    created_at    DATETIME(3)   NOT NULL,
    updated_at    DATETIME(3)   NOT NULL,
    finished_at   DATETIME(3) NULL,
    KEY idx_job_asset (asset_id, id),
    KEY idx_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS job_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT        NOT NULL,
    seq           BIGINT        NOT NULL,
    stage         VARCHAR(32)   NOT NULL,
    type          VARCHAR(24)   NOT NULL,
    level         VARCHAR(16)   NOT NULL,
    progress      INT           NOT NULL DEFAULT 0,
    message       LONGTEXT NULL,
    payload_json  LONGTEXT NULL,
    created_at    DATETIME(3)   NOT NULL,
    KEY idx_job_event_job (job_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS transcript_segment (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    job_id        BIGINT        NOT NULL,
    seq           INT           NOT NULL,
    start_ms      BIGINT        NOT NULL,
    end_ms        BIGINT        NOT NULL,
    text          LONGTEXT      NOT NULL,
    speaker       VARCHAR(64) NULL,
    confidence    DOUBLE NULL,
    source        VARCHAR(32)   NOT NULL,
    KEY idx_transcript_job (job_id, start_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS visual_evidence (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    job_id        BIGINT        NOT NULL,
    seq           INT           NOT NULL,
    time_ms       BIGINT        NOT NULL,
    frame_path    VARCHAR(1024) NULL,
    ocr_text      LONGTEXT NULL,
    caption       LONGTEXT NULL,
    hash          VARCHAR(32) NULL,
    source        VARCHAR(32)   NOT NULL,
    KEY idx_visual_job (job_id, time_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS context_chunk (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    job_id        BIGINT        NOT NULL,
    chunk_index   INT           NOT NULL,
    start_ms      BIGINT        NOT NULL,
    end_ms        BIGINT        NOT NULL,
    summary       LONGTEXT NULL,
    keywords      LONGTEXT NULL,
    visual_keywords LONGTEXT NULL,
    transcript_text LONGTEXT NULL,
    ocr_text      LONGTEXT NULL,
    embedding     LONGTEXT NULL,
    embedding_model VARCHAR(64) NULL,
    KEY idx_chunk_job (job_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS analysis_report (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT        NOT NULL,
    asset_id      BIGINT        NOT NULL,
    title         VARCHAR(512)  NOT NULL,
    mode          VARCHAR(32)   NOT NULL,
    markdown      LONGTEXT      NOT NULL,
    outline_json  LONGTEXT NULL,
    trace_json    LONGTEXT NULL,
    docx_path     VARCHAR(1024) NULL,
    created_at    DATETIME(3)   NOT NULL,
    KEY idx_report_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_message (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT        NOT NULL,
    role          VARCHAR(16)   NOT NULL,
    content       LONGTEXT      NOT NULL,
    citations_json LONGTEXT NULL,
    created_at    DATETIME(3)   NOT NULL,
    KEY idx_chat_job (job_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 分片上传会话：断点续传靠它记录「哪些片已经收到」
CREATE TABLE IF NOT EXISTS upload_session (
    upload_id     VARCHAR(128) PRIMARY KEY,
    name          VARCHAR(512) NOT NULL,
    total_bytes   BIGINT       NOT NULL,
    total_chunks  INT          NOT NULL,
    chunk_size    INT          NOT NULL,
    received      INT          NOT NULL DEFAULT 0,
    asset_id      BIGINT NULL,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    KEY idx_upload_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 分片本身也落库：进程重启后仍能判断某一片是否真的写过（避免只信任内存计数）
CREATE TABLE IF NOT EXISTS upload_chunk (
    upload_id     VARCHAR(128) NOT NULL,
    chunk_index   INT          NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (upload_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
