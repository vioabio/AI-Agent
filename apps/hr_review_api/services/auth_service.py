from __future__ import annotations

import hashlib
import secrets
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError
from sqlalchemy import Engine

from apps.hr_review_api.config import HRReviewSettings
from apps.hr_review_api.domain.errors import (
    AuthenticationError,
    CsrfValidationError,
    LoginRateLimitedError,
)
from apps.hr_review_api.domain.models import AuthenticatedUser, SessionIdentity
from apps.hr_review_api.repositories.auth_repository import AuthRepository

SHANGHAI_TIMEZONE = timezone(timedelta(hours=8))


@dataclass(frozen=True, slots=True)
class LoginResult:
    user: AuthenticatedUser
    session_token: str
    csrf_token: str
    expires_at: datetime


@dataclass(frozen=True, slots=True)
class AuthContext:
    identity: SessionIdentity
    csrf_token_hash: str


class AuthService:
    """本地账号认证、失败限速和服务端 Cookie 会话用例。"""

    def __init__(
        self,
        engine: Engine,
        settings: HRReviewSettings,
        repository: AuthRepository | None = None,
        now_provider: Callable[[], datetime] | None = None,
    ) -> None:
        self.engine = engine
        self.settings = settings
        self.repository = repository or AuthRepository()
        self._now_provider = now_provider or (lambda: datetime.now(SHANGHAI_TIMEZONE))
        self._password_hasher = PasswordHasher()
        self._dummy_hash = self._password_hasher.hash("constant-invalid-password")

    def login(
        self,
        *,
        username: str,
        password: str,
        client_ip: str,
        user_agent: str,
    ) -> LoginResult:
        now = self._now_provider()
        since = now - timedelta(minutes=self.settings.login_failure_window_minutes)
        authenticated_user: AuthenticatedUser | None = None
        session_token = ""
        csrf_token = ""
        expires_at = now
        with self.engine.begin() as connection:
            failures = self.repository.count_recent_failures(
                connection,
                username=username,
                client_ip=client_ip,
                since=_database_time(since),
            )
            if failures >= self.settings.login_max_failures:
                raise LoginRateLimitedError("登录失败次数过多，请稍后再试")
            credentials = self.repository.get_user_credentials(connection, username)
            encoded_hash = credentials[1] if credentials else self._dummy_hash
            valid_password = self._verify_password(encoded_hash, password)
            if credentials is None or not valid_password:
                self.repository.record_login_attempt(
                    connection,
                    username=username,
                    client_ip=client_ip,
                    succeeded=False,
                    created_at=_database_time(now),
                )
            else:
                authenticated_user = credentials[0]
                self.repository.record_login_attempt(
                    connection,
                    username=username,
                    client_ip=client_ip,
                    succeeded=True,
                    created_at=_database_time(now),
                )
                session_token = secrets.token_urlsafe(32)
                csrf_token = secrets.token_urlsafe(32)
                expires_at = now + timedelta(hours=self.settings.session_hours)
                self.repository.create_session(
                    connection,
                    user_id=authenticated_user.id,
                    token_hash=_token_hash(session_token),
                    csrf_token_hash=_token_hash(csrf_token),
                    expires_at=_database_time(expires_at),
                    client_ip=client_ip,
                    user_agent=user_agent,
                    created_at=_database_time(now),
                )
        if authenticated_user is None:
            # 失败事件必须先提交，后续请求才能正确执行同一 IP/账号的限速。
            raise AuthenticationError("用户名或密码错误")
        return LoginResult(
            user=authenticated_user,
            session_token=session_token,
            csrf_token=csrf_token,
            expires_at=expires_at,
        )

    def authenticate(self, session_token: str | None) -> AuthContext:
        if not session_token:
            raise AuthenticationError("登录会话已失效，请重新登录")
        now = self._now_provider()
        with self.engine.connect() as connection:
            session = self.repository.get_session(
                connection,
                token_hash=_token_hash(session_token),
                now=_database_time(now),
            )
        if session is None:
            raise AuthenticationError("登录会话已失效，请重新登录")
        return AuthContext(identity=session[0], csrf_token_hash=session[1])

    def verify_csrf(
        self, context: AuthContext, header_token: str | None, cookie_token: str | None
    ) -> None:
        if not header_token or not cookie_token:
            raise CsrfValidationError("缺少 CSRF 安全凭据")
        if not secrets.compare_digest(header_token, cookie_token):
            raise CsrfValidationError("CSRF 安全凭据不匹配")
        if not secrets.compare_digest(_token_hash(header_token), context.csrf_token_hash):
            raise CsrfValidationError("CSRF 安全凭据已失效")

    def logout(self, context: AuthContext) -> None:
        with self.engine.begin() as connection:
            self.repository.revoke_session(
                connection,
                session_id=context.identity.session_id,
                revoked_at=_database_time(self._now_provider()),
            )

    def hash_password(self, password: str) -> str:
        """供受控 CLI 创建本地账号，明文密码不落盘。"""

        return self._password_hasher.hash(password)

    def _verify_password(self, encoded_hash: str, password: str) -> bool:
        try:
            return self._password_hasher.verify(encoded_hash, password)
        except (VerifyMismatchError, VerificationError, InvalidHashError):
            return False


def _token_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _database_time(value: datetime) -> datetime:
    return value.replace(tzinfo=None) if value.tzinfo is not None else value
