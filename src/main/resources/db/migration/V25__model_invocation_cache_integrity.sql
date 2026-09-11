-- N2：模型调用缓存完整性约束。
--
-- 约束先以 NOT VALID 方式加入，避免历史候选数据阻塞迁移；它们仍会
-- 立即约束新写入。清理历史脏数据后，由 staging 运维窗口执行
-- VALIDATE CONSTRAINT，并把输出归档到 N2 证据目录。

ALTER TABLE model_invocation_cache
    ADD CONSTRAINT model_cache_request_hash_sha256
        CHECK (request_hash ~ '^[0-9a-fA-F]{64}$') NOT VALID;

ALTER TABLE model_invocation_cache
    ADD CONSTRAINT model_cache_response_digest_sha256
        CHECK (response_digest ~ '^[0-9a-fA-F]{64}$') NOT VALID;

ALTER TABLE model_invocation_cache
    ADD CONSTRAINT model_cache_single_payload
        CHECK (
            (encrypted_response IS NOT NULL AND btrim(encrypted_response) <> '' AND artifact_uri IS NULL)
            OR (artifact_uri IS NOT NULL AND btrim(artifact_uri) <> '' AND encrypted_response IS NULL)
        ) NOT VALID;

CREATE INDEX IF NOT EXISTS idx_model_invocation_cache_request_hash
    ON model_invocation_cache (request_hash);
