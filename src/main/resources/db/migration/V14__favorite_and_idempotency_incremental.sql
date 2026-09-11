-- N2：用户收藏与通用幂等记录。相对 V1/V3 只做兼容增量，不重复创建既有表。

CREATE TABLE IF NOT EXISTS favorite (
    id BIGSERIAL PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_key VARCHAR(256) NOT NULL,
    label VARCHAR(256),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, resource_type, resource_key)
);

CREATE INDEX IF NOT EXISTS idx_favorite_owner_created
    ON favorite (owner_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS api_idempotency_record (
    scope VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_status INTEGER NOT NULL CHECK (response_status BETWEEN 100 AND 599),
    response_body JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_api_idempotency_expiry
    ON api_idempotency_record (expires_at);
