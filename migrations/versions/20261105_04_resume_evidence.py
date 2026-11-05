"""Immutable resume snapshots and exact evidence references; no historical backfill."""
from __future__ import annotations

from alembic import context, op
import sqlalchemy as sa
from sqlalchemy.dialects.mysql import LONGTEXT

revision = "20261105_04"
down_revision = "20261105_03"
branch_labels = None
depends_on = None

SNAPSHOTS = "candidate_resume_snapshots"
RUNS = "candidate_resume_analysis_runs"
REFS = "candidate_resume_evidence_refs"
CACHE = "candidate_resume_analysis_cache"


def upgrade():
    op.create_table(
        SNAPSHOTS,
        sa.Column("snapshot_id", sa.String(64), primary_key=True),
        sa.Column("candidate_fingerprint", sa.String(64), nullable=False),
        sa.Column("content_hash", sa.String(64), nullable=False),
        sa.Column("normalization_version", sa.String(64), nullable=False),
        sa.Column("raw_text", sa.Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        mysql_charset="utf8mb4", mysql_engine="InnoDB",
    )
    op.create_index("ix_candidate_resume_snapshots_candidate_fingerprint", SNAPSHOTS, ["candidate_fingerprint"])
    op.create_table(
        RUNS,
        sa.Column("analysis_run_id", sa.String(64), primary_key=True),
        sa.Column("candidate_fingerprint", sa.String(64), nullable=False),
        sa.Column("job_profile_id", sa.String(64), nullable=False),
        sa.Column("snapshot_id", sa.String(64), sa.ForeignKey(f"{SNAPSHOTS}.snapshot_id"), nullable=False),
        sa.Column("analysis_version", sa.String(64), nullable=False),
        sa.Column("result_hash", sa.String(64), nullable=False),
        sa.Column("result_json", sa.Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
        sa.Column("binding_source", sa.String(32), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        mysql_charset="utf8mb4", mysql_engine="InnoDB",
    )
    op.create_index("ix_candidate_resume_analysis_runs_candidate_fingerprint", RUNS, ["candidate_fingerprint"])
    op.create_table(
        REFS,
        sa.Column("evidence_id", sa.String(64), primary_key=True),
        sa.Column("analysis_run_id", sa.String(64), sa.ForeignKey(f"{RUNS}.analysis_run_id"), nullable=False),
        sa.Column("snapshot_id", sa.String(64), sa.ForeignKey(f"{SNAPSHOTS}.snapshot_id"), nullable=False),
        sa.Column("chunk_id", sa.String(64), nullable=False),
        sa.Column("summary_index", sa.Integer(), nullable=False),
        sa.Column("start", sa.Integer(), nullable=False),
        sa.Column("end", sa.Integer(), nullable=False),
        sa.Column("quote", sa.Text(), nullable=False),
        mysql_charset="utf8mb4", mysql_engine="InnoDB",
    )
    op.create_index("ix_candidate_resume_evidence_refs_analysis_run_id", REFS, ["analysis_run_id"])
    if context.is_offline_mode() or sa.inspect(op.get_bind()).has_table(CACHE):
        with op.batch_alter_table(CACHE) as batch:
            batch.add_column(sa.Column("analysis_run_id", sa.String(64), nullable=True))
            batch.add_column(sa.Column("analyzed_text_hash", sa.String(64), nullable=True))


def downgrade():
    if context.is_offline_mode() or sa.inspect(op.get_bind()).has_table(CACHE):
        with op.batch_alter_table(CACHE) as batch:
            batch.drop_column("analyzed_text_hash")
            batch.drop_column("analysis_run_id")
    op.drop_table(REFS)
    op.drop_table(RUNS)
    op.drop_table(SNAPSHOTS)
