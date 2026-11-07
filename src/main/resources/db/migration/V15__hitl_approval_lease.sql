-- N3：持久化 HITL 审批、主体绑定和租约/CAS。
-- 运行时审批视图可以从本表恢复；审批决定只允许从 PENDING 单向收敛。

CREATE TABLE IF NOT EXISTS hitl_approval (
    approval_id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL
        REFERENCES agent_run(id) ON DELETE RESTRICT,
    tool_name VARCHAR(128) NOT NULL,
    tool_version VARCHAR(128) NOT NULL,
    parameter_digest VARCHAR(256) NOT NULL,
    risk_level VARCHAR(32) NOT NULL
        CHECK (risk_level IN ('SAFE','MEDIUM','HIGH','UNKNOWN')),
    expires_at TIMESTAMPTZ NOT NULL,
    decision VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (decision IN ('PENDING','APPROVED','DENIED','EXPIRED')),
    decided_by VARCHAR(128),
    decided_at TIMESTAMPTZ,
    decision_reason TEXT,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((decision = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL
            AND ((lease_owner IS NULL AND lease_expires_at IS NULL)
                 OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)))
           OR (decision <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
               AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CHECK ((lease_expires_at IS NULL OR lease_owner IS NOT NULL)),
    CHECK (length(parameter_digest) > 0)
);

-- 只限制同时存在一个活跃 PENDING 请求；历史 DENIED/EXPIRED/APPROVED 行保留，
-- 同一参数在过期后可产生新的 approval_id，不覆盖审计事实。
CREATE UNIQUE INDEX IF NOT EXISTS uq_hitl_approval_pending_key
    ON hitl_approval (user_id, run_id, tool_name, tool_version, parameter_digest)
    WHERE decision = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_hitl_approval_user_run
    ON hitl_approval (user_id, run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_hitl_approval_expiry
    ON hitl_approval (decision, expires_at);
