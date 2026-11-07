-- N4：版本化、带来源与有效窗口的活动资讯。
-- 只读 API 只消费 REVIEWED/PUBLISHED/ACTIVE 且 freshness=FRESH 的记录；
-- 摄取/审核流程可写入 CANDIDATE，但不能绕过服务层直接对外展示。
CREATE TABLE IF NOT EXISTS activity_notice (
    id VARCHAR(128) PRIMARY KEY,
    notice_key VARCHAR(128) NOT NULL,
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    title VARCHAR(256) NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    source_id VARCHAR(128) NOT NULL,
    source_url TEXT NOT NULL CHECK (source_url ~* '^https?://[^[:space:]]+$'),
    license VARCHAR(128) NOT NULL CHECK (length(trim(license)) > 0),
    trust_level VARCHAR(64) NOT NULL CHECK (length(trim(trust_level)) > 0),
    game_release_id VARCHAR(64) NOT NULL REFERENCES game_release(id),
    dlc_id VARCHAR(128),
    language VARCHAR(16) NOT NULL,
    region VARCHAR(64),
    event_type VARCHAR(64) NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    freshness VARCHAR(32) NOT NULL CHECK (freshness IN
        ('FRESH', 'STALE', 'EXPIRED', 'NOT_YET_ACTIVE', 'UNKNOWN')),
    status VARCHAR(32) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    content_version VARCHAR(64) NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT activity_notice_window_valid
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT activity_notice_publish_time_valid
        CHECK (published_at IS NULL OR published_at >= retrieved_at),
    CONSTRAINT activity_notice_version_unique UNIQUE (notice_key, version_no)
);

CREATE INDEX IF NOT EXISTS idx_activity_notice_lookup
    ON activity_notice (game_release_id, dlc_id, language, region, effective_from DESC);

CREATE INDEX IF NOT EXISTS idx_activity_notice_status_window
    ON activity_notice (status, freshness, effective_from, effective_to);

-- 同一版本不可被静默覆盖；新内容必须产生新的 version_no/id。
CREATE OR REPLACE FUNCTION prevent_activity_notice_version_mutation()
RETURNS trigger AS $$
BEGIN
    IF OLD.notice_key <> NEW.notice_key
       OR OLD.version_no <> NEW.version_no
       OR OLD.checksum <> NEW.checksum
       OR OLD.content_version <> NEW.content_version THEN
        RAISE EXCEPTION 'activity_notice version is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_activity_notice_version_immutable ON activity_notice;
CREATE TRIGGER trg_activity_notice_version_immutable
    BEFORE UPDATE ON activity_notice
    FOR EACH ROW EXECUTE FUNCTION prevent_activity_notice_version_mutation();
