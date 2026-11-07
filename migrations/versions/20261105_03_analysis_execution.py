"""Add execution metadata without guessing states for historical analyses."""
from __future__ import annotations

from alembic import context, op
import sqlalchemy as sa

revision = "20261105_03"
down_revision = "20260818_02"
branch_labels = None
depends_on = None

TABLE = "candidate_resume_analysis_cache"


def upgrade() -> None:
    # The base collector tables predate Alembic and may not exist in an HR-only DB.
    columns = set()
    if not context.is_offline_mode():
        inspector = sa.inspect(op.get_bind())
        if not inspector.has_table(TABLE):
            return
        columns = {item["name"] for item in inspector.get_columns(TABLE)}
    with op.batch_alter_table(TABLE) as batch:
        for name, kind in (
            ("execution_status", sa.String(32)), ("error_code", sa.String(64)),
            ("retryable", sa.Boolean()), ("retry_after", sa.DateTime()),
        ):
            if name not in columns:
                batch.add_column(sa.Column(name, kind, nullable=True))


def downgrade() -> None:
    if not context.is_offline_mode() and not sa.inspect(op.get_bind()).has_table(TABLE):
        return
    with op.batch_alter_table(TABLE) as batch:
        for name in ("retry_after", "retryable", "error_code", "execution_status"):
            batch.drop_column(name)
