-- R03: store exact producer bytes, not a mutable client-supplied projection.
CREATE TABLE evaluation_report (
    evaluation_run_id VARCHAR(128) PRIMARY KEY,
    report_hash VARCHAR(71) NOT NULL CHECK (report_hash ~ '^sha256:[0-9a-f]{64}$'),
    artifact_bytes BYTEA NOT NULL CHECK (octet_length(artifact_bytes) BETWEEN 1 AND 1048576),
    source VARCHAR(512) NOT NULL CHECK (length(trim(source)) > 0),
    imported_by VARCHAR(256) NOT NULL CHECK (length(trim(imported_by)) > 0),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (evaluation_run_id ~ '^[A-Za-z0-9._-]{3,128}$')
);

CREATE OR REPLACE FUNCTION vio_reject_evaluation_report_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'evaluation_report is append-only';
END;
$$;

CREATE TRIGGER trg_evaluation_report_append_only
    BEFORE UPDATE OR DELETE ON evaluation_report
    FOR EACH ROW EXECUTE FUNCTION vio_reject_evaluation_report_mutation();

REVOKE UPDATE, DELETE ON evaluation_report FROM PUBLIC;
