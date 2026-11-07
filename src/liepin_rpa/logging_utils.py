from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from logging.handlers import TimedRotatingFileHandler


SENSITIVE_CONTEXT_KEYS = {
    "candidate_card",
    "experience",
    "resume",
    "raw_text",
    "chat_message",
    "message_text",
    "phone",
}


class JsonFormatter(logging.Formatter):
    def __init__(self, *, log_pii: bool) -> None:
        super().__init__()
        self.log_pii = log_pii

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
            payload.update(_sanitize_context(record.context, log_pii=self.log_pii))
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


class ConsoleFormatter(logging.Formatter):
    def __init__(self, *, log_pii: bool) -> None:
        super().__init__()
        self.log_pii = log_pii

    def format(self, record: logging.LogRecord) -> str:
        parts = [record.levelname, record.getMessage()]
        if hasattr(record, "context") and isinstance(record.context, dict) and record.context:
            context = _sanitize_context(record.context, log_pii=self.log_pii)
            context_text = " | ".join(
                f"{key}={value}" for key, value in context.items() if value is not None
            )
            if context_text:
                parts.append(context_text)
        if record.exc_info:
            parts.append(self.formatException(record.exc_info))
        return " | ".join(parts)


def configure_logging(
    log_level: str,
    logs_dir: Path,
    *,
    retention_days: int = 30,
    log_pii: bool = False,
) -> None:
    logs_dir.mkdir(parents=True, exist_ok=True)
    root_logger = logging.getLogger()
    root_logger.setLevel(log_level.upper())
    root_logger.handlers.clear()

    console_formatter = ConsoleFormatter(log_pii=log_pii)
    file_formatter = JsonFormatter(log_pii=log_pii)
    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(console_formatter)
    stream_handler.setLevel(logging.WARNING)

    file_handler = TimedRotatingFileHandler(
        logs_dir / "app.log",
        when="midnight",
        interval=1,
        backupCount=max(1, retention_days),
        encoding="utf-8",
    )
    file_handler.setFormatter(file_formatter)

    root_logger.addHandler(stream_handler)
    root_logger.addHandler(file_handler)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


def _sanitize_context(context: dict[str, object], *, log_pii: bool) -> dict[str, object]:
    if log_pii:
        return dict(context)
    sanitized: dict[str, object] = {}
    for key, value in context.items():
        lowered = key.lower()
        if key == "candidate_name" and isinstance(value, str):
            sanitized[key] = _mask_candidate_name(value)
        elif any(token in lowered for token in SENSITIVE_CONTEXT_KEYS):
            sanitized[key] = "[redacted]"
        else:
            sanitized[key] = value
    return sanitized


def _mask_candidate_name(value: str) -> str:
    normalized = value.strip()
    if not normalized:
        return ""
    if "*" in normalized:
        return normalized
    return normalized[0] + "**"
