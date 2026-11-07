-- M2：知识来源、文档版本、切块和候选索引生命周期
-- 该迁移只增加新表，不修改 M1 表；候选版本发布通过 active_index_release 指针完成。

CREATE TABLE IF NOT EXISTS knowledge_source (
    id VARCHAR(64) PRIMARY KEY,
    source_url TEXT NOT NULL,
    title VARCHAR(256),
    license VARCHAR(128) NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    owner_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_document (
    id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL REFERENCES knowledge_source(id),
    canonical_key VARCHAR(512) NOT NULL,
    language VARCHAR(16) NOT NULL,
    game_release_id VARCHAR(64) REFERENCES game_release(id),
    battle_format_id VARCHAR(64),
    ruleset_version_id VARCHAR(64) REFERENCES ruleset_version(id),
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_id, canonical_key)
);

CREATE TABLE IF NOT EXISTS document_version (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    version_no VARCHAR(64) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    content_version VARCHAR(64),
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retrieved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (document_id, version_no),
    UNIQUE (checksum)
);

CREATE TABLE IF NOT EXISTS document_chunk (
    id VARCHAR(128) PRIMARY KEY,
    document_version_id VARCHAR(64) NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    chunk_no INTEGER NOT NULL CHECK (chunk_no >= 0),
    content TEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding_model VARCHAR(128),
    embedding_dimension INTEGER CHECK (embedding_dimension IS NULL OR embedding_dimension > 0),
    embedding_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_version_id, chunk_no),
    UNIQUE (document_version_id, content_hash)
);

CREATE TABLE IF NOT EXISTS ingestion_batch (
    id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL REFERENCES knowledge_source(id),
    idempotency_key VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    chunk_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding_model VARCHAR(128),
    accepted_count INTEGER NOT NULL DEFAULT 0 CHECK (accepted_count >= 0),
    rejected_count INTEGER NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (source_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS knowledge_release (
    id VARCHAR(64) PRIMARY KEY,
    release_no VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    parser_version VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(128),
    data_release_id VARCHAR(64) REFERENCES data_release(id),
    sample_size INTEGER NOT NULL DEFAULT 0 CHECK (sample_size >= 0),
    recall_at_5 NUMERIC(8, 5),
    citation_accuracy NUMERIC(8, 5),
    groundedness NUMERIC(8, 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS vector_index_release (
    id VARCHAR(64) PRIMARY KEY,
    knowledge_release_id VARCHAR(64) NOT NULL REFERENCES knowledge_release(id) ON DELETE CASCADE,
    vector_store VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension > 0),
    status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    index_checksum VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

-- 单行指针用于原子切换 active index；候选索引评测失败时不影响旧指针。
CREATE TABLE IF NOT EXISTS active_index_release (
    singleton_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton_id = TRUE),
    vector_index_release_id VARCHAR(64) REFERENCES vector_index_release(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 业务 chunk 目录与 Spring AI PGVector 表通过 chunk id/metadata 关联。
-- 由 Flyway 管理结构，避免应用启动时隐式删建或改变已发布索引。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS document_chunk_vector (
    id TEXT PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(1536) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_knowledge_document_version_filter
    ON knowledge_document (game_release_id, battle_format_id, ruleset_version_id, language);
CREATE INDEX IF NOT EXISTS idx_document_version_review_status
    ON document_version (review_status, published_at);
CREATE INDEX IF NOT EXISTS idx_document_chunk_version_no
    ON document_chunk (document_version_id, chunk_no);
CREATE INDEX IF NOT EXISTS idx_ingestion_batch_status
    ON ingestion_batch (status, started_at);

-- PostgreSQL FTS 的生成列在旧版本兼容性不一致，使用表达式索引避免改变原文。
CREATE INDEX IF NOT EXISTS idx_document_chunk_fts
    ON document_chunk USING GIN (to_tsvector('simple', content));
CREATE INDEX IF NOT EXISTS idx_document_chunk_vector_embedding
    ON document_chunk_vector USING hnsw (embedding vector_cosine_ops);
