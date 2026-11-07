-- R07: immutable, opaque output for a replayable Run step.
-- The payload is encrypted before persistence; prompts, policies and plaintext answers
-- have no columns in this table and must never be placed in encrypted_payload by callers.
CREATE TABLE IF NOT EXISTS agent_run_step_output (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL CHECK (step_no >= 0),
    executor_version VARCHAR(128) NOT NULL CHECK (length(btrim(executor_version)) > 0),
    context_hash VARCHAR(64) NOT NULL CHECK (context_hash ~ '^[0-9a-fA-F]{64}$'),
    payload_digest VARCHAR(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-fA-F]{64}$'),
    encrypted_payload TEXT NOT NULL CHECK (length(btrim(encrypted_payload)) > 0),
    key_version VARCHAR(128) NOT NULL CHECK (length(btrim(key_version)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, step_no),
    CONSTRAINT agent_run_step_output_expiry_check CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_agent_run_step_output_expiry
    ON agent_run_step_output (expires_at);
