-- N3：强化 N2 已建立的副作用账本。
-- 本迁移只扩展/加固现有 agent_effect_ledger，不重复创建同名表。

ALTER TABLE agent_effect_ledger
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS attempt_no INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS retention_class VARCHAR(64) NOT NULL DEFAULT 'run-effects',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE agent_effect_ledger
    ADD CONSTRAINT agent_effect_attempt_nonnegative CHECK (attempt_no >= 0);

CREATE INDEX IF NOT EXISTS idx_agent_effect_trace
    ON agent_effect_ledger (trace_id, run_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_effect_retention
    ON agent_effect_ledger (retention_class, updated_at DESC);

-- 终态副作用只能从 STARTED 结算一次；禁止静默覆盖已结算结果或修改复合键。
CREATE OR REPLACE FUNCTION prevent_effect_ledger_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.run_id <> NEW.run_id OR OLD.step_no <> NEW.step_no
       OR OLD.tool_name <> NEW.tool_name OR OLD.tool_version <> NEW.tool_version
       OR OLD.idempotency_key <> NEW.idempotency_key OR OLD.parameter_hash <> NEW.parameter_hash THEN
        RAISE EXCEPTION 'agent_effect_ledger key is immutable';
    END IF;
    IF OLD.status <> 'STARTED' THEN
        RAISE EXCEPTION 'agent_effect_ledger terminal row is immutable';
    END IF;
    IF NEW.status = 'STARTED' THEN
        RAISE EXCEPTION 'agent_effect_ledger must transition to a terminal status';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_effect_ledger_immutable ON agent_effect_ledger;
CREATE TRIGGER trg_effect_ledger_immutable
    BEFORE UPDATE ON agent_effect_ledger
    FOR EACH ROW EXECUTE FUNCTION prevent_effect_ledger_mutation();
