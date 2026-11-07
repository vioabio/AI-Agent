-- M2：把候选索引与具体 chunk 建立显式血缘，供 active_index_release 过滤。
-- 采用关联表而不是在 document_chunk 上覆盖 release_id：同一内容版本可以被
-- 多个候选索引复用，旧索引回滚时不会丢失其可检索边界。

CREATE TABLE IF NOT EXISTS knowledge_release_document_version (
    knowledge_release_id VARCHAR(64) NOT NULL
        REFERENCES knowledge_release(id) ON DELETE CASCADE,
    document_version_id VARCHAR(64) NOT NULL
        REFERENCES document_version(id) ON DELETE CASCADE,
    PRIMARY KEY (knowledge_release_id, document_version_id)
);

CREATE TABLE IF NOT EXISTS vector_index_release_chunk (
    vector_index_release_id VARCHAR(64) NOT NULL
        REFERENCES vector_index_release(id) ON DELETE CASCADE,
    document_chunk_id VARCHAR(128) NOT NULL
        REFERENCES document_chunk(id) ON DELETE CASCADE,
    PRIMARY KEY (vector_index_release_id, document_chunk_id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_release_version_document
    ON knowledge_release_document_version (document_version_id, knowledge_release_id);

CREATE INDEX IF NOT EXISTS idx_vector_index_release_chunk_chunk
    ON vector_index_release_chunk (document_chunk_id, vector_index_release_id);
