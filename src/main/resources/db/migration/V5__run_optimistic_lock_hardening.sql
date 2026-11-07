-- M3/M4 并发硬化：为已存在的 agent_run 表补齐乐观锁列。
-- V3 新安装已经声明 version；IF NOT EXISTS 允许旧环境平滑升级。
ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE agent_run
SET version = 0
WHERE version IS NULL;

ALTER TABLE agent_run
    DROP CONSTRAINT IF EXISTS ck_agent_run_version_non_negative;

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_version_non_negative CHECK (version >= 0);

CREATE INDEX IF NOT EXISTS idx_agent_run_active_version
    ON agent_run (status, version)
    WHERE status IN ('CREATED', 'RUNNING', 'WAITING_APPROVAL');
