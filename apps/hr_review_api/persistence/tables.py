from __future__ import annotations

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Table,
    Text,
    UniqueConstraint,
    func,
    text,
)

from liepin_rpa.db import metadata

SQL_ID = BigInteger().with_variant(Integer, "sqlite")

hr_users = Table(
    "hr_users",
    metadata,
    Column("id", SQL_ID, primary_key=True, autoincrement=True),
    Column("username", String(128), nullable=False, unique=True),
    Column("display_name", String(128), nullable=False),
    Column("password_hash", String(255), nullable=False),
    Column("role", String(32), nullable=False),
    Column("is_active", Boolean, nullable=False, server_default=text("1")),
    Column("can_view_sensitive", Boolean, nullable=False, server_default=text("0")),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)

hr_user_job_permissions = Table(
    "hr_user_job_permissions",
    metadata,
    Column("id", SQL_ID, primary_key=True, autoincrement=True),
    Column("user_id", SQL_ID, ForeignKey("hr_users.id", ondelete="CASCADE"), nullable=False),
    Column("job_profile_id", String(128), nullable=False),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("user_id", "job_profile_id", name="uq_hr_user_job_permission"),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)

hr_sessions = Table(
    "hr_sessions",
    metadata,
    Column("id", SQL_ID, primary_key=True, autoincrement=True),
    Column("user_id", SQL_ID, ForeignKey("hr_users.id", ondelete="CASCADE"), nullable=False),
    Column("token_hash", String(64), nullable=False, unique=True),
    Column("csrf_token_hash", String(64), nullable=False),
    Column("expires_at", DateTime, nullable=False, index=True),
    Column("revoked_at", DateTime, nullable=True),
    Column("client_ip", String(64), nullable=False),
    Column("user_agent", String(255), nullable=False),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)

hr_login_attempts = Table(
    "hr_login_attempts",
    metadata,
    Column("id", SQL_ID, primary_key=True, autoincrement=True),
    Column("username", String(128), nullable=False),
    Column("client_ip", String(64), nullable=False),
    Column("succeeded", Boolean, nullable=False),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Index("ix_hr_login_attempt_lookup", "username", "client_ip", "created_at"),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)

manual_review_events = Table(
    "candidate_resume_manual_review_events",
    metadata,
    Column("id", SQL_ID, primary_key=True, autoincrement=True),
    Column(
        "candidate_fingerprint",
        String(64),
        ForeignKey(
            "candidate_resume_experience_records.candidate_fingerprint", ondelete="RESTRICT"
        ),
        nullable=False,
        index=True,
    ),
    Column("previous_status", String(64), nullable=True),
    Column("review_status", String(64), nullable=False),
    Column("feedback", Text, nullable=False),
    Column("reviewer_id", SQL_ID, ForeignKey("hr_users.id", ondelete="RESTRICT"), nullable=False),
    Column("reviewer_name_snapshot", String(128), nullable=False),
    Column("revision", BigInteger, nullable=False),
    Column("suppress_communication", Boolean, nullable=False),
    Column("suppress_report", Boolean, nullable=False),
    Column("client_ip", String(64), nullable=False),
    Column("user_agent", String(255), nullable=False),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("candidate_fingerprint", "revision", name="uq_candidate_review_revision"),
    Index("ix_review_events_candidate_created", "candidate_fingerprint", "created_at"),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)

sensitive_access_events = Table(
    "candidate_sensitive_access_events",
    metadata,
    Column("id", SQL_ID, primary_key=True, autoincrement=True),
    Column(
        "candidate_fingerprint",
        String(64),
        ForeignKey(
            "candidate_resume_experience_records.candidate_fingerprint", ondelete="RESTRICT"
        ),
        nullable=False,
        index=True,
    ),
    Column("user_id", SQL_ID, ForeignKey("hr_users.id", ondelete="RESTRICT"), nullable=False),
    Column("access_kind", String(32), nullable=False),
    Column("client_ip", String(64), nullable=False),
    Column("user_agent", String(255), nullable=False),
    Column("created_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)
