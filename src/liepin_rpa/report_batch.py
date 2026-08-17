from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable


BATCH_STATE_FILENAME = "search_workflow_batches.json"
DUAL_JOB_ORDER = ("daren_live_bd_v1", "finance_shared_management_v1")
SHANGHAI_TIMEZONE = timezone(timedelta(hours=8))


class SearchBatchStateError(RuntimeError):
    """Raised when a two-job search batch cannot be advanced safely."""


class SearchBatchNotReadyError(SearchBatchStateError):
    """Raised when no reportable batch exists because search is still running."""


class NoUnreportedSearchBatchError(SearchBatchStateError):
    """Raised when every completed search batch has already been processed."""


@dataclass(frozen=True, slots=True)
class SearchBatchWindow:
    batch_id: str
    started_at: datetime
    ended_at: datetime


class SearchBatchStateStore:
    def __init__(
        self,
        path: Path,
        *,
        now_provider: Callable[[], datetime] = datetime.now,
    ) -> None:
        self.path = path
        self._now_provider = now_provider

    def begin_or_resume(self, *, job_id: str, checkpoint_file: str) -> dict[str, object]:
        self._validate_job_id(job_id)
        payload = self._load()
        active = payload.get("active_batch")
        now = self._local_timestamp()
        if not isinstance(active, dict):
            if job_id != DUAL_JOB_ORDER[0]:
                raise SearchBatchStateError(
                    "The finance job cannot start a new report batch before the talent job."
                )
            active = self._new_batch(now)
            payload["active_batch"] = active

        jobs = active.get("jobs")
        if not isinstance(jobs, dict):
            raise SearchBatchStateError("Active search batch has invalid job state.")
        if job_id == DUAL_JOB_ORDER[1]:
            first_job = jobs.get(DUAL_JOB_ORDER[0])
            if not isinstance(first_job, dict) or first_job.get("status") != "completed":
                raise SearchBatchStateError(
                    "The finance job cannot start until the talent job is complete."
                )

        job = jobs.get(job_id)
        if not isinstance(job, dict):
            raise SearchBatchStateError(f"Active search batch is missing job `{job_id}`.")
        recorded_checkpoint = str(job.get("checkpoint_file") or "")
        if recorded_checkpoint and recorded_checkpoint != checkpoint_file:
            raise SearchBatchStateError(
                f"Job `{job_id}` is already bound to checkpoint `{recorded_checkpoint}`."
            )
        job["checkpoint_file"] = checkpoint_file
        if job.get("status") != "completed":
            job["status"] = "running"
            if not job.get("started_at"):
                job["started_at"] = now
        active["updated_at"] = now
        self._save(payload)
        return dict(active)

    def mark_job_completed(
        self,
        *,
        batch_id: str,
        job_id: str,
        checkpoint_file: str,
    ) -> dict[str, object]:
        self._validate_job_id(job_id)
        payload = self._load()
        active = payload.get("active_batch")
        if not isinstance(active, dict) or active.get("batch_id") != batch_id:
            raise SearchBatchStateError(f"Search batch `{batch_id}` is not active.")
        jobs = active.get("jobs")
        job = jobs.get(job_id) if isinstance(jobs, dict) else None
        if not isinstance(job, dict):
            raise SearchBatchStateError(f"Active search batch is missing job `{job_id}`.")
        recorded_checkpoint = str(job.get("checkpoint_file") or "")
        if recorded_checkpoint and recorded_checkpoint != checkpoint_file:
            raise SearchBatchStateError(
                f"Job `{job_id}` is bound to checkpoint `{recorded_checkpoint}`."
            )

        now = self._local_timestamp()
        job["checkpoint_file"] = checkpoint_file
        job["status"] = "completed"
        job["completed_at"] = now
        active["updated_at"] = now
        if job_id == DUAL_JOB_ORDER[-1]:
            if any(
                not isinstance(jobs.get(required_job), dict)
                or jobs[required_job].get("status") != "completed"
                for required_job in DUAL_JOB_ORDER
            ):
                raise SearchBatchStateError(
                    "The report batch cannot complete until both jobs are complete."
                )
            active["status"] = "completed"
            active["ended_at"] = now
            active["report_status"] = "pending"
            active["reported_at"] = None
            active["report_key"] = None
            completed = payload.setdefault("completed_batches", [])
            if not isinstance(completed, list):
                raise SearchBatchStateError("Completed search batch history is invalid.")
            completed.append(active)
            payload["completed_batches"] = completed[-100:]
            payload["active_batch"] = None
        self._save(payload)
        return dict(active)

    def register_completed(
        self,
        *,
        started_at: datetime,
        ended_at: datetime,
        batch_id: str | None = None,
    ) -> dict[str, object]:
        if ended_at <= started_at:
            raise SearchBatchStateError("Search batch end time must be after start time.")
        payload = self._load()
        completed = payload.setdefault("completed_batches", [])
        if not isinstance(completed, list):
            raise SearchBatchStateError("Completed search batch history is invalid.")
        resolved_batch_id = batch_id or uuid.uuid4().hex
        existing = next(
            (
                item
                for item in completed
                if isinstance(item, dict) and item.get("batch_id") == resolved_batch_id
            ),
            None,
        )
        if existing is not None:
            return dict(existing)
        batch = self._new_batch(_format_local_datetime(started_at), batch_id=resolved_batch_id)
        batch["status"] = "completed"
        batch["ended_at"] = _format_local_datetime(ended_at)
        batch["updated_at"] = batch["ended_at"]
        batch["report_status"] = "pending"
        batch["reported_at"] = None
        batch["report_key"] = None
        jobs = batch["jobs"]
        for job_id in DUAL_JOB_ORDER:
            jobs[job_id]["status"] = "completed"
            jobs[job_id]["completed_at"] = batch["ended_at"]
        completed.append(batch)
        payload["completed_batches"] = completed[-100:]
        self._save(payload)
        return dict(batch)

    def latest_completed_window(self, *, batch_id: str | None = None) -> SearchBatchWindow:
        payload = self._load()
        if batch_id is None and isinstance(payload.get("active_batch"), dict):
            raise SearchBatchStateError(
                "The current dual-job search batch is not complete yet."
            )
        completed = payload.get("completed_batches")
        if not isinstance(completed, list):
            completed = []
        candidates = [item for item in completed if isinstance(item, dict)]
        if batch_id is not None:
            candidates = [item for item in candidates if item.get("batch_id") == batch_id]
        if not candidates:
            label = f" `{batch_id}`" if batch_id else ""
            raise SearchBatchStateError(f"No completed search batch{label} is available.")
        batch = max(candidates, key=lambda item: str(item.get("ended_at") or ""))
        started_at = _parse_local_datetime(batch.get("started_at"))
        ended_at = _parse_local_datetime(batch.get("ended_at"))
        if ended_at <= started_at:
            raise SearchBatchStateError("Completed search batch has an invalid time window.")
        return SearchBatchWindow(
            batch_id=str(batch.get("batch_id") or ""),
            started_at=started_at,
            ended_at=ended_at,
        )

    def latest_unreported_completed_window(
        self,
        *,
        batch_id: str | None = None,
    ) -> SearchBatchWindow:
        """Return the newest completed batch that has not been officially processed.

        A newer active search batch does not hide an older completed batch. This lets
        the scheduled report publish the previous batch while today's search is
        already running.
        """
        payload = self._load()
        completed = payload.get("completed_batches")
        if not isinstance(completed, list):
            completed = []
        candidates = [
            item
            for item in completed
            if isinstance(item, dict)
            and item.get("report_status") != "reported"
            and (batch_id is None or item.get("batch_id") == batch_id)
        ]
        if not candidates:
            if batch_id is not None:
                raise NoUnreportedSearchBatchError(
                    f"Completed search batch `{batch_id}` is unavailable or already reported."
                )
            if isinstance(payload.get("active_batch"), dict):
                raise SearchBatchNotReadyError(
                    "No unreported completed search batch is available; search is still running."
                )
            raise NoUnreportedSearchBatchError(
                "No unreported completed search batch is available."
            )
        batch = max(candidates, key=lambda item: str(item.get("ended_at") or ""))
        started_at = _parse_local_datetime(batch.get("started_at"))
        ended_at = _parse_local_datetime(batch.get("ended_at"))
        if ended_at <= started_at:
            raise SearchBatchStateError("Completed search batch has an invalid time window.")
        return SearchBatchWindow(
            batch_id=str(batch.get("batch_id") or ""),
            started_at=started_at,
            ended_at=ended_at,
        )

    def mark_reported(
        self,
        *,
        batch_id: str,
        report_key: str,
        delivery_status: str = "sent",
    ) -> dict[str, object]:
        """Mark a completed batch processed after an official, idempotent delivery."""
        payload = self._load()
        completed = payload.get("completed_batches")
        if not isinstance(completed, list):
            raise SearchBatchStateError("Completed search batch history is invalid.")
        batch = next(
            (
                item
                for item in completed
                if isinstance(item, dict) and item.get("batch_id") == batch_id
            ),
            None,
        )
        if not isinstance(batch, dict):
            raise SearchBatchStateError(f"Completed search batch `{batch_id}` was not found.")
        if batch.get("report_status") == "reported":
            return dict(batch)
        now = self._local_timestamp()
        batch["report_status"] = "reported"
        batch["reported_at"] = now
        batch["report_key"] = report_key
        batch["report_delivery_status"] = delivery_status
        batch["updated_at"] = now
        self._save(payload)
        return dict(batch)

    def _new_batch(self, started_at: str, *, batch_id: str | None = None) -> dict[str, object]:
        return {
            "batch_id": batch_id or uuid.uuid4().hex,
            "status": "running",
            "started_at": started_at,
            "ended_at": None,
            "updated_at": started_at,
            "jobs": {
                job_id: {
                    "status": "pending",
                    "checkpoint_file": "",
                    "started_at": None,
                    "completed_at": None,
                }
                for job_id in DUAL_JOB_ORDER
            },
        }

    def _load(self) -> dict[str, object]:
        if not self.path.exists():
            return {"schema_version": 1, "active_batch": None, "completed_batches": []}
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise SearchBatchStateError(f"Unable to read search batch state: {exc}") from exc
        if not isinstance(payload, dict):
            raise SearchBatchStateError("Search batch state must be a JSON object.")
        payload.setdefault("schema_version", 1)
        payload.setdefault("active_batch", None)
        payload.setdefault("completed_batches", [])
        return payload

    def _save(self, payload: dict[str, object]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        temporary.replace(self.path)

    def _local_timestamp(self) -> str:
        return _format_local_datetime(self._now_provider())

    @staticmethod
    def _validate_job_id(job_id: str) -> None:
        if job_id not in DUAL_JOB_ORDER:
            raise SearchBatchStateError(f"Job `{job_id}` is not part of the dual-job batch.")


def _format_local_datetime(value: datetime) -> str:
    if value.tzinfo is not None:
        value = value.astimezone(SHANGHAI_TIMEZONE).replace(tzinfo=None)
    return value.isoformat(timespec="seconds")


def _parse_local_datetime(value: object) -> datetime:
    if not isinstance(value, str) or not value.strip():
        raise SearchBatchStateError("Search batch timestamp is missing.")
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(SHANGHAI_TIMEZONE).replace(tzinfo=None)
    return parsed
