from __future__ import annotations

from pathlib import Path

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class HRReviewSettings(BaseSettings):
    """HR Web 服务配置；与手机采集配置保持解耦。"""

    app_env: str = Field(default="development", alias="APP_ENV")
    database_url: str = Field(default="", alias="HR_REVIEW_DATABASE_URL")
    session_hours: int = Field(default=8, ge=1, le=24, alias="HR_SESSION_HOURS")
    login_max_failures: int = Field(default=5, ge=1, le=20, alias="HR_LOGIN_MAX_FAILURES")
    login_failure_window_minutes: int = Field(
        default=15,
        ge=1,
        le=120,
        alias="HR_LOGIN_FAILURE_WINDOW_MINUTES",
    )
    cookie_secure: bool = Field(default=False, alias="HR_COOKIE_SECURE")
    allow_http: bool = Field(default=False, alias="HR_ALLOW_HTTP")
    cookie_domain: str | None = Field(default=None, alias="HR_COOKIE_DOMAIN")
    allowed_origins: str = Field(
        default="http://localhost:5173,http://127.0.0.1:5173",
        alias="HR_ALLOWED_ORIGINS",
    )
    enable_openapi: bool = Field(default=True, alias="HR_ENABLE_OPENAPI")
    batch_state_path: Path = Field(
        default=Path("artifacts/search_workflow_batches.json"),
        alias="HR_BATCH_STATE_PATH",
    )

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
        populate_by_name=True,
    )

    @model_validator(mode="after")
    def validate_production_security(self) -> HRReviewSettings:
        if not self.database_url.strip():
            raise ValueError("必须显式设置 HR_REVIEW_DATABASE_URL，禁止回退到采集主库")
        if self.app_env.lower() == "production":
            if not self.cookie_secure and not self.allow_http:
                raise ValueError("生产环境必须设置 HR_COOKIE_SECURE=true")
            if self.enable_openapi:
                raise ValueError("生产环境必须设置 HR_ENABLE_OPENAPI=false")
        return self

    @property
    def origin_list(self) -> list[str]:
        return [item.strip() for item in self.allowed_origins.split(",") if item.strip()]
