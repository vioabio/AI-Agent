from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


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
