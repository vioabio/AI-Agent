from __future__ import annotations

import argparse
import json
from types import SimpleNamespace

import pytest

from scripts.run_search_workflow import (
    _run_main,
    resolve_finished_run_status,
    resolve_effective_batch_job_id,
    resolve_run_permissions,
    resolve_standalone_batch_context,
)


def _args(*, no_send_message: bool, collect_only: bool) -> argparse.Namespace:
    return argparse.Namespace(
        no_send_message=no_send_message,
        collect_only=collect_only,
    )


def test_default_mode_writes_and_sends() -> None:
    assert resolve_run_permissions(
        _args(no_send_message=False, collect_only=False)
    ) == (True, True)


def test_probe_mode_neither_writes_nor_sends() -> None:
    assert resolve_run_permissions(
        _args(no_send_message=True, collect_only=False)
    ) == (False, False)


def test_collect_only_mode_writes_without_sending() -> None:
    assert resolve_run_permissions(
        _args(no_send_message=False, collect_only=True)
    ) == (True, False)


def test_standalone_job_does_not_join_dual_job_batch() -> None:
    assert resolve_effective_batch_job_id(object(), standalone_job=True) is None


def test_standalone_resume_reuses_previous_batch_identity(tmp_path) -> None:
    state_path = tmp_path / "run_state.json"
    state_path.write_text(
        json.dumps(
            {
                "started_at": 1_700_000_000,
                "context": {
                    "checkpoint_file": "artifacts/fde_checkpoint.json",
                    "job_profile_id": "fde_v1",
                    "search_batch_id": "standalone-fde_v1-existing",
                    "search_batch_job_id": "job_existing",
                },
            }
        ),
        encoding="utf-8",
    )

    batch_id, batch_job_id, _ = resolve_standalone_batch_context(
        state_path=state_path,
        checkpoint_file="artifacts/fde_checkpoint.json",
        job_profile_id="fde_v1",
        run_id="new-run-id",
    )

    assert batch_id == "standalone-fde_v1-existing"
    assert batch_job_id == "job_existing"


def test_jd_smart_write_fails_before_device_access_without_parent_trace(tmp_path) -> None:
    args = argparse.Namespace(
        checkpoint_file=str(tmp_path / "checkpoint.json"),
        no_send_message=False,
        collect_only=False,
    )
    settings = SimpleNamespace(selection_mode="jd_smart")

    with pytest.raises(ValueError, match="require parent batch"):
        _run_main(args, settings)


def test_batch_run_is_not_completed_until_checkpoint_is_complete() -> None:
    assert resolve_finished_run_status(
        exit_code=0,
        is_batch_job=True,
        checkpoint_complete=False,
    ) == "interrupted"
    assert resolve_finished_run_status(
        exit_code=0,
        is_batch_job=True,
        checkpoint_complete=True,
    ) == "completed"
    assert resolve_finished_run_status(
        exit_code=2,
        is_batch_job=True,
        checkpoint_complete=False,
    ) == "failed"


def test_standalone_run_is_not_completed_until_checkpoint_is_complete() -> None:
    assert resolve_finished_run_status(
        exit_code=0,
        is_batch_job=False,
        checkpoint_complete=False,
    ) == "interrupted"
    assert resolve_finished_run_status(
        exit_code=0,
        is_batch_job=False,
        checkpoint_complete=True,
    ) == "completed"
