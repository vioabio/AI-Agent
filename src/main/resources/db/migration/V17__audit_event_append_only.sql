-- N3：集中 HITL 审计。应用只执行 INSERT；数据库触发器和权限共同阻止
-- UPDATE/DELETE，避免把审批历史改写成“从未发生”。

CREATE TABLE IF NOT EXISTS audit_event (
    event_id VARCHAR(128) PRIMARY KEY,
    approval_id VARCHAR(128)
        REFERENCES hitl_approval(approval_id) ON DELETE RESTRICT,
    action VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_version VARCHAR(128),
    parameter_digest VARCHAR(256),
    reason TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    retention_class VARCHAR(64) NOT NULL DEFAULT 'security',
    event_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (length(event_hash) = 64)
);

CREATE INDEX IF NOT EXISTS idx_audit_event_approval_time
    ON audit_event (approval_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_audit_event_user_run_time
    ON audit_event (user_id, run_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION vio_reject_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_event_append_only ON audit_event;
CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION vio_reject_audit_event_mutation();

REVOKE UPDATE, DELETE ON audit_event FROM PUBLIC;
