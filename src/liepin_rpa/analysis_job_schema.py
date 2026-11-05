"""Explicitly migrated queue tables. Importing does not connect or create tables."""
from sqlalchemy import BigInteger, Boolean, Column, DateTime, Float, ForeignKey, Index, Integer, MetaData, String, Table, Text, UniqueConstraint
from sqlalchemy.dialects.mysql import LONGTEXT

from liepin_rpa.analysis_evidence_store import resume_snapshots

queue_metadata = MetaData()
OPTIONS = {"mysql_charset": "utf8mb4", "mysql_engine": "InnoDB"}

queue_control = Table(
    "analysis_queue_control", queue_metadata,
    Column("control_id", String(32), primary_key=True),
    Column("binding_source", String(32), nullable=False),
    Column("currency", String(16), nullable=False),
    Column("price_version", String(64), nullable=False),
    Column("max_in_flight", Integer, nullable=False),
    Column("max_queued", Integer, nullable=False),
    Column("requests_per_minute", Integer, nullable=False),
    Column("tokens_per_minute", BigInteger, nullable=False),
    Column("window_start", DateTime, nullable=False),
    Column("window_requests", Integer, nullable=False),
    Column("window_tokens", BigInteger, nullable=False),
    **OPTIONS,
)
job_batches = Table(
    "analysis_job_batches", queue_metadata,
    Column("batch_id", String(64), primary_key=True),
    Column("collection_completed", Boolean, nullable=False),
    Column("max_requests", Integer, nullable=False),
    Column("max_tokens", BigInteger, nullable=False),
    Column("max_cost_micros", BigInteger, nullable=False),
    Column("charged_requests", Integer, nullable=False),
    Column("charged_tokens", BigInteger, nullable=False),
    Column("charged_cost_micros", BigInteger, nullable=False),
    Column("created_at", DateTime, nullable=False),
    **OPTIONS,
)
job_heads = Table(
    "analysis_job_heads", queue_metadata,
    Column("scope_id", String(64), primary_key=True),
    Column("candidate_fingerprint", String(64), nullable=False),
    Column("job_profile_id", String(64), nullable=False),
    Column("current_job_id", String(64), nullable=True),
    Column("published_run_id", String(64), nullable=True),
    Column("generation", Integer, nullable=False),
    **OPTIONS,
)
analysis_jobs = Table(
    "analysis_jobs", queue_metadata,
    Column("job_id", String(64), primary_key=True),
    Column("idempotency_key", String(64), nullable=False, unique=True),
    Column("scope_id", String(64), ForeignKey(job_heads.c.scope_id), nullable=False),
    Column("snapshot_id", String(64), ForeignKey(resume_snapshots.c.snapshot_id), nullable=False),
    Column("pipeline_fingerprint", String(64), nullable=False),
    Column("contract_json", Text, nullable=False),
    Column("batch_id", String(64), ForeignKey(job_batches.c.batch_id), nullable=False),
    Column("status", String(32), nullable=False),
    Column("attempts", Integer, nullable=False),
    Column("max_attempts", Integer, nullable=False),
    Column("reserve_tokens", BigInteger, nullable=False),
    Column("reserve_cost_micros", BigInteger, nullable=False),
    Column("max_tokens", BigInteger, nullable=False),
    Column("max_cost_micros", BigInteger, nullable=False),
    Column("charged_tokens", BigInteger, nullable=False),
    Column("charged_cost_micros", BigInteger, nullable=False),
    Column("available_at", DateTime, nullable=False),
    Column("deadline_at", DateTime, nullable=False),
    Column("lease_owner", String(64), nullable=True),
    Column("lease_expires_at", DateTime, nullable=True),
    Column("lease_token", Integer, nullable=False),
    Column("last_error_code", String(64), nullable=True),
    Column("retryable", Boolean, nullable=False),
    Column("analysis_run_id", String(64), nullable=True),
    Column("result_hash", String(64), nullable=True),
    Column("created_at", DateTime, nullable=False),
    Column("updated_at", DateTime, nullable=False),
    **OPTIONS,
)
Index("ix_analysis_jobs_ready", analysis_jobs.c.status, analysis_jobs.c.available_at)
job_memberships = Table(
    "analysis_job_memberships", queue_metadata,
    Column("batch_id", String(64), ForeignKey(job_batches.c.batch_id), primary_key=True),
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), primary_key=True),
    **OPTIONS,
)
job_attempts = Table(
    "analysis_job_attempts", queue_metadata,
    Column("attempt_id", String(64), primary_key=True),
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), nullable=False),
    Column("lease_token", Integer, nullable=False),
    Column("worker_id", String(64), nullable=False),
    Column("status", String(32), nullable=False),
    Column("reserved_tokens", BigInteger, nullable=False),
    Column("reserved_cost_micros", BigInteger, nullable=False),
    Column("reported_tokens", BigInteger, nullable=True),
    Column("reported_cost_micros", BigInteger, nullable=True),
    Column("started_at", DateTime, nullable=False),
    Column("finished_at", DateTime, nullable=True),
    Column("error_code", String(64), nullable=True),
    **OPTIONS,
)
job_events = Table(
    "analysis_job_events", queue_metadata,
    Column("event_id", String(64), primary_key=True),
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), nullable=False),
    Column("event_type", String(32), nullable=False),
    Column("actor", String(64), nullable=False),
    Column("lease_token", Integer, nullable=False),
    Column("created_at", DateTime, nullable=False),
    **OPTIONS,
)
collection_receipts = Table(
    "analysis_collection_receipts", queue_metadata,
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), primary_key=True),
    Column("candidate_name", String(128), nullable=False),
    Column("source_type", String(32), nullable=False),
    Column("qualification_state", String(32), nullable=False),
    Column("qualification_hash", String(64), nullable=False),
    Column("intake_version", String(64), nullable=False),
    **OPTIONS,
)
request_plans = Table(
    "analysis_request_plans", queue_metadata,
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), primary_key=True),
    Column("plan_hash", String(64), nullable=False),
    Column("plan_json", Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
    **OPTIONS,
)
request_receipts = Table(
    "analysis_request_receipts", queue_metadata,
    Column("attempt_id", String(64), ForeignKey(job_attempts.c.attempt_id), primary_key=True),
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), nullable=False, index=True),
    Column("plan_hash", String(64), nullable=False),
    Column("request_hash", String(64), nullable=False),
    Column("state", String(32), nullable=False),
    Column("started_at", DateTime, nullable=False),
    Column("completed_at", DateTime, nullable=True),
    Column("outcome_hash", String(64), nullable=True),
    Column("http_status", Integer, nullable=True),
    Column("error_code", String(64), nullable=True),
    Column("latency_ms", Float, nullable=True),
    Column("input_tokens", BigInteger, nullable=True),
    Column("output_tokens", BigInteger, nullable=True),
    Column("model_verified", Boolean, nullable=True),
    Column("response_id_hash", String(64), nullable=True),
    Column("reported_tokens", BigInteger, nullable=True),
    Column("reported_cost_micros", BigInteger, nullable=True),
    Column("accounted_tokens", BigInteger, nullable=True),
    Column("accounted_cost_micros", BigInteger, nullable=True),
    **OPTIONS,
)

collected_inputs = Table(
    "analysis_collected_inputs", queue_metadata,
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), primary_key=True),
    Column("record_json", Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
    Column("profile_json", Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
    Column("input_hash", String(64), nullable=False),
    Column("strategy", String(16), nullable=False),
    **OPTIONS,
)
tool_sessions = Table(
    "analysis_tool_sessions", queue_metadata,
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), primary_key=True),
    Column("session_hash", String(64), nullable=False),
    Column("config_json", Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
    Column("created_at", DateTime, nullable=False),
    **OPTIONS,
)
tool_rounds = Table(
    "analysis_tool_rounds", queue_metadata,
    Column("round_id", String(64), primary_key=True),
    Column("job_id", String(64), ForeignKey(analysis_jobs.c.job_id), nullable=False),
    Column("sequence", Integer, nullable=False),
    Column("lease_token", Integer, nullable=False),
    Column("state", String(32), nullable=False),
    Column("request_hash", String(64), nullable=True),
    Column("reply_json", Text().with_variant(LONGTEXT(), "mysql"), nullable=True),
    Column("reserved_tokens", BigInteger, nullable=False),
    Column("reserved_cost_micros", BigInteger, nullable=False),
    Column("input_tokens", BigInteger, nullable=True),
    Column("output_tokens", BigInteger, nullable=True),
    Column("reported_cost_micros", BigInteger, nullable=True),
    Column("started_at", DateTime, nullable=False),
    Column("finished_at", DateTime, nullable=True),
    UniqueConstraint("job_id", "sequence", name="uniq_tool_job_round"),
    **OPTIONS,
)
