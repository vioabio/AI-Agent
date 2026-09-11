-- N4/N6a 前置：答案稳定投影及版本血缘。
-- 投影只追加同一 answerId/runId 的不可变快照，反馈通过它回读 run/trace/release。
CREATE TABLE IF NOT EXISTS answer_projection (
    answer_id VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE REFERENCES agent_run(id) ON DELETE CASCADE,
    session_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    answer_text TEXT NOT NULL DEFAULT '',
    game_release_id VARCHAR(64),
    dlc_id VARCHAR(64),
    battle_format_id VARCHAR(64),
    ruleset_version_id VARCHAR(64),
    data_release_id VARCHAR(64),
    knowledge_release_id VARCHAR(64),
    index_release_id VARCHAR(64),
    contract_version VARCHAR(32) NOT NULL DEFAULT 'v1',
    lineage_status VARCHAR(32) NOT NULL DEFAULT 'PARTIAL',
    answer_digest CHAR(64) NOT NULL,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT answer_projection_lineage_status_check
        CHECK (lineage_status IN ('COMPLETE', 'PARTIAL', 'UNTRACEABLE')),
    CONSTRAINT answer_projection_digest_check
        CHECK (answer_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT answer_projection_citations_array_check
        CHECK (jsonb_typeof(citations) = 'array'),
    CONSTRAINT answer_projection_limitations_array_check
        CHECK (jsonb_typeof(limitations) = 'array'),
    CONSTRAINT answer_projection_completed_after_created
        CHECK (completed_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_answer_projection_user_completed
    ON answer_projection (user_id, completed_at DESC, answer_id);
CREATE INDEX IF NOT EXISTS idx_answer_projection_trace
    ON answer_projection (trace_id, completed_at DESC);
CREATE INDEX IF NOT EXISTS idx_answer_projection_release
    ON answer_projection (data_release_id, knowledge_release_id, index_release_id);

-- 已写入的答案血缘不能被 UPDATE/DELETE 静默改写；纠错通过新 release/新答案关联。
CREATE OR REPLACE FUNCTION prevent_answer_projection_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'answer_projection is append-only';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS answer_projection_append_only ON answer_projection;
CREATE TRIGGER answer_projection_append_only
    BEFORE UPDATE OR DELETE ON answer_projection
    FOR EACH ROW EXECUTE FUNCTION prevent_answer_projection_mutation();
