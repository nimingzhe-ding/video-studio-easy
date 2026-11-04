-- video-studio-easy schema (H2 / MySQL 兼容写法)
CREATE TABLE IF NOT EXISTS media_asset (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(512)  NOT NULL,
    kind          VARCHAR(16)   NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    duration_ms   BIGINT        NOT NULL DEFAULT 0,
    width         INT,
    height        INT,
    fps           DOUBLE,
    video_codec   VARCHAR(64),
    audio_codec   VARCHAR(64),
    storage_path  VARCHAR(1024) NOT NULL,
    audio_path    VARCHAR(1024),
    frame_dir     VARCHAR(1024),
    meta_json     CLOB,
    created_at    TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS analysis_job (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    mode          VARCHAR(32)   NOT NULL,
    title         VARCHAR(512),
    focus         CLOB,
    status        VARCHAR(16)   NOT NULL,
    stage         VARCHAR(32)   NOT NULL,
    progress      INT           NOT NULL DEFAULT 0,
    error         CLOB,
    degraded      BOOLEAN       NOT NULL DEFAULT FALSE,
    notices       CLOB,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL,
    finished_at   TIMESTAMP
);

CREATE TABLE IF NOT EXISTS job_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT        NOT NULL,
    seq           BIGINT        NOT NULL,
    stage         VARCHAR(32)   NOT NULL,
    type          VARCHAR(24)   NOT NULL,
    level         VARCHAR(16)   NOT NULL,
    progress      INT           NOT NULL DEFAULT 0,
    message       CLOB,
    payload_json  CLOB,
    created_at    TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_job_event_job ON job_event (job_id, seq);

CREATE TABLE IF NOT EXISTS transcript_segment (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    job_id        BIGINT        NOT NULL,
    seq           INT           NOT NULL,
    start_ms      BIGINT        NOT NULL,
    end_ms        BIGINT        NOT NULL,
    text          CLOB          NOT NULL,
    speaker       VARCHAR(64),
    confidence    DOUBLE,
    source        VARCHAR(32)   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_transcript_job ON transcript_segment (job_id, start_ms);

CREATE TABLE IF NOT EXISTS visual_evidence (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    job_id        BIGINT        NOT NULL,
    seq           INT           NOT NULL,
    time_ms       BIGINT        NOT NULL,
    frame_path    VARCHAR(1024),
    ocr_text      CLOB,
    caption       CLOB,
    hash          VARCHAR(32),
    source        VARCHAR(32)   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_visual_job ON visual_evidence (job_id, time_ms);

CREATE TABLE IF NOT EXISTS context_chunk (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id      BIGINT        NOT NULL,
    job_id        BIGINT        NOT NULL,
    chunk_index   INT           NOT NULL,
    start_ms      BIGINT        NOT NULL,
    end_ms        BIGINT        NOT NULL,
    summary       CLOB,
    keywords      CLOB,
    visual_keywords CLOB,
    transcript_text CLOB,
    ocr_text      CLOB,
    embedding     CLOB,
    embedding_model VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_chunk_job ON context_chunk (job_id, chunk_index);

CREATE TABLE IF NOT EXISTS analysis_report (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT        NOT NULL,
    asset_id      BIGINT        NOT NULL,
    title         VARCHAR(512)  NOT NULL,
    mode          VARCHAR(32)   NOT NULL,
    markdown      CLOB          NOT NULL,
    outline_json  CLOB,
    trace_json    CLOB,
    docx_path     VARCHAR(1024),
    created_at    TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_report_job ON analysis_report (job_id);

CREATE TABLE IF NOT EXISTS chat_message (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT        NOT NULL,
    role          VARCHAR(16)   NOT NULL,
    content       CLOB          NOT NULL,
    citations_json CLOB,
    created_at    TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_job ON chat_message (job_id, id);

-- 分片上传会话：断点续传靠它记录「哪些片已经收到」
CREATE TABLE IF NOT EXISTS upload_session (
    upload_id     VARCHAR(128) PRIMARY KEY,
    name          VARCHAR(512) NOT NULL,
    total_bytes   BIGINT       NOT NULL,
    total_chunks  INT          NOT NULL,
    chunk_size    INT          NOT NULL,
    received      INT          NOT NULL DEFAULT 0,
    asset_id      BIGINT,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_upload_updated ON upload_session (updated_at);

-- 分片本身也落库：进程重启后仍能判断某一片是否真的写过（避免只信任内存计数）
CREATE TABLE IF NOT EXISTS upload_chunk (
    upload_id     VARCHAR(128) NOT NULL,
    chunk_index   INT          NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    PRIMARY KEY (upload_id, chunk_index)
);
