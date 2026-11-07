CREATE TABLE IF NOT EXISTS agent_authorization_snapshot_revocation (
    run_id VARCHAR(128) PRIMARY KEY REFERENCES agent_authorization_snapshot(run_id),
    revoked_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    reason VARCHAR(256) NOT NULL
);
