"""Immutable collected inputs and durable bounded tool request ledger; no backfill."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.mysql import LONGTEXT

revision = "20261105_08"
down_revision = "20261105_07"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("analysis_collected_inputs",
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), primary_key=True),
        sa.Column("record_json", sa.Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
        sa.Column("profile_json", sa.Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
        sa.Column("input_hash", sa.String(64), nullable=False),
        sa.Column("strategy", sa.String(16), nullable=False),
        mysql_charset="utf8mb4", mysql_engine="InnoDB")
    op.create_table("analysis_tool_sessions",
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), primary_key=True),
        sa.Column("session_hash", sa.String(64), nullable=False),
        sa.Column("config_json", sa.Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        mysql_charset="utf8mb4", mysql_engine="InnoDB")
    op.create_table("analysis_tool_rounds",
        sa.Column("round_id", sa.String(64), primary_key=True),
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("lease_token", sa.Integer(), nullable=False),
        sa.Column("state", sa.String(32), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=True),
        sa.Column("reply_json", sa.Text().with_variant(LONGTEXT(), "mysql"), nullable=True),
        sa.Column("reserved_tokens", sa.BigInteger(), nullable=False),
        sa.Column("reserved_cost_micros", sa.BigInteger(), nullable=False),
        sa.Column("input_tokens", sa.BigInteger(), nullable=True),
        sa.Column("output_tokens", sa.BigInteger(), nullable=True),
        sa.Column("reported_cost_micros", sa.BigInteger(), nullable=True),
        sa.Column("started_at", sa.DateTime(), nullable=False),
        sa.Column("finished_at", sa.DateTime(), nullable=True),
        sa.UniqueConstraint("job_id", "sequence", name="uniq_tool_job_round"),
        mysql_charset="utf8mb4", mysql_engine="InnoDB")


def downgrade():
    op.drop_table("analysis_tool_rounds")
    op.drop_table("analysis_tool_sessions")
    op.drop_table("analysis_collected_inputs")
