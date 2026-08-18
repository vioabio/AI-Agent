"""新增 HR 审核账号、会话、审计事件和候选人 revision。"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "20260818_01"
down_revision = None
branch_labels = None
depends_on = None
SQL_ID = sa.BigInteger().with_variant(sa.Integer(), "sqlite")


def upgrade() -> None:
    op.create_table(
        "hr_users",
        sa.Column("id", SQL_ID, primary_key=True, autoincrement=True),
        sa.Column("username", sa.String(128), nullable=False),
        sa.Column("display_name", sa.String(128), nullable=False),
        sa.Column("password_hash", sa.String(255), nullable=False),
        sa.Column("role", sa.String(32), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.text("1")),
        sa.Column("can_view_sensitive", sa.Boolean(), nullable=False, server_default=sa.text("0")),
        sa.Column(
            "created_at", sa.DateTime(), nullable=False, server_default=sa.func.current_timestamp()
        ),
        sa.Column(
            "updated_at", sa.DateTime(), nullable=False, server_default=sa.func.current_timestamp()
        ),
        sa.UniqueConstraint("username", name="uq_hr_users_username"),
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_unicode_ci",
        mysql_engine="InnoDB",
    )
    op.create_table(
        "hr_user_job_permissions",
        sa.Column("id", SQL_ID, primary_key=True, autoincrement=True),
        sa.Column(
            "user_id",
            SQL_ID,
            sa.ForeignKey("hr_users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("job_profile_id", sa.String(128), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(), nullable=False, server_default=sa.func.current_timestamp()
        ),
        sa.UniqueConstraint("user_id", "job_profile_id", name="uq_hr_user_job_permission"),
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_unicode_ci",
        mysql_engine="InnoDB",
    )
    op.create_table(
        "hr_sessions",
        sa.Column("id", SQL_ID, primary_key=True, autoincrement=True),
        sa.Column(
            "user_id",
            SQL_ID,
            sa.ForeignKey("hr_users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("token_hash", sa.String(64), nullable=False),
        sa.Column("csrf_token_hash", sa.String(64), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("revoked_at", sa.DateTime(), nullable=True),
        sa.Column("client_ip", sa.String(64), nullable=False),
        sa.Column("user_agent", sa.String(255), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(), nullable=False, server_default=sa.func.current_timestamp()
        ),
        sa.UniqueConstraint("token_hash", name="uq_hr_sessions_token_hash"),
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_unicode_ci",
        mysql_engine="InnoDB",
    )
    op.create_index("ix_hr_sessions_expires_at", "hr_sessions", ["expires_at"])
    op.create_table(
        "hr_login_attempts",
        sa.Column("id", SQL_ID, primary_key=True, autoincrement=True),
        sa.Column("username", sa.String(128), nullable=False),
        sa.Column("client_ip", sa.String(64), nullable=False),
        sa.Column("succeeded", sa.Boolean(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(), nullable=False, server_default=sa.func.current_timestamp()
        ),
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_unicode_ci",
        mysql_engine="InnoDB",
    )
    op.create_index(
        "ix_hr_login_attempt_lookup",
        "hr_login_attempts",
        ["username", "client_ip", "created_at"],
    )
    # 现有 AI 列保持只读；人工 revision 与审核人仅作为独立快照补充。
    with op.batch_alter_table("candidate_resume_experience_records") as batch_op:
        batch_op.add_column(sa.Column("manual_reviewed_by", SQL_ID, nullable=True))
        batch_op.add_column(sa.Column("manual_review_assignee_id", SQL_ID, nullable=True))
        batch_op.add_column(
            sa.Column(
                "manual_review_revision",
                sa.BigInteger(),
                nullable=False,
                server_default=sa.text("0"),
            )
        )
        batch_op.create_index("ix_candidate_manual_reviewed_by", ["manual_reviewed_by"])
        batch_op.create_index("ix_candidate_manual_review_assignee", ["manual_review_assignee_id"])
        batch_op.create_foreign_key(
            "fk_candidate_manual_reviewed_by_hr_users",
            "hr_users",
            ["manual_reviewed_by"],
            ["id"],
            ondelete="SET NULL",
        )
        batch_op.create_foreign_key(
            "fk_candidate_manual_assignee_hr_users",
            "hr_users",
            ["manual_review_assignee_id"],
            ["id"],
            ondelete="SET NULL",
        )
    op.create_table(
        "candidate_resume_manual_review_events",
        sa.Column("id", SQL_ID, primary_key=True, autoincrement=True),
        sa.Column(
            "candidate_fingerprint",
            sa.String(64),
            sa.ForeignKey(
                "candidate_resume_experience_records.candidate_fingerprint",
                ondelete="RESTRICT",
            ),
            nullable=False,
        ),
        sa.Column("previous_status", sa.String(64), nullable=True),
        sa.Column("review_status", sa.String(64), nullable=False),
        sa.Column("feedback", sa.Text(), nullable=False),
        sa.Column(
            "reviewer_id",
            SQL_ID,
            sa.ForeignKey("hr_users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("reviewer_name_snapshot", sa.String(128), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("suppress_communication", sa.Boolean(), nullable=False),
        sa.Column("suppress_report", sa.Boolean(), nullable=False),
        sa.Column("client_ip", sa.String(64), nullable=False),
        sa.Column("user_agent", sa.String(255), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(), nullable=False, server_default=sa.func.current_timestamp()
        ),
        sa.UniqueConstraint(
            "candidate_fingerprint", "revision", name="uq_candidate_review_revision"
        ),
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_unicode_ci",
        mysql_engine="InnoDB",
    )
    op.create_index(
        "ix_review_events_candidate_created",
        "candidate_resume_manual_review_events",
        ["candidate_fingerprint", "created_at"],
    )
    op.create_table(
        "candidate_sensitive_access_events",
        sa.Column("id", SQL_ID, primary_key=True, autoincrement=True),
        sa.Column(
            "candidate_fingerprint",
            sa.String(64),
            sa.ForeignKey(
                "candidate_resume_experience_records.candidate_fingerprint",
                ondelete="RESTRICT",
            ),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            SQL_ID,
            sa.ForeignKey("hr_users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("access_kind", sa.String(32), nullable=False),
        sa.Column("client_ip", sa.String(64), nullable=False),
        sa.Column("user_agent", sa.String(255), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(), nullable=False, server_default=sa.func.current_timestamp()
        ),
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_unicode_ci",
        mysql_engine="InnoDB",
    )
    op.create_index(
        "ix_sensitive_access_candidate",
        "candidate_sensitive_access_events",
        ["candidate_fingerprint", "created_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_sensitive_access_candidate", table_name="candidate_sensitive_access_events")
    op.drop_table("candidate_sensitive_access_events")
    op.drop_index(
        "ix_review_events_candidate_created",
        table_name="candidate_resume_manual_review_events",
    )
    op.drop_table("candidate_resume_manual_review_events")
    with op.batch_alter_table("candidate_resume_experience_records") as batch_op:
        batch_op.drop_constraint("fk_candidate_manual_assignee_hr_users", type_="foreignkey")
        batch_op.drop_constraint("fk_candidate_manual_reviewed_by_hr_users", type_="foreignkey")
        batch_op.drop_index("ix_candidate_manual_review_assignee")
        batch_op.drop_index("ix_candidate_manual_reviewed_by")
        batch_op.drop_column("manual_review_revision")
        batch_op.drop_column("manual_review_assignee_id")
        batch_op.drop_column("manual_reviewed_by")
    op.drop_index("ix_hr_login_attempt_lookup", table_name="hr_login_attempts")
    op.drop_table("hr_login_attempts")
    op.drop_index("ix_hr_sessions_expires_at", table_name="hr_sessions")
    op.drop_table("hr_sessions")
    op.drop_table("hr_user_job_permissions")
    op.drop_table("hr_users")
