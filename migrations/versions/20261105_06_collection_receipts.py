"""Synthetic intake receipts for authorized shadow-task views; no legacy backfill."""
from alembic import op
import sqlalchemy as sa

revision = "20261105_06"
down_revision = "20261105_05"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "analysis_collection_receipts",
        sa.Column("job_id", sa.String(64), sa.ForeignKey("analysis_jobs.job_id"), primary_key=True),
        sa.Column("candidate_name", sa.String(128), nullable=False),
        sa.Column("source_type", sa.String(32), nullable=False),
        sa.Column("qualification_state", sa.String(32), nullable=False),
        sa.Column("qualification_hash", sa.String(64), nullable=False),
        sa.Column("intake_version", sa.String(64), nullable=False),
        mysql_charset="utf8mb4", mysql_engine="InnoDB",
    )


def downgrade():
    op.drop_table("analysis_collection_receipts")
