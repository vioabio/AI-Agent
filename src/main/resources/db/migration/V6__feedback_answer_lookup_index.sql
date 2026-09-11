-- M6：按用户和答案查询反馈时先过滤再分页，避免大用户反馈列表产生缺页。
-- 独立迁移而不是修改 V4，保证已执行环境的 Flyway checksum 不变。
CREATE INDEX IF NOT EXISTS idx_user_feedback_user_answer_updated
    ON user_feedback (user_id, answer_id, updated_at DESC, feedback_id);
