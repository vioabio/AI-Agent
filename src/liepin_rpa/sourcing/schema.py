"""Additive source ledger; no import-time connections or legacy identity rewrites."""
from sqlalchemy import Column, DateTime, ForeignKey, Integer, BigInteger, MetaData, String, Table, Text, UniqueConstraint
from sqlalchemy.dialects.mysql import LONGTEXT

sourcing_metadata = MetaData()
OPTIONS = {"mysql_charset": "utf8mb4", "mysql_collate": "utf8mb4_bin", "mysql_engine": "InnoDB"}

sourcing_runs = Table("sourcing_runs", sourcing_metadata,
    Column("run_id", String(64), primary_key=True),
    Column("mode", String(16), nullable=False),
    Column("job_profile_id", String(64), nullable=False),
    Column("analysis_batch_id", String(64), nullable=False, unique=True),
    Column("config_hash", String(64), nullable=False),
    Column("max_requests", Integer, nullable=False),
    Column("max_tokens", BigInteger, nullable=False),
    Column("max_cost_micros", BigInteger, nullable=False),
    Column("created_at", DateTime, nullable=False), **OPTIONS)

sourcing_platform_runs = Table("sourcing_platform_runs", sourcing_metadata,
    Column("platform_run_id", String(64), primary_key=True),
    Column("run_id", String(64), ForeignKey(sourcing_runs.c.run_id), nullable=False),
    Column("platform", String(16), nullable=False),
    Column("account_alias", String(32), nullable=False),
    Column("platform_job_ref", String(255), nullable=True),
    Column("created_at", DateTime, nullable=False),
    UniqueConstraint("run_id", "platform", "account_alias", name="uniq_sourcing_run_account"), **OPTIONS)

candidate_source_identities = Table("candidate_source_identities", sourcing_metadata,
    Column("identity_key", String(64), primary_key=True),
    Column("candidate_fingerprint", String(64), nullable=False, unique=True),
    Column("mode", String(16), nullable=False),
    Column("platform", String(16), nullable=False),
    Column("account_alias", String(32), nullable=False),
    Column("identity_kind", String(32), nullable=False),
    Column("source_ref", String(255), nullable=True),
    Column("created_at", DateTime, nullable=False), **OPTIONS)

sourcing_observations = Table("sourcing_observations", sourcing_metadata,
    Column("event_key", String(64), primary_key=True),
    Column("platform_run_id", String(64), ForeignKey(sourcing_platform_runs.c.platform_run_id), nullable=False),
    Column("identity_key", String(64), ForeignKey(candidate_source_identities.c.identity_key), nullable=False),
    Column("event_id", String(64), nullable=False),
    Column("payload_hash", String(64), nullable=False),
    Column("payload_json", Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
    Column("content_hash", String(64), nullable=True),
    Column("analysis_readiness", String(32), nullable=False),
    Column("captured_at", DateTime, nullable=False),
    Column("created_at", DateTime, nullable=False), **OPTIONS)
