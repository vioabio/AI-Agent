from __future__ import annotations

from liepin_rpa.config import get_settings
from liepin_rpa.logging_utils import configure_logging


def bootstrap_runtime() -> None:
    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)
