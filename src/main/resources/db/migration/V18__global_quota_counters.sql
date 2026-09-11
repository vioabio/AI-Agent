-- N3：跨实例配额桶（Redis/Lua 的可替换 JDBC 过渡实现）。
CREATE TABLE IF NOT EXISTS global_quota_counter (
    scope_key VARCHAR(256) NOT NULL,
    window_bucket BIGINT NOT NULL,
    used INTEGER NOT NULL CHECK (used >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope_key, window_bucket)
);

CREATE INDEX IF NOT EXISTS idx_global_quota_expiry
    ON global_quota_counter (expires_at);
