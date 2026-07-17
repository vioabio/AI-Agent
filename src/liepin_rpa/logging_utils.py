from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        if hasattr(record, "event"):
            payload["event"] = record.event
        if hasattr(record, "context") and isinstance(record.context, dict):
            payload.update(record.context)
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


class ConsoleFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        parts = [record.levelname, record.getMessage()]
        if hasattr(record, "context") and isinstance(record.context, dict) and record.context:
            context_text = " | ".join(
                f"{key}={value}" for key, value in record.context.items() if value is not None
            )
            if context_text:
                parts.append(context_text)
        if record.exc_info:
            parts.append(self.formatException(record.exc_info))
        return " | ".join(parts)


def configure_logging(log_level: str, logs_dir: Path) -> None:
    logs_dir.mkdir(parents=True, exist_ok=True)
    root_logger = logging.getLogger()
    root_logger.setLevel(log_level.upper())
    root_logger.handlers.clear()

    console_formatter = ConsoleFormatter()
    file_formatter = JsonFormatter()
    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(console_formatter)
    stream_handler.setLevel(logging.WARNING)

    file_handler = logging.FileHandler(logs_dir / "app.log", encoding="utf-8")
    file_handler.setFormatter(file_formatter)

    root_logger.addHandler(stream_handler)
    root_logger.addHandler(file_handler)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)
