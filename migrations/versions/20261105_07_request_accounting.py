"""Frozen synthetic SDK request plans and durable usage receipts; no backfill."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.mysql import LONGTEXT

revision = "20261105_07"
down_revision = "20261105_06"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "analysis_request_plans",
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), primary_key=True),
        sa.Column("plan_hash", sa.String(64), nullable=False),
        sa.Column("plan_json", sa.Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
        mysql_charset="utf8mb4", mysql_engine="InnoDB",
    )
    op.create_table(
        "analysis_request_receipts",
        sa.Column("attempt_id", sa.String(64), sa.ForeignKey("analysis_job_attempts.attempt_id"), primary_key=True),
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), nullable=False),
        sa.Column("plan_hash", sa.String(64), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("state", sa.String(32), nullable=False),
        sa.Column("started_at", sa.DateTime(), nullable=False),
        sa.Column("completed_at", sa.DateTime(), nullable=True),
        sa.Column("outcome_hash", sa.String(64), nullable=True),
        sa.Column("http_status", sa.Integer(), nullable=True),
        sa.Column("error_code", sa.String(64), nullable=True),
        sa.Column("latency_ms", sa.Float(), nullable=True),
        sa.Column("input_tokens", sa.BigInteger(), nullable=True),
        sa.Column("output_tokens", sa.BigInteger(), nullable=True),
        sa.Column("model_verified", sa.Boolean(), nullable=True),
        sa.Column("response_id_hash", sa.String(64), nullable=True),
        sa.Column("reported_tokens", sa.BigInteger(), nullable=True),
        sa.Column("reported_cost_micros", sa.BigInteger(), nullable=True),
        sa.Column("accounted_tokens", sa.BigInteger(), nullable=True),
        sa.Column("accounted_cost_micros", sa.BigInteger(), nullable=True),
        mysql_charset="utf8mb4", mysql_engine="InnoDB",
    )
    op.create_index("ix_analysis_request_receipts_job_id", "analysis_request_receipts", ["job_id"])


def downgrade():
    op.drop_table("analysis_request_receipts")
    op.drop_table("analysis_request_plans")
