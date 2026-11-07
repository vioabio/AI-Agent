-- M6：用户反馈、纠错工单和可审计状态迁移
-- 反馈字段保存提交时的事实/知识/索引版本，避免后续发布覆盖历史证据。

CREATE TABLE IF NOT EXISTS user_feedback (
    feedback_id VARCHAR(64) PRIMARY KEY,
    answer_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    error_type VARCHAR(64) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    user_id VARCHAR(128) NOT NULL,
    data_release_id VARCHAR(64) NOT NULL,
    knowledge_release_id VARCHAR(64) NOT NULL,
    index_release_id VARCHAR(64) NOT NULL,
    feedback_type VARCHAR(32) NOT NULL DEFAULT 'CORRECTION',
    priority VARCHAR(8) NOT NULL DEFAULT 'P2',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_feedback_type_check CHECK
        (feedback_type IN ('HELPFUL','NOT_HELPFUL','CORRECTION')),
    CONSTRAINT user_feedback_priority_check CHECK
        (priority IN ('P0','P1','P2','P3'))
);

CREATE TABLE IF NOT EXISTS correction_case (
    feedback_id VARCHAR(64) PRIMARY KEY REFERENCES user_feedback(feedback_id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    assigned_to VARCHAR(128),
    fix_release_id VARCHAR(128),
    evaluation_result TEXT,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT correction_case_status_check CHECK
        (status IN ('SUBMITTED','TRIAGED','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','VERIFIED','CLOSED'))
);

CREATE TABLE IF NOT EXISTS feedback_transition (
    feedback_id VARCHAR(64) NOT NULL REFERENCES correction_case(feedback_id) ON DELETE CASCADE,
    transition_no INTEGER NOT NULL CHECK (transition_no >= 0),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (feedback_id, transition_no),
    CONSTRAINT feedback_transition_to_status_check CHECK
        (to_status IN ('SUBMITTED','TRIAGED','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','VERIFIED','CLOSED')),
    CONSTRAINT feedback_transition_role_check CHECK
        (actor_role IN ('OPERATOR','QUALITY','REVIEWER','PUBLISHER'))
);

CREATE INDEX IF NOT EXISTS idx_user_feedback_user_created
    ON user_feedback (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_correction_case_status_updated
    ON correction_case (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_transition_trace
    ON feedback_transition (trace_id, changed_at DESC);
