from __future__ import annotations

import argparse
import json
import sys
import warnings
from pathlib import Path

warnings.filterwarnings(
    "ignore",
    message="authlib.jose module is deprecated, please use joserfc instead.",
)

from liepin_rpa.app_runtime import (
    ensure_liepin_started,
    resolve_liepin_main_activity,
    resolve_liepin_package,
)
from liepin_rpa.config import get_settings
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.db_daily_report import send_database_daily_report
from liepin_rpa.logging_utils import configure_logging, get_logger
from liepin_rpa.search_execution.catalog import FrozenKeywordCatalog
from liepin_rpa.search_execution.result_filter import ResultPageFilter
from liepin_rpa.search_execution.workflow import (
    SearchWorkflowRunner,
    WorkflowCheckpointStore,
    build_seed_key,
)
from liepin_rpa.task_generation.generator import PRIORITY_ROLE_KEYWORDS, generate_search_task_seeds
from liepin_rpa.task_generation.models import SearchTaskSeed

LOGGER = get_logger(__name__)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--brand", default=None, help="Run only this frozen brand keyword.")
    parser.add_argument("--role", default=None, help="Run only this frozen role keyword.")
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Limit how many keyword combinations to execute after filtering.",
    )
    parser.add_argument(
        "--output-dir",
        default="outputs/search_workflow",
        help="Deprecated compatibility argument. Output files are no longer generated.",
    )
    parser.add_argument(
        "--checkpoint-file",
        default="artifacts/search_workflow_checkpoint.json",
        help="Path to the keyword-level checkpoint file used for resume.",
    )
    parser.add_argument(
        "--no-resume",
        action="store_true",
        help="Ignore existing checkpoint and run all selected keywords.",
    )
    parser.add_argument(
        "--reset-checkpoint",
        action="store_true",
        help="Delete the existing checkpoint before this run starts.",
    )
    parser.add_argument(
        "--send-report",
        action="store_true",
        help="Send a database daily report after the search workflow finishes.",
    )
    return parser.parse_args()


def build_seeds(args: argparse.Namespace) -> list[SearchTaskSeed]:
    settings = get_settings()
    catalog = FrozenKeywordCatalog(
        brands_path=Path("config/brands.yaml"),
        roles_path=Path("config/roles.yaml"),
    )
    if args.brand and args.role:
        seed = SearchTaskSeed(brand_keyword=args.brand, role_keyword=args.role)
        catalog.validate_seed(seed)
        return [seed]

    seeds = generate_search_task_seeds(
        brands_path=Path("config/brands.yaml"),
        roles_path=Path("config/roles.yaml"),
    )
    if args.brand:
        seeds = [seed for seed in seeds if seed.brand_keyword == args.brand]
    if args.role:
        seeds = [seed for seed in seeds if seed.role_keyword == args.role]

    for seed in seeds:
        catalog.validate_seed(seed)
    if args.limit is not None:
        seeds = seeds[: args.limit]
    elif settings.workflow_max_keywords_per_run is not None and settings.workflow_max_keywords_per_run > 0:
        seeds = seeds[: settings.workflow_max_keywords_per_run]
    if not seeds:
        raise ValueError("No search task seeds remain after applying the current filters")
    return seeds


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()

    settings = get_settings()
    checkpoint_store = WorkflowCheckpointStore(Path(args.checkpoint_file))
    if args.reset_checkpoint:
        checkpoint_store.reset()
    configure_logging(settings.log_level, settings.logs_dir)
    LOGGER.info(
        "Starting search workflow script",
        extra={
            "event": "workflow_script_start",
            "context": {
                "brand_filter": args.brand,
                "role_filter": args.role,
                "limit": args.limit,
                "checkpoint_file": str(checkpoint_store.path),
                "resume_enabled": not args.no_resume,
                "reset_checkpoint": args.reset_checkpoint,
            },
        },
    )

    seeds = build_seeds(args)
    selected_total = len(seeds)
    skipped_by_resume = 0
    resumed_from_keyword: dict[str, object] | None = None
    if not args.no_resume:
        completed_keys = checkpoint_store.load_completed_keys()
        if completed_keys:
            original_count = len(seeds)
            seeds = [seed for seed in seeds if build_seed_key(seed) not in completed_keys]
            skipped_by_resume = original_count - len(seeds)
        in_progress_keyword = checkpoint_store.load_in_progress_keyword()
        if in_progress_keyword and seeds:
            in_progress_key = str(in_progress_keyword.get("key") or "")
            matching_index = next(
                (i for i, seed in enumerate(seeds) if build_seed_key(seed) == in_progress_key),
                None,
            )
            if matching_index is not None:
                resumed_from_keyword = in_progress_keyword
                in_progress_seed = seeds[matching_index]
                if in_progress_seed.role_keyword in PRIORITY_ROLE_KEYWORDS:
                    seeds = [in_progress_seed] + seeds[:matching_index] + seeds[matching_index + 1 :]

    print(f"本次共需执行 {selected_total} 个关键词", flush=True)
    if skipped_by_resume > 0:
        print(
            f"检测到断点续跑，已跳过前面 {skipped_by_resume} 个关键词，剩余 {len(seeds)} 个待执行",
            flush=True,
        )
    if resumed_from_keyword is not None:
        print(
            "将从上次中断的关键词重新开始："
            f"{resumed_from_keyword.get('brand_keyword')} | {resumed_from_keyword.get('role_keyword')}",
            flush=True,
        )
    if not seeds:
        print("当前筛选范围内的关键词都已执行完成，无需继续跑。", flush=True)
        return 0

    adb_runner = AdbRunner(settings.adb_executable, device_id=settings.device_id)
    adb_runner.clear_uiautomator_sessions()
    device = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)
    device.connect()
    LOGGER.info(
        "Device connected and app startup check begins",
        extra={
            "event": "workflow_device_ready",
            "context": {
                "device_id": settings.device_id,
                "liepin_package": settings.liepin_package,
            },
        },
    )
    ensure_liepin_started(
        device,
        resolve_liepin_package(settings.liepin_package),
        resolve_liepin_main_activity(settings.liepin_main_activity),
    )

    workflow = SearchWorkflowRunner(
        ResultPageFilter(
            device=device,
            settle_seconds=3.0,
            write_to_db=True,
        ),
        checkpoint_store=checkpoint_store,
    )
    report = workflow.run(seeds=seeds, output_dir=Path(args.output_dir))
    db_report_result = (
        send_database_daily_report(settings, use_cache=True, dry_run=False)
        if args.send_report
        else {
            "records_loaded": 0,
            "candidate_outcomes": 0,
            "feishu_sent": False,
            "skipped": "report is handled by the daily scheduled task",
        }
    )
    feishu_sent = bool(db_report_result.get("feishu_sent"))
    LOGGER.info(
        "Search workflow script finished",
        extra={
            "event": "workflow_script_complete",
            "context": {
                "total_keywords": report.total_keywords,
                "matched_keywords": report.matched_keywords,
                "profiles_collected": report.profiles_collected,
                "db_report_records_loaded": db_report_result.get("records_loaded"),
                "db_report_candidate_outcomes": db_report_result.get("candidate_outcomes"),
                "feishu_sent": feishu_sent,
            },
        },
    )
    print(
        json.dumps(
            {
                "total_keywords": report.total_keywords,
                "matched_keywords": report.matched_keywords,
                "profiles_collected": report.profiles_collected,
                "db_report_records_loaded": db_report_result.get("records_loaded"),
                "db_report_candidate_outcomes": db_report_result.get("candidate_outcomes"),
                "feishu_sent": feishu_sent,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
