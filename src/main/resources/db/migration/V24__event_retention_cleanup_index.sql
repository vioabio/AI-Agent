-- N2：事件保留清理的前向兼容索引。
-- 仅优化已完成 Run 的批量扫描，不改变事件事实或终态约束。
CREATE INDEX IF NOT EXISTS idx_agent_event_retention_cleanup
    ON agent_event (event_timestamp ASC, run_id ASC, event_id ASC)
    WHERE type NOT IN ('run.completed', 'run.failed', 'run.cancelled', 'run.timed_out');

CREATE INDEX IF NOT EXISTS idx_agent_event_outbox_run_event_status
    ON agent_event_outbox (run_id, event_id, status);
