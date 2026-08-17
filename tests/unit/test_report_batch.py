from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from liepin_rpa.db_daily_report import _build_report_window, _to_database_local_datetime
from liepin_rpa.report_batch import (
    NoUnreportedSearchBatchError,
    SearchBatchNotReadyError,
    SearchBatchStateError,
    SearchBatchStateStore,
)


def test_dual_job_batch_survives_resume_and_finishes_after_second_job(tmp_path) -> None:
    times = iter(
        [
            datetime(2026, 8, 15, 9, 0, 0),
            datetime(2026, 8, 15, 10, 0, 0),
            datetime(2026, 8, 15, 12, 0, 0),
            datetime(2026, 8, 15, 13, 0, 0),
            datetime(2026, 8, 15, 16, 30, 0),
        ]
    )
    store = SearchBatchStateStore(tmp_path / "batches.json", now_provider=lambda: next(times))

    started = store.begin_or_resume(
        job_id="daren_live_bd_v1",
        checkpoint_file="artifacts/daren.json",
    )
    resumed = store.begin_or_resume(
        job_id="daren_live_bd_v1",
        checkpoint_file="artifacts/daren.json",
    )
    assert resumed["batch_id"] == started["batch_id"]
    assert resumed["started_at"] == "2026-08-15T09:00:00"

    store.mark_job_completed(
        batch_id=str(started["batch_id"]),
        job_id="daren_live_bd_v1",
        checkpoint_file="artifacts/daren.json",
    )
    store.begin_or_resume(
        job_id="finance_shared_management_v1",
        checkpoint_file="artifacts/finance.json",
    )
    completed = store.mark_job_completed(
        batch_id=str(started["batch_id"]),
        job_id="finance_shared_management_v1",
        checkpoint_file="artifacts/finance.json",
    )

    assert completed["status"] == "completed"
    window = store.latest_completed_window()
    assert window.started_at == datetime(2026, 8, 15, 9, 0, 0)
    assert window.ended_at == datetime(2026, 8, 15, 16, 30, 0)


def test_finance_job_cannot_start_without_first_job(tmp_path) -> None:
    store = SearchBatchStateStore(tmp_path / "batches.json")
    with pytest.raises(SearchBatchStateError, match="cannot start"):
        store.begin_or_resume(
            job_id="finance_shared_management_v1",
            checkpoint_file="artifacts/finance.json",
        )


def test_report_defaults_to_latest_completed_batch(tmp_path) -> None:
    store = SearchBatchStateStore(tmp_path / "search_workflow_batches.json")
    store.register_completed(
        batch_id="batch-20260813",
        started_at=datetime(2026, 8, 13, 12, 50, 0),
        ended_at=datetime(2026, 8, 13, 23, 2, 4),
    )

    start_at, end_at, mode, batch_id = _build_report_window(
        SimpleNamespace(artifacts_dir=tmp_path),
        None,
    )

    assert start_at == datetime(2026, 8, 13, 12, 50, 0)
    assert end_at == datetime(2026, 8, 13, 23, 2, 4)
    assert mode == "completed_search_batch"
    assert batch_id == "batch-20260813"


def test_default_report_refuses_previous_batch_while_new_batch_is_active(tmp_path) -> None:
    store = SearchBatchStateStore(tmp_path / "search_workflow_batches.json")
    store.register_completed(
        batch_id="completed-batch",
        started_at=datetime(2026, 8, 13, 12, 50, 0),
        ended_at=datetime(2026, 8, 13, 23, 2, 4),
    )
    store.begin_or_resume(
        job_id="daren_live_bd_v1",
        checkpoint_file="artifacts/next-daren.json",
    )

    with pytest.raises(SearchBatchStateError, match="not complete"):
        store.latest_completed_window()


def test_unreported_batch_remains_reportable_while_next_search_is_active(tmp_path) -> None:
    store = SearchBatchStateStore(tmp_path / "search_workflow_batches.json")
    store.register_completed(
        batch_id="completed-batch",
        started_at=datetime(2026, 8, 13, 12, 50, 0),
        ended_at=datetime(2026, 8, 13, 23, 2, 4),
    )
    store.begin_or_resume(
        job_id="daren_live_bd_v1",
        checkpoint_file="artifacts/next-daren.json",
    )

    window = store.latest_unreported_completed_window()

    assert window.batch_id == "completed-batch"


def test_reported_batch_is_skipped_idempotently(tmp_path) -> None:
    store = SearchBatchStateStore(
        tmp_path / "search_workflow_batches.json",
        now_provider=lambda: datetime(2026, 8, 14, 10, 0, 0),
    )
    store.register_completed(
        batch_id="completed-batch",
        started_at=datetime(2026, 8, 13, 12, 50, 0),
        ended_at=datetime(2026, 8, 13, 23, 2, 4),
    )

    first = store.mark_reported(batch_id="completed-batch", report_key="report-1")
    second = store.mark_reported(batch_id="completed-batch", report_key="report-2")

    assert first["report_status"] == "reported"
    assert first["report_key"] == "report-1"
    assert second["report_key"] == "report-1"
    with pytest.raises(NoUnreportedSearchBatchError, match="No unreported"):
        store.latest_unreported_completed_window()


def test_no_pending_batch_while_search_runs_requests_retry(tmp_path) -> None:
    store = SearchBatchStateStore(tmp_path / "search_workflow_batches.json")
    store.begin_or_resume(
        job_id="daren_live_bd_v1",
        checkpoint_file="artifacts/daren.json",
    )

    with pytest.raises(SearchBatchNotReadyError, match="still running"):
        store.latest_unreported_completed_window()


def test_database_window_keeps_shanghai_local_time_without_subtracting_eight_hours() -> None:
    local_naive = datetime(2026, 8, 13, 12, 50, 0)
    local_aware = local_naive.replace(tzinfo=timezone(timedelta(hours=8)))
    utc_aware = datetime(2026, 8, 13, 4, 50, 0, tzinfo=timezone.utc)

    assert _to_database_local_datetime(local_naive) == local_naive
    assert _to_database_local_datetime(local_aware) == local_naive
    assert _to_database_local_datetime(utc_aware) == local_naive
