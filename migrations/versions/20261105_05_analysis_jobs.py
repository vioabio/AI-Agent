"""Durable shadow queue, budgets, attempts and publication pointers. No backfill."""
from alembic import op
import sqlalchemy as sa

revision = "20261105_05"
down_revision = "20261105_04"
branch_labels = None
depends_on = None

OPTIONS = {"mysql_charset": "utf8mb4", "mysql_engine": "InnoDB"}


def upgrade():
    op.create_table(
        "analysis_queue_control",
        sa.Column("control_id", sa.String(32), primary_key=True),
        sa.Column("binding_source", sa.String(32), nullable=False),
        sa.Column("currency", sa.String(16), nullable=False),
        sa.Column("price_version", sa.String(64), nullable=False),
        sa.Column("max_in_flight", sa.Integer(), nullable=False),
        sa.Column("max_queued", sa.Integer(), nullable=False),
        sa.Column("requests_per_minute", sa.Integer(), nullable=False),
        sa.Column("tokens_per_minute", sa.BigInteger(), nullable=False),
        sa.Column("window_start", sa.DateTime(), nullable=False),
        sa.Column("window_requests", sa.Integer(), nullable=False),
        sa.Column("window_tokens", sa.BigInteger(), nullable=False), **OPTIONS,
    )
    op.create_table(
        "analysis_job_batches",
        sa.Column("batch_id", sa.String(64), primary_key=True),
        sa.Column("collection_completed", sa.Boolean(), nullable=False),
        sa.Column("max_requests", sa.Integer(), nullable=False),
        sa.Column("max_tokens", sa.BigInteger(), nullable=False),
        sa.Column("max_cost_micros", sa.BigInteger(), nullable=False),
        sa.Column("charged_requests", sa.Integer(), nullable=False),
        sa.Column("charged_tokens", sa.BigInteger(), nullable=False),
        sa.Column("charged_cost_micros", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False), **OPTIONS,
    )
    op.create_table(
        "analysis_job_heads",
        sa.Column("scope_id", sa.String(64), primary_key=True),
        sa.Column("candidate_fingerprint", sa.String(64), nullable=False),
        sa.Column("job_profile_id", sa.String(64), nullable=False),
        sa.Column("current_job_id", sa.String(64), nullable=True),
        sa.Column("published_run_id", sa.String(64), nullable=True),
        sa.Column("generation", sa.Integer(), nullable=False), **OPTIONS,
    )
    op.create_table(
        "analysis_jobs",
        sa.Column("job_id", sa.String(64), primary_key=True),
        sa.Column("idempotency_key", sa.String(64), nullable=False, unique=True),
        sa.Column("scope_id", sa.String(64), sa.ForeignKey("analysis_job_heads.scope_id"), nullable=False),
        sa.Column("snapshot_id", sa.String(64), sa.ForeignKey("candidate_resume_snapshots.snapshot_id"), nullable=False),
        sa.Column("pipeline_fingerprint", sa.String(64), nullable=False),
        sa.Column("contract_json", sa.Text(), nullable=False),
        sa.Column("batch_id", sa.String(64), sa.ForeignKey("analysis_job_batches.batch_id"), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("max_attempts", sa.Integer(), nullable=False),
        sa.Column("reserve_tokens", sa.BigInteger(), nullable=False),
        sa.Column("reserve_cost_micros", sa.BigInteger(), nullable=False),
        sa.Column("max_tokens", sa.BigInteger(), nullable=False),
        sa.Column("max_cost_micros", sa.BigInteger(), nullable=False),
        sa.Column("charged_tokens", sa.BigInteger(), nullable=False),
        sa.Column("charged_cost_micros", sa.BigInteger(), nullable=False),
        sa.Column("available_at", sa.DateTime(), nullable=False),
        sa.Column("deadline_at", sa.DateTime(), nullable=False),
        sa.Column("lease_owner", sa.String(64), nullable=True),
        sa.Column("lease_expires_at", sa.DateTime(), nullable=True),
        sa.Column("lease_token", sa.Integer(), nullable=False),
        sa.Column("last_error_code", sa.String(64), nullable=True),
        sa.Column("retryable", sa.Boolean(), nullable=False),
        sa.Column("analysis_run_id", sa.String(64), nullable=True),
        sa.Column("result_hash", sa.String(64), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False), **OPTIONS,
    )
    op.create_index("ix_analysis_jobs_ready", "analysis_jobs", ["status", "available_at"])
    op.create_table(
        "analysis_job_memberships",
        sa.Column("batch_id", sa.String(64), sa.ForeignKey("analysis_job_batches.batch_id"), primary_key=True),
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), primary_key=True), **OPTIONS,
    )
    op.create_table(
        "analysis_job_attempts",
        sa.Column("attempt_id", sa.String(64), primary_key=True),
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), nullable=False),
        sa.Column("lease_token", sa.Integer(), nullable=False),
        sa.Column("worker_id", sa.String(64), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("reserved_tokens", sa.BigInteger(), nullable=False),
        sa.Column("reserved_cost_micros", sa.BigInteger(), nullable=False),
        sa.Column("reported_tokens", sa.BigInteger(), nullable=True),
        sa.Column("reported_cost_micros", sa.BigInteger(), nullable=True),
        sa.Column("started_at", sa.DateTime(), nullable=False),
        sa.Column("finished_at", sa.DateTime(), nullable=True),
        sa.Column("error_code", sa.String(64), nullable=True), **OPTIONS,
    )
    op.create_table(
        "analysis_job_events",
        sa.Column("event_id", sa.String(64), primary_key=True),
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), nullable=False),
        sa.Column("event_type", sa.String(32), nullable=False),
        sa.Column("actor", sa.String(64), nullable=False),
        sa.Column("lease_token", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False), **OPTIONS,
    )


def downgrade():
    for name in ("analysis_job_events", "analysis_job_attempts", "analysis_job_memberships", "analysis_jobs",
                 "analysis_job_heads", "analysis_job_batches", "analysis_queue_control"):
        op.drop_table(name)
