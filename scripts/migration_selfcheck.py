from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree

from liepin_rpa.config import get_settings
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.device.diagnose import run_device_diagnosis
from liepin_rpa.page_detection.runtime import build_page_detector
from liepin_rpa.profile_collection import CandidateProfileCollector
from liepin_rpa.search_execution import FrozenKeywordCatalog
from liepin_rpa.search_execution.result_filter import ResultPageFilter
from liepin_rpa.search_execution.workflow import SearchWorkflowRunner
from liepin_rpa.task_generation.models import SearchTaskSeed
from liepin_rpa.ui_controls import find_candidate_card_centers


@dataclass(slots=True)
class StageResult:
    name: str
    ok: bool
    detail: str
    artifacts: dict[str, object]
    duration_seconds: float


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--brand", default="珀莱雅", help="Frozen brand keyword for live search.")
    parser.add_argument("--role", default="达播总监", help="Frozen role keyword for live search.")
    parser.add_argument(
        "--output-dir",
        default="outputs/migration_selfcheck",
        help="Base directory for self-check artifacts.",
    )
    parser.add_argument(
        "--skip-live-profile",
        action="store_true",
        help="Skip the candidate profile collection stage.",
    )
    return parser.parse_args()


def run_command(*args: str) -> str:
    result = subprocess.run(
        list(args),
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return (result.stdout or result.stderr).strip()


def run_stage(name: str, runner) -> StageResult:
    started = time.perf_counter()
    try:
        detail, artifacts = runner()
        return StageResult(
            name=name,
            ok=True,
            detail=detail,
            artifacts=artifacts,
            duration_seconds=round(time.perf_counter() - started, 2),
        )
    except Exception as exc:
        return StageResult(
            name=name,
            ok=False,
            detail=str(exc),
            artifacts={},
            duration_seconds=round(time.perf_counter() - started, 2),
        )


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()

    output_root = Path(args.output_dir) / datetime.now().strftime("%Y%m%d_%H%M%S")
    output_root.mkdir(parents=True, exist_ok=True)

    settings = get_settings()
    detector = build_page_detector()
    adb_runner = AdbRunner(settings.adb_executable, device_id=settings.device_id)

    results: list[StageResult] = []

    def environment_stage() -> tuple[str, dict[str, object]]:
        python_version = run_command(sys.executable, "--version")
        pip_version = run_command(sys.executable, "-m", "pip", "-V")
        pytest_version = run_command(sys.executable, "-m", "pytest", "-V")
        return (
            "Python, pip, and pytest are callable from the migrated virtual environment.",
            {
                "python_version": python_version,
                "pip_version": pip_version,
                "pytest_version": pytest_version,
                "python_executable": sys.executable,
            },
        )

    def package_stage() -> tuple[str, dict[str, object]]:
        import liepin_rpa

        package_path = Path(liepin_rpa.__file__).resolve()
        expected_root = Path.cwd().resolve()
        if expected_root not in package_path.parents:
            raise RuntimeError(f"Package still resolves outside workspace: {package_path}")
        return (
            "Editable install resolves to the migrated workspace.",
            {"package_path": str(package_path)},
        )

    def structure_stage() -> tuple[str, dict[str, object]]:
        required = [
            "config",
            "src",
            "scripts",
            "outputs",
            "artifacts",
            "logs",
            "pyproject.toml",
        ]
        missing = [path for path in required if not Path(path).exists()]
        if missing:
            raise RuntimeError(f"Missing required project paths: {missing}")
        sample_xml_count = len(list(Path("outputs").glob("**/hierarchy.xml")))
        return (
            "Required project directories are present and historical XML samples remain available.",
            {"sample_hierarchy_xml_count": sample_xml_count},
        )

    def diagnosis_stage() -> tuple[str, dict[str, object]]:
        artifacts = run_device_diagnosis(settings)
        adb_runner.clear_uiautomator_sessions()
        return (
            "ADB and uiautomator2 can reach the connected Android device.",
            {
                "report_path": str(artifacts.report_path),
                "screenshot_path": str(artifacts.screenshot_path),
                "hierarchy_path": str(artifacts.hierarchy_path),
            },
        )

    def controls_stage() -> tuple[str, dict[str, object]]:
        device = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)
        device.connect()
        current_app = device.current_app()
        stage_dir = output_root / "controls"
        stage_dir.mkdir(parents=True, exist_ok=True)
        screenshot_path = device.screenshot(stage_dir / "screen.png")
        hierarchy_path = device.dump_hierarchy(stage_dir / "hierarchy.xml")
        detection = detector.detect_from_xml(hierarchy_path)
        adb_runner.clear_uiautomator_sessions()
        return (
            "Current page hierarchy can be captured and classified in the migrated workspace.",
            {
                "package": current_app.get("package"),
                "activity": current_app.get("activity"),
                "detected_state": detection.state.value,
                "matched_signature": detection.matched_signature,
                "screenshot_path": str(screenshot_path),
                "hierarchy_path": str(hierarchy_path),
            },
        )

    def workflow_stage() -> tuple[str, dict[str, object]]:
        catalog = FrozenKeywordCatalog(
            brands_path=Path("config/brands.yaml"),
            roles_path=Path("config/roles.yaml"),
        )
        seed = SearchTaskSeed(brand_keyword=args.brand, role_keyword=args.role)
        catalog.validate_seed(seed)
        device = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)
        device.connect()
        report = SearchWorkflowRunner(ResultPageFilter(device=device)).run(
            seeds=[seed],
            output_dir=output_root / "search_workflow",
        )
        adb_runner.clear_uiautomator_sessions()
        return (
            "Keyword workflow completed through search, result filtering, candidate detail collection, and result-page continuation.",
            {
                "report_path": report.report_path,
                "total_keywords": report.total_keywords,
                "matched_keywords": report.matched_keywords,
                "profiles_collected": report.profiles_collected,
            },
        )

    def result_filter_stage() -> tuple[str, dict[str, object]]:
        seed = SearchTaskSeed(brand_keyword=args.brand, role_keyword=args.role)
        device = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)
        device.connect()
        report = ResultPageFilter(device=device).search_and_filter_seed(
            seed=seed,
            output_dir=output_root / "result_filter",
        )
        adb_runner.clear_uiautomator_sessions()
        return (
            "Result-page screening completed on the migrated workspace.",
            {
                "matched": report.matched,
                "matched_candidate": report.matched_candidate,
                "screened_candidates": report.screened_candidates,
                "iterations": len(report.iterations),
                "end_reason": report.end_reason,
                "report_path": str(output_root / "result_filter" / "report.json"),
            },
        )

    def profile_stage() -> tuple[str, dict[str, object]]:
        device = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)
        device.connect()
        probe_dir = output_root / "profile_prepare"
        probe_dir.mkdir(parents=True, exist_ok=True)
        hierarchy_path = device.dump_hierarchy(probe_dir / "current.xml")
        root = ElementTree.fromstring(hierarchy_path.read_text(encoding="utf-8"))
        card_centers = find_candidate_card_centers(root)
        if not card_centers:
            raise RuntimeError("No candidate card found on current page while preparing profile collection.")
        card_centers.sort(key=lambda item: (item[1], item[0]))
        device.tap(*card_centers[0])
        time.sleep(2.0)
        report = CandidateProfileCollector(device=device).collect_current_profile(output_root / "profile")
        adb_runner.clear_uiautomator_sessions()
        return (
            "Candidate profile collection reached bottom after expand-all and swipe iterations.",
            {
                "report_path": report.report_path,
                "raw_text_path": report.raw_text_path,
                "markdown_path": report.markdown_path,
                "total_iterations": report.total_iterations,
                "total_expand_clicks": report.total_expand_clicks,
                "reached_bottom": report.reached_bottom,
            },
        )

    results.append(run_stage("environment", environment_stage))
    results.append(run_stage("package_path", package_stage))
    results.append(run_stage("project_structure", structure_stage))
    results.append(run_stage("device_diagnosis", diagnosis_stage))
    results.append(run_stage("controls_capture", controls_stage))
    results.append(run_stage("search_workflow", workflow_stage))
    results.append(run_stage("result_filter", result_filter_stage))
    if not args.skip_live_profile:
        results.append(run_stage("profile_collection", profile_stage))

    summary = {
        "generated_at": datetime.now().isoformat(),
        "workspace": str(Path.cwd().resolve()),
        "output_root": str(output_root.resolve()),
        "brand": args.brand,
        "role": args.role,
        "all_ok": all(item.ok for item in results),
        "stages": [asdict(item) for item in results],
    }
    summary_path = output_root / "summary.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["all_ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
