from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import uuid
import warnings
from datetime import datetime
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
from liepin_rpa.report_batch import (
    BATCH_STATE_FILENAME,
    DUAL_JOB_ORDER,
    SearchBatchStateStore,
)
from liepin_rpa.batch_trace import stable_batch_job_id
from liepin_rpa.search_execution.catalog import FrozenKeywordCatalog
from liepin_rpa.search_execution.result_filter import ResultPageFilter
from liepin_rpa.search_execution.workflow import (
    SearchWorkflowRunner,
    WorkflowCheckpointStore,
    build_seed_key,
)
from liepin_rpa.runtime_control import ProcessLease, ProcessLeaseError, WorkflowRunStateStore
from liepin_rpa.task_generation.generator import (
    generate_jd_smart_search_task_seeds,
    generate_search_task_seeds,
)
from liepin_rpa.task_generation.config_loader import load_job_profile
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
        "--daily-budget",
        type=int,
        default=None,
        help=(
            "Override the number of remaining keywords to execute without changing "
            "the checkpoint catalog. Intended for resuming an interrupted daily run."
        ),
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
    parser.add_argument(
        "--standalone-job",
        action="store_true",
        help=(
            "Run the selected job outside the dual-job report batch. "
            "This explicit mode never permits --send-report."
        ),
    )
    parser.add_argument(
        "--no-send-message",
        "--dry-run",
        dest="no_send_message",
        action="store_true",
        help=(
            "Safe probe mode: do not write database records, click communication buttons, "
            "or send chat messages."
        ),
    )
    parser.add_argument(
        "--collect-only",
        action="store_true",
        help=(
            "Collect and analyze resumes and write database records, but never click "
            "communication buttons or send chat messages."
        ),
    )
    parser.add_argument(
        "--target-candidate-fingerprint",
        action="append",
        default=[],
        help=(
            "Only allow message sending for this exact candidate fingerprint. "
            "May be provided more than once. Other matched candidates are never contacted."
        ),
    )
    args = parser.parse_args()
    if args.no_send_message and args.collect_only:
        parser.error("--collect-only cannot be combined with --no-send-message/--dry-run")
    if args.target_candidate_fingerprint and (args.no_send_message or args.collect_only):
        parser.error("--target-candidate-fingerprint requires message sending to be enabled")
    return args


def resolve_run_permissions(args: argparse.Namespace) -> tuple[bool, bool]:
    """Return (write_to_db, send_messages) for the requested execution mode."""
    write_to_db = not args.no_send_message
    send_messages = write_to_db and not args.collect_only
    return write_to_db, send_messages


def build_seeds(args: argparse.Namespace) -> list[SearchTaskSeed]:
    settings = get_settings()
    if settings.selection_mode == "jd_smart" and (args.brand or args.role):
        raise ValueError("--brand/--role frozen-catalog filters are only supported in legacy mode")
    catalog = FrozenKeywordCatalog(
        brands_path=Path("config/brands.yaml"),
        roles_path=Path("config/roles.yaml"),
    )
    if args.brand and args.role:
        seed = SearchTaskSeed(brand_keyword=args.brand, role_keyword=args.role)
        catalog.validate_seed(seed)
        return [seed]

    seeds = (
        generate_jd_smart_search_task_seeds(settings.workflow_job_profile_path)
        if settings.selection_mode == "jd_smart"
        else generate_search_task_seeds(
            brands_path=Path("config/brands.yaml"),
            roles_path=Path("config/roles.yaml"),
        )
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


def _should_apply_daily_keyword_budget(args: argparse.Namespace) -> bool:
    return not args.brand and not args.role and args.limit is None


def _resolve_daily_keyword_budget(args: argparse.Namespace) -> int | None:
    if args.daily_budget is not None:
        if args.daily_budget <= 0:
            raise ValueError("--daily-budget must be greater than zero")
        return args.daily_budget
    settings = get_settings()
    min_count = settings.workflow_daily_keyword_min
    max_count = settings.workflow_daily_keyword_max
    if min_count <= 0 or max_count <= 0:
        return None
    lower = min(min_count, max_count)
    upper = max(min_count, max_count)
    return random.randint(lower, upper)


def build_catalog_key(seeds: list[SearchTaskSeed]) -> str:
    payload = "\n".join(sorted(build_seed_key(seed) for seed in seeds))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _resolve_batch_job_id(settings) -> str | None:
    if settings.selection_mode != "jd_smart":
        return None
    job_id = _resolve_job_profile_id(settings)
    return job_id if job_id in DUAL_JOB_ORDER else None


def _resolve_job_profile_id(settings) -> str | None:
    if settings.selection_mode != "jd_smart":
        return None
    profile = load_job_profile(settings.workflow_job_profile_path)
    job_id = str(profile.get("id") or "").strip()
    return job_id or None


def resolve_effective_batch_job_id(settings, *, standalone_job: bool) -> str | None:
    """Return the dual-job batch id unless an explicit standalone run was requested."""
    if standalone_job:
        return None
    return _resolve_batch_job_id(settings)


def _is_current_job_checkpoint_complete(args: argparse.Namespace) -> bool:
    all_seeds = build_seeds(args)
    catalog_key = build_catalog_key(all_seeds)
    completed_keys = WorkflowCheckpointStore(Path(args.checkpoint_file)).load_completed_keys(
        catalog_key=catalog_key
    )
    return bool(all_seeds) and all(
        build_seed_key(seed) in completed_keys for seed in all_seeds
    )


def resolve_finished_run_status(
    *,
    exit_code: int,
    is_batch_job: bool,
    checkpoint_complete: bool,
) -> str:
    """进程正常返回不等于批次完成，checkpoint 才是完成事实来源。"""

    if exit_code != 0:
        return "failed"
    if not checkpoint_complete:
        return "interrupted"
    return "completed"


def resolve_standalone_batch_context(
    *,
    state_path: Path,
    checkpoint_file: str,
    job_profile_id: str | None,
    run_id: str,
) -> tuple[str, str, datetime]:
    """复用同一 checkpoint 上一次独立运行的批次身份。"""

    try:
        previous = json.loads(state_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        previous = {}
    previous_context = previous.get("context") if isinstance(previous, dict) else None
    if isinstance(previous_context, dict):
        previous_checkpoint = str(previous_context.get("checkpoint_file") or "")
        previous_profile = str(previous_context.get("job_profile_id") or "")
        previous_batch_id = str(previous_context.get("search_batch_id") or "")
        previous_batch_job_id = str(previous_context.get("search_batch_job_id") or "")
        if (
            Path(previous_checkpoint) == Path(checkpoint_file)
            and previous_profile == (job_profile_id or "")
            and previous_batch_id.startswith("standalone-")
            and previous_batch_job_id
        ):
            started_at = datetime.fromtimestamp(int(previous.get("started_at") or 0))
            return previous_batch_id, previous_batch_job_id, started_at

    batch_id = f"standalone-{job_profile_id or 'job'}-{run_id[:12]}"
    batch_job_id = stable_batch_job_id(batch_id, job_profile_id or "standalone")
    return batch_id, batch_job_id, datetime.now()


def _run_main(
    args: argparse.Namespace,
    settings,
    *,
    search_batch_id: str | None = None,
    search_batch_job_id: str | None = None,
    batch_started_at: datetime | None = None,
) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    checkpoint_store = WorkflowCheckpointStore(Path(args.checkpoint_file))
    write_to_db, send_messages = resolve_run_permissions(args)
    if write_to_db and settings.selection_mode == "jd_smart" and (
        not search_batch_id or not search_batch_job_id or batch_started_at is None
    ):
        raise ValueError(
            "JD smart database writes require parent batch, batch job, and batch start facts"
        )
    if args.reset_checkpoint:
        checkpoint_store.reset()
    configure_logging(
        settings.log_level,
        settings.logs_dir,
        retention_days=settings.log_retention_days,
        log_pii=settings.log_pii,
    )
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
                "send_messages": send_messages,
                "write_to_db": write_to_db,
                "selection_mode": settings.selection_mode,
                "job_profile_path": (
                    str(settings.workflow_job_profile_path)
                    if settings.selection_mode == "jd_smart"
                    else None
                ),
            },
        },
    )

    seeds = build_seeds(args)
    selected_total = len(seeds)
    catalog_key = build_catalog_key(seeds)
    skipped_by_resume = 0
    resumed_from_keyword: dict[str, object] | None = None
    if not args.no_resume:
        completed_keys = checkpoint_store.load_completed_keys(catalog_key=catalog_key)
        if completed_keys:
            original_count = len(seeds)
            seeds = [seed for seed in seeds if build_seed_key(seed) not in completed_keys]
            skipped_by_resume = original_count - len(seeds)
        in_progress_keyword = checkpoint_store.load_in_progress_keyword(catalog_key=catalog_key)
        if in_progress_keyword and seeds:
            in_progress_key = str(in_progress_keyword.get("key") or "")
            matching_index = next(
                (i for i, seed in enumerate(seeds) if build_seed_key(seed) == in_progress_key),
                None,
            )
            if matching_index is not None:
                resumed_from_keyword = in_progress_keyword
                in_progress_seed = seeds[matching_index]
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

    daily_keyword_budget: int | None = None
    if _should_apply_daily_keyword_budget(args):
        daily_keyword_budget = _resolve_daily_keyword_budget(args)
        if daily_keyword_budget is not None and len(seeds) > daily_keyword_budget:
            seeds = seeds[:daily_keyword_budget]
            print(
                f"今日随机关键词预算：{daily_keyword_budget} 个，跑完即停止；剩余关键词明天继续。",
                flush=True,
            )

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
        force_restart=True,
    )

    workflow = SearchWorkflowRunner(
        ResultPageFilter(
            device=device,
            settle_seconds=3.0,
            write_to_db=write_to_db,
            send_messages=send_messages,
            target_candidate_fingerprints=set(args.target_candidate_fingerprint),
            max_profiles_per_keyword=settings.workflow_max_profiles_per_keyword,
            ai_first_selection=settings.workflow_ai_first_selection,
            selection_mode=settings.selection_mode,
            job_profile_path=(
                settings.workflow_job_profile_path
                if settings.selection_mode == "jd_smart"
                else None
            ),
            jd_card_min_score=settings.workflow_jd_card_min_score,
            search_batch_id=search_batch_id,
            search_batch_job_id=search_batch_job_id,
        ),
        checkpoint_store=checkpoint_store,
        checkpoint_catalog_key=catalog_key,
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
                "daily_keyword_budget": daily_keyword_budget,
                "send_messages": send_messages,
                "write_to_db": write_to_db,
                "selection_mode": settings.selection_mode,
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
                "daily_keyword_budget": daily_keyword_budget,
                "send_messages": send_messages,
                "write_to_db": write_to_db,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()
    settings = get_settings()
    configure_logging(
        settings.log_level,
        settings.logs_dir,
        retention_days=settings.log_retention_days,
        log_pii=settings.log_pii,
    )
    run_id = uuid.uuid4().hex
    lease_path = settings.artifacts_dir / "search_workflow.lock"
    state_store = WorkflowRunStateStore(settings.artifacts_dir / "search_workflow_run_state.json")
    if args.standalone_job and args.send_report:
        raise ValueError("--standalone-job cannot be combined with --send-report")
    standalone_writes, _ = resolve_run_permissions(args)
    # 独立岗位（例如 FDE）不进入双岗位批次，但仍需保留批次链路以便
    # 简历明细、AI 结果和沟通记录可按一次运行进行追踪。这里生成独立
    # 的 parent/job id，不修改双岗位批次状态。
    batch_job_id = resolve_effective_batch_job_id(
        settings,
        standalone_job=args.standalone_job,
    )
    job_profile_id = _resolve_job_profile_id(settings)
    if args.send_report and batch_job_id is not None:
        raise ValueError(
            "--send-report cannot run inside a dual-job search batch. "
            "Run scripts/send_db_daily_report.py after the finance job completes."
        )
    batch_store = (
        SearchBatchStateStore(settings.artifacts_dir / BATCH_STATE_FILENAME)
        if batch_job_id is not None
        else None
    )
    batch_id: str | None = None
    search_batch_job_id: str | None = None
    batch_started_at: datetime | None = None
    try:
        lease = ProcessLease.acquire(
            lease_path,
            stale_after_seconds=settings.workflow_lock_stale_seconds,
            context={"run_id": run_id, "kind": "search_workflow"},
        )
    except ProcessLeaseError as exc:
        print(f"已有搜索任务正在运行，当前任务未启动：{exc}", flush=True)
        return 2

    try:
        if batch_store is not None and batch_job_id is not None:
            batch = batch_store.begin_or_resume(
                job_id=batch_job_id,
                checkpoint_file=args.checkpoint_file,
            )
            batch_id = str(batch.get("batch_id") or "")
            batch_started_at = datetime.fromisoformat(str(batch.get("started_at") or ""))
            jobs = batch.get("jobs")
            job_state = jobs.get(batch_job_id) if isinstance(jobs, dict) else None
            if not isinstance(job_state, dict):
                raise ValueError("active batch is missing the current job state")
            search_batch_job_id = str(job_state.get("batch_job_id") or "")
        elif args.standalone_job and standalone_writes:
            batch_id, search_batch_job_id, batch_started_at = resolve_standalone_batch_context(
                state_path=state_store.path,
                checkpoint_file=args.checkpoint_file,
                job_profile_id=job_profile_id,
                run_id=run_id,
            )
        state_store.mark_started(
            run_id=run_id,
            context={
                "checkpoint_file": args.checkpoint_file,
                "search_batch_id": batch_id,
                "search_batch_job_id": search_batch_job_id,
                "job_profile_id": job_profile_id,
            },
        )
        try:
            exit_code = _run_main(
                args,
                settings,
                search_batch_id=batch_id,
                search_batch_job_id=search_batch_job_id,
                batch_started_at=batch_started_at,
            )
        except KeyboardInterrupt:
            state_store.mark_finished(
                run_id=run_id,
                status="interrupted",
                context={
                    "search_batch_id": batch_id,
                    "search_batch_job_id": search_batch_job_id,
                    "job_profile_id": job_profile_id,
                },
            )
            raise
        except Exception as exc:
            state_store.mark_finished(
                run_id=run_id,
                status="failed",
                context={
                    "error": str(exc)[:500],
                    "search_batch_id": batch_id,
                    "search_batch_job_id": search_batch_job_id,
                    "job_profile_id": job_profile_id,
                },
            )
            raise
        else:
            batch_completed = False
            checkpoint_complete = _is_current_job_checkpoint_complete(args)
            if (
                exit_code == 0
                and batch_store is not None
                and batch_job_id is not None
                and batch_id
                and checkpoint_complete
            ):
                batch = batch_store.mark_job_completed(
                    batch_id=batch_id,
                    job_id=batch_job_id,
                    checkpoint_file=args.checkpoint_file,
                )
                batch_completed = batch.get("status") == "completed"
            state_store.mark_finished(
                run_id=run_id,
                status=resolve_finished_run_status(
                    exit_code=exit_code,
                    is_batch_job=batch_store is not None and batch_job_id is not None,
                    checkpoint_complete=checkpoint_complete,
                ),
                context={
                    "exit_code": exit_code,
                    "search_batch_id": batch_id,
                    "search_batch_job_id": search_batch_job_id,
                    "job_profile_id": job_profile_id,
                    "search_batch_completed": batch_completed,
                    "checkpoint_complete": checkpoint_complete,
                },
            )
            return exit_code
    finally:
        lease.release()


if __name__ == "__main__":
    raise SystemExit(main())
