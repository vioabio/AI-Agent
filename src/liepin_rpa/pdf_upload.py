from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path
import posixpath
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

    try:
        if _has_password(settings):
            _upload_with_password(settings, pdf_path, remote_dir)
        else:
            ssh_base = _ssh_base_args(settings)
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
    except (RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
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


def _upload_with_password(settings: Settings, pdf_path: Path, remote_dir: str) -> None:
    try:
        import paramiko
    except ImportError as exc:  # pragma: no cover - deployment dependency guard
        raise RuntimeError("Password upload requires the paramiko package") from exc

    known_hosts = settings.pdf_upload_known_hosts
    if not known_hosts.exists():
        raise RuntimeError(f"Configured SSH known_hosts file does not exist: {known_hosts}")
    password = settings.pdf_upload_password.get_secret_value()
    if not password:
        raise RuntimeError("PDF_UPLOAD_PASSWORD is empty")

    client = paramiko.SSHClient()
    client.load_host_keys(str(known_hosts))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        client.connect(
            hostname=settings.pdf_upload_host or "",
            port=settings.pdf_upload_port,
            username=settings.pdf_upload_user or "",
            password=password,
            look_for_keys=False,
            allow_agent=False,
            timeout=20,
            auth_timeout=20,
            banner_timeout=20,
        )
        sftp = client.open_sftp()
        try:
            _ensure_remote_directory(sftp, remote_dir)
            sftp.put(str(pdf_path), posixpath.join(remote_dir, pdf_path.name))
        finally:
            sftp.close()
    finally:
        client.close()


def _ensure_remote_directory(sftp, remote_dir: str) -> None:
    current = "/" if remote_dir.startswith("/") else ""
    for part in (item for item in remote_dir.split("/") if item):
        current = posixpath.join(current, part)
        try:
            sftp.stat(current)
        except OSError:
            sftp.mkdir(current)


def _has_upload_config(settings: Settings) -> bool:
    return bool(settings.pdf_upload_host and settings.pdf_upload_user)


def _has_password(settings: Settings) -> bool:
    return bool(
        settings.pdf_upload_password
        and settings.pdf_upload_password.get_secret_value().strip()
    )


def _ssh_base_args(settings: Settings) -> list[str]:
    known_hosts = settings.pdf_upload_known_hosts
    if not known_hosts.exists():
        raise RuntimeError(f"Configured SSH known_hosts file does not exist: {known_hosts}")
    args = [
        "-p",
        str(settings.pdf_upload_port),
        "-o",
        "BatchMode=yes",
        "-o",
        "StrictHostKeyChecking=yes",
        "-o",
        f"UserKnownHostsFile={known_hosts}",
    ]
    if settings.pdf_upload_private_key:
        args.extend(["-i", str(settings.pdf_upload_private_key)])
    return args


def _scp_base_args(settings: Settings) -> list[str]:
    known_hosts = settings.pdf_upload_known_hosts
    if not known_hosts.exists():
        raise RuntimeError(f"Configured SSH known_hosts file does not exist: {known_hosts}")
    args = [
        "-P",
        str(settings.pdf_upload_port),
        "-o",
        "BatchMode=yes",
        "-o",
        "StrictHostKeyChecking=yes",
        "-o",
        f"UserKnownHostsFile={known_hosts}",
    ]
    if settings.pdf_upload_private_key:
        args.extend(["-i", str(settings.pdf_upload_private_key)])
    return args


def _build_public_url(settings: Settings, filename: str) -> str | None:
    if not settings.pdf_public_base_url:
        return None
    return f"{settings.pdf_public_base_url.rstrip('/')}/{quote(filename)}"


def _format_subprocess_error(
    exc: RuntimeError | subprocess.CalledProcessError | subprocess.TimeoutExpired,
) -> str:
    if isinstance(exc, RuntimeError):
        return str(exc)[:500]
    if isinstance(exc, subprocess.TimeoutExpired):
        return f"timeout after {exc.timeout}s"
    stderr = (exc.stderr or "").strip()
    stdout = (exc.stdout or "").strip()
    detail = stderr or stdout or str(exc)
    return detail[:500]
