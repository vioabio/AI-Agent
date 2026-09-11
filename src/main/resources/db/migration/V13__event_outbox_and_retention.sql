-- N2：事件 outbox 与保留策略。
-- 状态/事件/checkpoint/outbox 应由应用事务一起提交；publisher 采用 at-least-once。

CREATE TABLE IF NOT EXISTS agent_event_outbox (
    outbox_id VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    event_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    last_error TEXT,
    claimed_by VARCHAR(128),
    claim_expires_at TIMESTAMPTZ,
    UNIQUE (run_id, event_id, event_type),
    CONSTRAINT agent_event_outbox_status_check CHECK
        (status IN ('PENDING','CLAIMED','PUBLISHED','FAILED','DLQ'))
);

CREATE INDEX IF NOT EXISTS idx_agent_event_outbox_pending
    ON agent_event_outbox (status, next_attempt_at, created_at);

-- 兼容已存在的 N2 表：领取者租约用于防止错误 worker 回写及崩溃后永久卡死。
ALTER TABLE agent_event_outbox ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);
ALTER TABLE agent_event_outbox ADD COLUMN IF NOT EXISTS claim_expires_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_agent_event_outbox_claim_expiry
    ON agent_event_outbox (status, claim_expires_at);

CREATE TABLE IF NOT EXISTS agent_event_retention (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE,
    retention_class VARCHAR(64) NOT NULL,
    retained_from_event_id BIGINT NOT NULL DEFAULT 1 CHECK (retained_from_event_id >= 1),
    expires_at TIMESTAMPTZ,
    last_cleanup_at TIMESTAMPTZ,
    deleted_event_count BIGINT NOT NULL DEFAULT 0 CHECK (deleted_event_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_event_retention_expiry
    ON agent_event_retention (expires_at);
