-- N3：用户级长期记忆持久化。
-- 正文只保存 AES-GCM 密文；所有读取、删除和导出必须按 user_id 过滤。
-- 加密密钥由环境变量/Secret Store 注入，不进入数据库或迁移文件。

CREATE TABLE IF NOT EXISTS memory_entry (
    memory_id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(64),
    type VARCHAR(32) NOT NULL,
    content_digest CHAR(64) NOT NULL,
    ciphertext TEXT NOT NULL,
    key_version VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128),
    source_message_id VARCHAR(128),
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    consent_id VARCHAR(128) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (expires_at > effective_from),
    CHECK (length(content_digest) = 64),
    CHECK (length(consent_id) > 0)
);

CREATE INDEX IF NOT EXISTS idx_memory_entry_user_active
    ON memory_entry (user_id, effective_from, expires_at, created_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_memory_entry_expiry
    ON memory_entry (expires_at)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_memory_entry_user_conversation
    ON memory_entry (user_id, conversation_id, created_at DESC);
