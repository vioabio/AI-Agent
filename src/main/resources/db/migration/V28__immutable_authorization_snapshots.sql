CREATE TABLE IF NOT EXISTS agent_authorization_snapshot (
    run_id VARCHAR(128) PRIMARY KEY REFERENCES agent_run(id),
    principal_id VARCHAR(256) NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    roles_json TEXT NOT NULL,
    allowed_tools_json TEXT NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    policy_fingerprint CHAR(64) NOT NULL,
    binding_id VARCHAR(256) NOT NULL,
    issued_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NULL,
    CONSTRAINT authorization_snapshot_fingerprint_ck CHECK (policy_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT authorization_snapshot_expiry_ck CHECK (expires_at IS NULL OR expires_at > issued_at)
);

CREATE INDEX IF NOT EXISTS idx_agent_authorization_snapshot_expires
    ON agent_authorization_snapshot (expires_at);
