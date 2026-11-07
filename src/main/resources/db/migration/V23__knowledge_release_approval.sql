-- N1b：知识发布独立审批事实。
-- approval 与 DocumentVersion.review_status 分离；只有 promotion service 在
-- ReleaseQualityGate 通过后才写入。记录追加后由数据库触发器阻止篡改。

CREATE TABLE IF NOT EXISTS knowledge_release_approval (
    approval_id VARCHAR(128) PRIMARY KEY,
    knowledge_release_id VARCHAR(64) NOT NULL
        REFERENCES knowledge_release(id) ON DELETE RESTRICT,
    index_release_id VARCHAR(64) NOT NULL
        REFERENCES vector_index_release(id) ON DELETE RESTRICT,
    data_release_id VARCHAR(64) NOT NULL
        REFERENCES data_release(id) ON DELETE RESTRICT,
    evaluation_run_id VARCHAR(128) NOT NULL,
    report_hash VARCHAR(256) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    reason TEXT,
    approved_at TIMESTAMPTZ NOT NULL,
    gate_version VARCHAR(64) NOT NULL DEFAULT 'n1b-v1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (knowledge_release_id, index_release_id, evaluation_run_id, report_hash),
    CHECK (length(trim(approval_id)) > 0),
    CHECK (length(trim(evaluation_run_id)) > 0),
    CHECK (length(trim(report_hash)) > 0),
    CHECK (lower(trim(evaluation_run_id)) NOT IN ('unknown', 'untraceable', 'n/a')),
    CHECK (lower(trim(report_hash)) NOT IN ('unknown', 'untraceable', 'n/a'))
);

CREATE INDEX IF NOT EXISTS idx_knowledge_release_approval_release
    ON knowledge_release_approval (knowledge_release_id, approved_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_release_approval_evaluation
    ON knowledge_release_approval (evaluation_run_id, report_hash);

CREATE OR REPLACE FUNCTION vio_reject_knowledge_release_approval_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'knowledge_release_approval is append-only';
END;
$$;

DROP TRIGGER IF EXISTS trg_knowledge_release_approval_append_only ON knowledge_release_approval;
CREATE TRIGGER trg_knowledge_release_approval_append_only
    BEFORE UPDATE OR DELETE ON knowledge_release_approval
    FOR EACH ROW EXECUTE FUNCTION vio_reject_knowledge_release_approval_mutation();

REVOKE UPDATE, DELETE ON knowledge_release_approval FROM PUBLIC;
