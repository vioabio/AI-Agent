-- N4：Run 创建时冻结版本血缘，供事件/消息/答案投影回读。
-- 兼容旧 v1.0 行：新增 release 字段允许为空，contract_version 统一回退 v1；
-- 不改写旧答案或把 unknown 当作有效版本。
ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS dlc_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS data_release_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS knowledge_release_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS index_release_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS contract_version VARCHAR(32);

UPDATE agent_run
SET contract_version = 'v1'
WHERE contract_version IS NULL OR BTRIM(contract_version) = ''
   OR LOWER(BTRIM(contract_version)) = 'unknown';

ALTER TABLE agent_run
    ALTER COLUMN contract_version SET DEFAULT 'v1',
    ALTER COLUMN contract_version SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_run_lineage_release
    ON agent_run (data_release_id, knowledge_release_id, index_release_id, created_at DESC);

COMMENT ON COLUMN agent_run.dlc_id IS 'N4 canonical DLC release id; nullable for base-game/legacy runs';
COMMENT ON COLUMN agent_run.data_release_id IS 'Immutable data release selected for this Run';
COMMENT ON COLUMN agent_run.knowledge_release_id IS 'Immutable knowledge release selected for this Run';
COMMENT ON COLUMN agent_run.index_release_id IS 'Immutable vector/FTS index release selected for this Run';
COMMENT ON COLUMN agent_run.contract_version IS 'Answer/API contract version, defaults to v1';
