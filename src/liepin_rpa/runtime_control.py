from __future__ import annotations

import json
import os
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping


class ProcessLeaseError(RuntimeError):
    """Raised when another active process owns the same workflow lease."""


@dataclass(slots=True)
class ProcessLease:
    path: Path
    token: str

    @classmethod
    def acquire(
        cls,
        path: Path,
        *,
        stale_after_seconds: int,
        context: Mapping[str, object] | None = None,
    ) -> "ProcessLease":
        path.parent.mkdir(parents=True, exist_ok=True)
        _archive_stale_lease(path, stale_after_seconds=stale_after_seconds)
        token = uuid.uuid4().hex
        payload = {
            "token": token,
            "pid": os.getpid(),
            "started_at": int(time.time()),
            "context": dict(context or {}),
        }
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
        except FileExistsError as exc:
            detail = _read_json(path)
            raise ProcessLeaseError(
                f"Another process is already running: {path}. Existing lease: {detail}"
            ) from exc
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False)
            handle.flush()
            os.fsync(handle.fileno())
        return cls(path=path, token=token)

    def release(self) -> None:
        payload = _read_json(self.path)
        if payload.get("token") == self.token:
            self.path.unlink(missing_ok=True)


@dataclass(slots=True)
class WorkflowRunStateStore:
    path: Path

    def mark_started(self, *, run_id: str, context: Mapping[str, object]) -> None:
        self._save(
            {
                "run_id": run_id,
                "status": "running",
                "started_at": int(time.time()),
                "context": dict(context),
            }
        )

    def mark_finished(
        self,
        *,
        run_id: str,
        status: str,
        context: Mapping[str, object] | None = None,
    ) -> None:
        payload = _read_json(self.path)
        payload.update(
            {
                "run_id": run_id,
                "status": status,
                "finished_at": int(time.time()),
                "context": {**dict(payload.get("context") or {}), **dict(context or {})},
            }
        )
        self._save(payload)

    def _save(self, payload: Mapping[str, object]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary_path.write_text(
            json.dumps(dict(payload), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        os.replace(temporary_path, self.path)


def wait_for_process_lease(
    path: Path,
    *,
    timeout_seconds: int,
    stale_after_seconds: int,
    poll_seconds: float = 30.0,
) -> bool:
    deadline = time.monotonic() + max(0, timeout_seconds)
    while path.exists():
        _archive_stale_lease(path, stale_after_seconds=stale_after_seconds)
        if not path.exists():
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(max(1.0, poll_seconds))
    return True


def _archive_stale_lease(path: Path, *, stale_after_seconds: int) -> None:
    if not path.exists() or stale_after_seconds <= 0:
        return
    age_seconds = time.time() - path.stat().st_mtime
    if age_seconds <= stale_after_seconds:
        return
    stale_path = path.with_suffix(path.suffix + f".stale.{int(time.time())}")
    try:
        os.replace(path, stale_path)
    except FileNotFoundError:
        return


def _read_json(path: Path) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return dict(payload) if isinstance(payload, dict) else {}
