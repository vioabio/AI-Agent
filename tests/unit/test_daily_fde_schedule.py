import json
import subprocess
from pathlib import Path


PROJECT_ROOT = Path(__file__).parents[2]
DAILY_SCRIPT = PROJECT_ROOT / "scripts" / "run_daily_search.ps1"


def _resolve_dispatch(tmp_path: Path, state: dict[str, object]) -> dict[str, str]:
    artifacts = tmp_path / "artifacts"
    artifacts.mkdir(parents=True)
    (artifacts / "search_workflow_run_state.json").write_text(
        json.dumps(state, ensure_ascii=False),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(DAILY_SCRIPT),
            "-ProjectRoot",
            str(tmp_path),
            "-ResolveOnly",
        ],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8-sig",
    )
    return json.loads(completed.stdout.strip())


def _run_state(*, status: str, profile_id: str, checkpoint_file: str) -> dict[str, object]:
    return {
        "run_id": "test-run",
        "status": status,
        "started_at": 1,
        "context": {
            "job_profile_id": profile_id,
            "checkpoint_file": checkpoint_file,
        },
    }


def test_daily_search_resumes_incomplete_fde_batch(tmp_path: Path) -> None:
    dispatch = _resolve_dispatch(
        tmp_path,
        _run_state(
            status="interrupted",
            profile_id="fde_ai_deployment_engineer_v1",
            checkpoint_file=(
                "artifacts\\fde_ai_deployment_engineer_20260827_checkpoint.json"
            ),
        ),
    )

    assert dispatch == {
        "RunDate": "20260827",
        "JobKey": "fde",
        "Reason": "resume_incomplete",
    }


def test_daily_search_alternates_from_fde_to_ai_development(tmp_path: Path) -> None:
    dispatch = _resolve_dispatch(
        tmp_path,
        _run_state(
            status="completed",
            profile_id="fde_ai_deployment_engineer_v1",
            checkpoint_file=(
                "artifacts\\fde_ai_deployment_engineer_20260827_checkpoint.json"
            ),
        ),
    )

    assert dispatch["JobKey"] == "ai_development"
    assert dispatch["Reason"] == "alternate_after_fde"


def test_daily_search_alternates_from_ai_development_to_fde(tmp_path: Path) -> None:
    dispatch = _resolve_dispatch(
        tmp_path,
        _run_state(
            status="completed",
            profile_id="ai_development_engineer_v1",
            checkpoint_file=(
                "artifacts\\ai_development_engineer_20260827_checkpoint.json"
            ),
        ),
    )

    assert dispatch["JobKey"] == "fde"
    assert dispatch["Reason"] == "alternate_after_ai_development"


def test_single_job_runner_uses_safe_standalone_resume() -> None:
    script = (PROJECT_ROOT / "scripts" / "run_single_ai_job_search.ps1").read_text(
        encoding="utf-8"
    )

    assert "fde_ai_deployment_engineer_draft.yaml" in script
    assert "ai_development_engineer.yaml" in script
    assert '"--standalone-job"' in script
    assert '"--daily-budget"' in script
    assert "ValidateOnly" in script
    assert "--send-report" not in script
    assert "--no-resume" not in script
    assert "--reset-checkpoint" not in script
    assert "run_serial_job_queue.ps1" not in script
