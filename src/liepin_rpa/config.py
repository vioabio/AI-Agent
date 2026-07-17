from __future__ import annotations

import warnings
from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

warnings.filterwarnings(
    "ignore",
    message=".*authlib\\.jose module is deprecated, please use joserfc instead\\..*",
)

try:
    from cozepy import COZE_CN_BASE_URL
except Exception:  # pragma: no cover - optional import during bootstrap
    COZE_CN_BASE_URL = "https://api.coze.cn"


class Settings(BaseSettings):
    app_env: str = Field(default="development", alias="APP_ENV")
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")
    artifacts_dir: Path = Field(default=Path("artifacts"), alias="ARTIFACTS_DIR")
    logs_dir: Path = Field(default=Path("logs"), alias="LOGS_DIR")
    device_id: str | None = Field(default=None, alias="DEVICE_ID")
    adb_executable: str = Field(default="adb", alias="ADB_EXECUTABLE")
    liepin_package: str | None = Field(default=None, alias="LIEPIN_PACKAGE")
    liepin_main_activity: str | None = Field(default=None, alias="LIEPIN_MAIN_ACTIVITY")
    mysql_host: str | None = Field(default=None, alias="MYSQL_HOST")
    mysql_port: int = Field(default=3306, alias="MYSQL_PORT")
    mysql_user: str | None = Field(default=None, alias="MYSQL_USER")
    mysql_password: str | None = Field(default=None, alias="MYSQL_PASSWORD")
    mysql_database: str = Field(default="phone-rpa", alias="MYSQL_DATABASE")
    mysql_charset: str = Field(default="utf8mb4", alias="MYSQL_CHARSET")
    coze_api_token: str | None = Field(default=None, alias="COZE_API_TOKEN")
    coze_api_base_url: str = Field(default=COZE_CN_BASE_URL, alias="COZE_API_BASE_URL")
    coze_workflow_id: str | None = Field(default=None, alias="COZE_WORKFLOW_ID")
    feishu_bot_webhook: str | None = Field(default=None, alias="FEISHU_BOT_WEBHOOK")
    pdf_upload_host: str | None = Field(default=None, alias="PDF_UPLOAD_HOST")
    pdf_upload_port: int = Field(default=22, alias="PDF_UPLOAD_PORT")
    pdf_upload_user: str | None = Field(default=None, alias="PDF_UPLOAD_USER")
    pdf_upload_private_key: Path | None = Field(default=None, alias="PDF_UPLOAD_PRIVATE_KEY")
    pdf_upload_remote_dir: str = Field(
        default="/opt/liepin_daily_report/search_pdf",
        alias="PDF_UPLOAD_REMOTE_DIR",
    )
    pdf_public_base_url: str | None = Field(default=None, alias="PDF_PUBLIC_BASE_URL")
    workflow_min_keyword_delay_seconds: float = Field(
        default=6.0,
        alias="WORKFLOW_MIN_KEYWORD_DELAY_SECONDS",
    )
    workflow_max_keyword_delay_seconds: float = Field(
        default=14.0,
        alias="WORKFLOW_MAX_KEYWORD_DELAY_SECONDS",
    )
    workflow_max_keywords_per_run: int | None = Field(
        default=None,
        alias="WORKFLOW_MAX_KEYWORDS_PER_RUN",
    )
    workflow_max_consecutive_keyword_errors: int = Field(
        default=8,
        alias="WORKFLOW_MAX_CONSECUTIVE_KEYWORD_ERRORS",
    )
    workflow_device_heartbeat_interval: int = Field(
        default=5,
        alias="WORKFLOW_DEVICE_HEARTBEAT_INTERVAL",
    )

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
        populate_by_name=True,
    )

    def ensure_directories(self) -> None:
        self.artifacts_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)

    @property
    def device_diagnose_dir(self) -> Path:
        return self.artifacts_dir / "device_diagnose"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    settings = Settings()
    settings.ensure_directories()
    return settings
