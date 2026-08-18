from __future__ import annotations

from datetime import datetime

from sqlalchemy import Connection, delete, func, insert, select, update
from sqlalchemy.engine import RowMapping

from apps.hr_review_api.domain.access_policy import UserRole
from apps.hr_review_api.domain.models import AuthenticatedUser, SessionIdentity
from apps.hr_review_api.persistence.tables import (
    hr_login_attempts,
    hr_sessions,
    hr_user_job_permissions,
    hr_users,
)


class AuthRepository:
    """读写本地账号、岗位权限、登录尝试与服务端会话。"""

    def get_user_credentials(
        self,
        connection: Connection,
        username: str,
    ) -> tuple[AuthenticatedUser, str] | None:
        row = (
            connection.execute(
                select(
                    hr_users.c.id,
                    hr_users.c.username,
                    hr_users.c.display_name,
                    hr_users.c.password_hash,
                    hr_users.c.role,
                    hr_users.c.can_view_sensitive,
                ).where(
                    hr_users.c.username == username,
                    hr_users.c.is_active.is_(True),
                )
            )
            .mappings()
            .first()
        )
        if row is None:
            return None
        user = self._build_user(connection, row)
        return user, str(row["password_hash"])

    def get_user(self, connection: Connection, user_id: int) -> AuthenticatedUser | None:
        row = (
            connection.execute(
                select(
                    hr_users.c.id,
                    hr_users.c.username,
                    hr_users.c.display_name,
                    hr_users.c.role,
                    hr_users.c.can_view_sensitive,
                ).where(hr_users.c.id == user_id, hr_users.c.is_active.is_(True))
            )
            .mappings()
            .first()
        )
        return self._build_user(connection, row) if row is not None else None

    def count_recent_failures(
        self,
        connection: Connection,
        *,
        username: str,
        client_ip: str,
        since: datetime,
    ) -> int:
        value = connection.scalar(
            select(func.count())
            .select_from(hr_login_attempts)
            .where(
                hr_login_attempts.c.username == username,
                hr_login_attempts.c.client_ip == client_ip,
                hr_login_attempts.c.succeeded.is_(False),
                hr_login_attempts.c.created_at >= since,
            )
        )
        return int(value or 0)

    def record_login_attempt(
        self,
        connection: Connection,
        *,
        username: str,
        client_ip: str,
        succeeded: bool,
        created_at: datetime,
    ) -> None:
        connection.execute(
            insert(hr_login_attempts).values(
                username=username,
                client_ip=client_ip,
                succeeded=succeeded,
                created_at=created_at,
            )
        )
        if succeeded:
            connection.execute(
                delete(hr_login_attempts).where(
                    hr_login_attempts.c.username == username,
                    hr_login_attempts.c.client_ip == client_ip,
                    hr_login_attempts.c.succeeded.is_(False),
                )
            )

    def create_session(
        self,
        connection: Connection,
        *,
        user_id: int,
        token_hash: str,
        csrf_token_hash: str,
        expires_at: datetime,
        client_ip: str,
        user_agent: str,
        created_at: datetime,
    ) -> int:
        result = connection.execute(
            insert(hr_sessions).values(
                user_id=user_id,
                token_hash=token_hash,
                csrf_token_hash=csrf_token_hash,
                expires_at=expires_at,
                client_ip=client_ip,
                user_agent=user_agent[:255],
                created_at=created_at,
            )
        )
        inserted_primary_key = result.inserted_primary_key
        if inserted_primary_key is None:
            raise RuntimeError("创建会话后未返回主键")
        primary_key = inserted_primary_key[0]
        if primary_key is None:
            raise RuntimeError("创建会话后未返回主键")
        return int(primary_key)

    def get_session(
        self,
        connection: Connection,
        *,
        token_hash: str,
        now: datetime,
    ) -> tuple[SessionIdentity, str] | None:
        row = (
            connection.execute(
                select(
                    hr_sessions.c.id,
                    hr_sessions.c.user_id,
                    hr_sessions.c.csrf_token_hash,
                    hr_sessions.c.expires_at,
                ).where(
                    hr_sessions.c.token_hash == token_hash,
                    hr_sessions.c.revoked_at.is_(None),
                    hr_sessions.c.expires_at > now,
                )
            )
            .mappings()
            .first()
        )
        if row is None:
            return None
        user = self.get_user(connection, int(row["user_id"]))
        if user is None:
            return None
        identity = SessionIdentity(
            user=user,
            session_id=int(row["id"]),
            expires_at=row["expires_at"],
        )
        return identity, str(row["csrf_token_hash"])

    def revoke_session(
        self,
        connection: Connection,
        *,
        session_id: int,
        revoked_at: datetime,
    ) -> None:
        connection.execute(
            update(hr_sessions)
            .where(hr_sessions.c.id == session_id, hr_sessions.c.revoked_at.is_(None))
            .values(revoked_at=revoked_at)
        )

    @staticmethod
    def _build_user(
        connection: Connection,
        row: RowMapping,
    ) -> AuthenticatedUser:
        mapping = row
        user_id = int(mapping["id"])
        job_rows = connection.execute(
            select(hr_user_job_permissions.c.job_profile_id).where(
                hr_user_job_permissions.c.user_id == user_id
            )
        ).scalars()
        return AuthenticatedUser(
            id=user_id,
            username=str(mapping["username"]),
            display_name=str(mapping["display_name"]),
            role=UserRole(str(mapping["role"])),
            allowed_job_profile_ids=frozenset(str(value) for value in job_rows),
            can_view_sensitive=bool(mapping["can_view_sensitive"]),
        )
