"""Add source identities and immutable observations; leave legacy identities untouched."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.mysql import LONGTEXT

revision = "20261107_09"
down_revision = "20261105_08"
branch_labels = None
depends_on = None
OPTIONS = dict(mysql_charset="utf8mb4", mysql_collate="utf8mb4_bin", mysql_engine="InnoDB")


def upgrade():
    op.create_table("sourcing_runs",
        sa.Column("run_id", sa.String(64), primary_key=True),
        sa.Column("mode", sa.String(16), nullable=False),
        sa.Column("job_profile_id", sa.String(64), nullable=False),
        sa.Column("analysis_batch_id", sa.String(64), nullable=False, unique=True),
        sa.Column("config_hash", sa.String(64), nullable=False),
        sa.Column("max_requests", sa.Integer(), nullable=False),
        sa.Column("max_tokens", sa.BigInteger(), nullable=False),
        sa.Column("max_cost_micros", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False), **OPTIONS)
    op.create_table("sourcing_platform_runs",
        sa.Column("platform_run_id", sa.String(64), primary_key=True),
        sa.Column("run_id", sa.String(64), sa.ForeignKey("sourcing_runs.run_id"), nullable=False),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("account_alias", sa.String(32), nullable=False),
        sa.Column("platform_job_ref", sa.String(255), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.UniqueConstraint("run_id", "platform", "account_alias", name="uniq_sourcing_run_account"), **OPTIONS)
    op.create_table("candidate_source_identities",
        sa.Column("identity_key", sa.String(64), primary_key=True),
        sa.Column("candidate_fingerprint", sa.String(64), nullable=False, unique=True),
        sa.Column("mode", sa.String(16), nullable=False),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("account_alias", sa.String(32), nullable=False),
        sa.Column("identity_kind", sa.String(32), nullable=False),
        sa.Column("source_ref", sa.String(255), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False), **OPTIONS)
    op.create_table("sourcing_observations",
        sa.Column("event_key", sa.String(64), primary_key=True),
        sa.Column("platform_run_id", sa.String(64), sa.ForeignKey("sourcing_platform_runs.platform_run_id"), nullable=False),
        sa.Column("identity_key", sa.String(64), sa.ForeignKey("candidate_source_identities.identity_key"), nullable=False),
        sa.Column("event_id", sa.String(64), nullable=False),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("payload_json", sa.Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
        sa.Column("content_hash", sa.String(64), nullable=True),
        sa.Column("analysis_readiness", sa.String(32), nullable=False),
        sa.Column("captured_at", sa.DateTime(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False), **OPTIONS)


def downgrade():
    # Explicit operator rollback only; normal stop/retry never deletes the ledger.
    op.drop_table("sourcing_observations")
    op.drop_table("candidate_source_identities")
    op.drop_table("sourcing_platform_runs")
    op.drop_table("sourcing_runs")
