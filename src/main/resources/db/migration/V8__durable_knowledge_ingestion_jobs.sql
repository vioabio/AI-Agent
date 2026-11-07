-- v1.1 N1：知识摄取耐久队列。
-- Job/Attempt/Outbox/DLQ 与候选 vector build 记录均由 Flyway 管理。
-- 业务正文不落在 Job 表；request_payload 只保存可重建的元数据快照。

CREATE TABLE IF NOT EXISTS knowledge_ingestion_job (
    id VARCHAR(96) PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL
        REFERENCES knowledge_source(id),
    content_checksum VARCHAR(128) NOT NULL,
    target_knowledge_release_id VARCHAR(64) NOT NULL
        REFERENCES knowledge_release(id),
    ingestion_contract_version VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    replay_generation INTEGER NOT NULL DEFAULT 0 CHECK (replay_generation >= 0),
    replay_of_job_id VARCHAR(96) REFERENCES knowledge_ingestion_job(id),
    request_payload JSONB NOT NULL
        CHECK (jsonb_typeof(request_payload) = 'object'),
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','RESERVED','PROCESSING','RETRY_WAIT','SUCCEEDED','DLQ')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 20),
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    result_batch_id VARCHAR(64) REFERENCES ingestion_batch(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (source_id, idempotency_key),
    UNIQUE (source_id, content_checksum, target_knowledge_release_id,
            ingestion_contract_version, replay_generation),
    CHECK ((status IN ('RESERVED','PROCESSING') AND lease_owner IS NOT NULL
            AND lease_expires_at IS NOT NULL)
           OR (status NOT IN ('RESERVED','PROCESSING') AND lease_owner IS NULL
            AND lease_expires_at IS NULL)),
    CHECK ((status = 'SUCCEEDED' AND result_batch_id IS NOT NULL)
           OR status <> 'SUCCEEDED'),
    CHECK (attempt_count <= max_attempts),
    CHECK ((status = 'RETRY_WAIT' AND next_attempt_at IS NOT NULL)
           OR status <> 'RETRY_WAIT')
);

CREATE TABLE IF NOT EXISTS knowledge_ingestion_attempt (
    id VARCHAR(96) PRIMARY KEY,
    job_id VARCHAR(96) NOT NULL REFERENCES knowledge_ingestion_job(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','ABANDONED')),
    error_class VARCHAR(128),
    error_code VARCHAR(128),
    error_message TEXT,
    backoff_ms BIGINT NOT NULL DEFAULT 0 CHECK (backoff_ms >= 0),
    jitter_ms BIGINT NOT NULL DEFAULT 0 CHECK (jitter_ms >= 0),
    lease_owner VARCHAR(128) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (job_id, attempt_no)
);

CREATE TABLE IF NOT EXISTS knowledge_ingestion_outbox (
    id VARCHAR(96) PRIMARY KEY,
    job_id VARCHAR(96) NOT NULL REFERENCES knowledge_ingestion_job(id) ON DELETE CASCADE,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CHECK ((status = 'PUBLISHED' AND published_at IS NOT NULL)
           OR (status <> 'PUBLISHED' AND published_at IS NULL))
);

CREATE TABLE IF NOT EXISTS knowledge_ingestion_dlq (
    id VARCHAR(96) PRIMARY KEY,
    job_id VARCHAR(96) NOT NULL REFERENCES knowledge_ingestion_job(id) ON DELETE CASCADE,
    original_checksum VARCHAR(128) NOT NULL,
    last_attempt_no INTEGER NOT NULL CHECK (last_attempt_no > 0),
    error_class VARCHAR(128) NOT NULL,
    error_code VARCHAR(128) NOT NULL,
    error_message TEXT NOT NULL,
    retention_until TIMESTAMPTZ NOT NULL,
    replayed_by VARCHAR(128),
    replay_reason TEXT,
    replay_job_id VARCHAR(96) REFERENCES knowledge_ingestion_job(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replayed_at TIMESTAMPTZ,
    UNIQUE (job_id),
    CHECK ((replayed_by IS NULL AND replay_reason IS NULL
            AND replay_job_id IS NULL AND replayed_at IS NULL)
           OR (replayed_by IS NOT NULL AND replay_reason IS NOT NULL
            AND replay_job_id IS NOT NULL AND replayed_at IS NOT NULL))
);

-- Embedding/index 构建记录与知识 Job 分开，候选失败不得改变 active 指针。
CREATE TABLE IF NOT EXISTS vector_build_job (
    id VARCHAR(96) PRIMARY KEY,
    vector_index_release_id VARCHAR(64) NOT NULL
        REFERENCES vector_index_release(id) ON DELETE CASCADE,
    embedding_model VARCHAR(128) NOT NULL,
    embedding_dimension INTEGER NOT NULL CHECK (embedding_dimension > 0),
    preprocessing_version VARCHAR(64) NOT NULL,
    index_profile VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','RESERVED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    total_chunks INTEGER NOT NULL DEFAULT 0 CHECK (total_chunks >= 0),
    processed_chunks INTEGER NOT NULL DEFAULT 0 CHECK (processed_chunks >= 0),
    failed_chunks INTEGER NOT NULL DEFAULT 0 CHECK (failed_chunks >= 0),
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (vector_index_release_id, embedding_model, embedding_dimension,
            preprocessing_version, index_profile),
    CHECK (processed_chunks + failed_chunks <= total_chunks)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_ingestion_job_claim
    ON knowledge_ingestion_job (status, next_attempt_at, lease_expires_at, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_ingestion_attempt_job
    ON knowledge_ingestion_attempt (job_id, attempt_no);
CREATE INDEX IF NOT EXISTS idx_knowledge_ingestion_outbox_pending
    ON knowledge_ingestion_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_ingestion_dlq_retention
    ON knowledge_ingestion_dlq (retention_until, created_at);
CREATE INDEX IF NOT EXISTS idx_vector_build_job_status
    ON vector_build_job (status, lease_expires_at, created_at);
