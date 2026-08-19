"""Persist candidate membership in a concrete search batch."""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "20260818_02"
down_revision = "20260818_01"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("candidate_resume_search_hits") as batch_op:
        batch_op.add_column(sa.Column("search_batch_id", sa.String(64), nullable=True))
        batch_op.create_index("ix_candidate_search_hits_batch", ["search_batch_id"])
    with op.batch_alter_table("candidate_resume_source_locators") as batch_op:
        batch_op.add_column(sa.Column("search_batch_id", sa.String(64), nullable=True))
        batch_op.create_index("ix_candidate_source_locators_batch", ["search_batch_id"])
    with op.batch_alter_table("candidate_resume_daily_report_runs") as batch_op:
        batch_op.add_column(sa.Column("search_batch_id", sa.String(64), nullable=True))
        batch_op.create_unique_constraint(
            "uq_candidate_daily_report_search_batch",
            ["search_batch_id"],
        )


def downgrade() -> None:
    with op.batch_alter_table("candidate_resume_daily_report_runs") as batch_op:
        batch_op.drop_constraint(
            "uq_candidate_daily_report_search_batch",
            type_="unique",
        )
        batch_op.drop_column("search_batch_id")
    with op.batch_alter_table("candidate_resume_source_locators") as batch_op:
        batch_op.drop_index("ix_candidate_source_locators_batch")
        batch_op.drop_column("search_batch_id")
    with op.batch_alter_table("candidate_resume_search_hits") as batch_op:
        batch_op.drop_index("ix_candidate_search_hits_batch")
        batch_op.drop_column("search_batch_id")
