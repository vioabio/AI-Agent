-- M3：唯一 Agent Run、事件游标和持久会话

CREATE TABLE IF NOT EXISTS conversation (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    title VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS agent_run (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    user_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    prompt TEXT NOT NULL,
    game_release_id VARCHAR(64),
    battle_format VARCHAR(64),
    ruleset_version_id VARCHAR(64),
    language VARCHAR(16),
    trace_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (user_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS agent_event (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    event_id BIGINT NOT NULL,
    schema_version VARCHAR(16) NOT NULL DEFAULT '1',
    type VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (run_id, event_id)
);

CREATE TABLE IF NOT EXISTS chat_message (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    run_id VARCHAR(64) REFERENCES agent_run(id) ON DELETE SET NULL,
    user_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_agent_run_user_created
    ON agent_run (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_conversation
    ON agent_run (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_event_run_event
    ON agent_event (run_id, event_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_conversation_created
    ON chat_message (conversation_id, created_at, id);

-- 一个 Run 只能拥有一个终态事件；状态迁移由服务层 CAS/锁保证。
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_event_single_terminal
    ON agent_event (run_id)
    WHERE type IN ('run.completed', 'run.failed', 'run.cancelled', 'run.timed_out');

