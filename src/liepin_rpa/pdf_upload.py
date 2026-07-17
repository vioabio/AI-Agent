from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote

from liepin_rpa.config import Settings
from liepin_rpa.logging_utils import get_logger

LOGGER = get_logger(__name__)


@dataclass(slots=True)
class PdfUploadResult:
    uploaded: bool
    public_url: str | None
    error: str | None = None


def upload_pdf_if_configured(settings: Settings, pdf_path: Path | None) -> PdfUploadResult:
    if pdf_path is None:
        return PdfUploadResult(uploaded=False, public_url=None, error="pdf_not_generated")
    if not pdf_path.exists():
        return PdfUploadResult(uploaded=False, public_url=None, error="pdf_file_missing")
    if not _has_upload_config(settings):
        return PdfUploadResult(uploaded=False, public_url=None, error="upload_not_configured")

    host = settings.pdf_upload_host or ""
    user = settings.pdf_upload_user or ""
    remote_dir = settings.pdf_upload_remote_dir.rstrip("/")
    remote_target = f"{user}@{host}:{remote_dir}/{pdf_path.name}"
    ssh_base = _ssh_base_args(settings)

    try:
        subprocess.run(
            ["ssh", *ssh_base, f"{user}@{host}", "mkdir", "-p", remote_dir],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        subprocess.run(
            ["scp", *(_scp_base_args(settings)), str(pdf_path), remote_target],
            check=True,
            capture_output=True,
            text=True,
            timeout=120,
        )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        error_text = _format_subprocess_error(exc)
        LOGGER.warning(
            "Daily PDF upload failed",
            extra={
                "event": "daily_pdf_upload_failed",
                "context": {"pdf_path": str(pdf_path), "error": error_text},
            },
        )
        return PdfUploadResult(uploaded=False, public_url=None, error=error_text)

    public_url = _build_public_url(settings, pdf_path.name)
    LOGGER.info(
        "Daily PDF uploaded successfully",
        extra={
            "event": "daily_pdf_uploaded",
            "context": {"pdf_path": str(pdf_path), "public_url": public_url},
        },
    )
    return PdfUploadResult(uploaded=True, public_url=public_url)


def _has_upload_config(settings: Settings) -> bool:
    return bool(settings.pdf_upload_host and settings.pdf_upload_user)


def _ssh_base_args(settings: Settings) -> list[str]:
    args = [
        "-p",
        str(settings.pdf_upload_port),
        "-o",
        "BatchMode=yes",
        "-o",
        "StrictHostKeyChecking=accept-new",
    ]
    if settings.pdf_upload_private_key:
        args.extend(["-i", str(settings.pdf_upload_private_key)])
    return args


def _scp_base_args(settings: Settings) -> list[str]:
    args = [
        "-P",
        str(settings.pdf_upload_port),
        "-o",
        "BatchMode=yes",
        "-o",
        "StrictHostKeyChecking=accept-new",
    ]
    if settings.pdf_upload_private_key:
        args.extend(["-i", str(settings.pdf_upload_private_key)])
    return args


def _build_public_url(settings: Settings, filename: str) -> str | None:
    if not settings.pdf_public_base_url:
        return None
    return f"{settings.pdf_public_base_url.rstrip('/')}/{quote(filename)}"


def _format_subprocess_error(exc: subprocess.CalledProcessError | subprocess.TimeoutExpired) -> str:
    if isinstance(exc, subprocess.TimeoutExpired):
        return f"timeout after {exc.timeout}s"
    stderr = (exc.stderr or "").strip()
    stdout = (exc.stdout or "").strip()
    detail = stderr or stdout or str(exc)
    return detail[:500]
