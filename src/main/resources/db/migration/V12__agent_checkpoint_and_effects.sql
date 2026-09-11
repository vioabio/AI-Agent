-- N2：Run checkpoint、单 owner 租约、工具副作用账本和可重放模型调用缓存。
-- 只追加新表，不改写已发布的 Run/事件事实；所有幂等字段显式 NOT NULL。

CREATE TABLE IF NOT EXISTS agent_checkpoint (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL CHECK (step_no >= 0),
    executor_version VARCHAR(128) NOT NULL,
    context_hash VARCHAR(128) NOT NULL,
    last_event_id BIGINT NOT NULL DEFAULT 0 CHECK (last_event_id >= 0),
    next_action VARCHAR(256) NOT NULL,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (run_id, step_no)
);

CREATE INDEX IF NOT EXISTS idx_agent_checkpoint_run_updated
    ON agent_checkpoint (run_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_run_lease (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE,
    lease_owner VARCHAR(128) NOT NULL,
    lease_token VARCHAR(128) NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, lease_token)
);

CREATE INDEX IF NOT EXISTS idx_agent_run_lease_expiry
    ON agent_run_lease (lease_expires_at);

CREATE TABLE IF NOT EXISTS agent_effect_ledger (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL CHECK (step_no >= 0),
    tool_name VARCHAR(128) NOT NULL,
    tool_version VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    parameter_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_digest VARCHAR(128),
    result_uri TEXT,
    approval_id VARCHAR(128),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, step_no, tool_name, tool_version, idempotency_key, parameter_hash),
    CONSTRAINT agent_effect_status_check CHECK
        (status IN ('STARTED','SUCCEEDED','FAILED','WAITING_APPROVAL','UNKNOWN'))
);

CREATE INDEX IF NOT EXISTS idx_agent_effect_run_status
    ON agent_effect_ledger (run_id, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS model_invocation_cache (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL CHECK (step_no >= 0),
    request_hash VARCHAR(128) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(128) NOT NULL,
    response_digest VARCHAR(128) NOT NULL,
    encrypted_response TEXT,
    artifact_uri TEXT,
    usage JSONB NOT NULL DEFAULT '{}'::jsonb,
    key_version VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, step_no, request_hash)
);

CREATE INDEX IF NOT EXISTS idx_model_invocation_cache_expiry
    ON model_invocation_cache (expires_at);
