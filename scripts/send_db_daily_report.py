from __future__ import annotations

import argparse
from datetime import date, datetime
import json
import sys

from liepin_rpa.config import get_settings
from liepin_rpa.db_daily_report import previous_local_date, send_database_daily_report
from liepin_rpa.logging_utils import configure_logging, get_logger
from liepin_rpa.report_batch import (
    NoUnreportedSearchBatchError,
    SearchBatchNotReadyError,
)
from liepin_rpa.runtime_control import ProcessLease, ProcessLeaseError, wait_for_process_lease

LOGGER = get_logger(__name__)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Only analyze the latest N contacted candidates from the database.",
    )
    parser.add_argument(
        "--use-cache",
        action="store_true",
        help="Reuse analysis cache when available instead of always calling Coze again.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Build the report but do not send it to Feishu.",
    )
    parser.add_argument(
        "--yesterday",
        action="store_true",
        help="Legacy/debug option: report the previous natural day instead of a search batch.",
    )
    parser.add_argument(
        "--batch-id",
        default=None,
        help="Report one completed dual-job search batch. Defaults to the latest completed batch.",
    )
    parser.add_argument(
        "--report-date",
        default=None,
        help="Only report candidates successfully stored on this local date, in YYYY-MM-DD format.",
    )
    parser.add_argument(
        "--from-at",
        default=None,
        help="Report candidates updated at or after this local datetime, e.g. 2026-07-17 00:00:00.",
    )
    parser.add_argument(
        "--to-at",
        default=None,
        help="Report candidates updated before this local datetime. Defaults to now when --from-at is used.",
    )
    parser.add_argument(
        "--test-webhook",
        action="store_true",
        help="Send to FEISHU_TEST_BOT_WEBHOOK and do not advance the official report watermark.",
    )
    parser.add_argument(
        "--pdf-sent-only",
        action="store_true",
        help="Only include candidates with a verified sent communication in the detail PDF.",
    )
    parser.add_argument(
        "--wait-for-search",
        action="store_true",
        help="Wait for an active search workflow to finish before producing the report.",
    )
    return parser.parse_args()


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
    if args.wait_for_search:
        search_lock_path = settings.artifacts_dir / "search_workflow.lock"
        ready = wait_for_process_lease(
            search_lock_path,
            timeout_seconds=settings.daily_report_wait_for_search_seconds,
            stale_after_seconds=settings.workflow_lock_stale_seconds,
        )
        if not ready:
            raise RuntimeError("Timed out while waiting for the active search workflow to finish.")
    try:
        lease = ProcessLease.acquire(
            settings.artifacts_dir / "daily_report.lock",
            stale_after_seconds=settings.workflow_lock_stale_seconds,
            context={"kind": "daily_report"},
        )
    except ProcessLeaseError as exc:
        print(f"已有日报任务正在运行，当前任务未启动：{exc}", flush=True)
        return 2
    try:
        report_date = _resolve_report_date(args)
        window_start_at, window_end_at = _resolve_custom_window(args)
        feishu_webhook_override = settings.feishu_test_bot_webhook if args.test_webhook else None
        if args.test_webhook and not feishu_webhook_override:
            raise ValueError("FEISHU_TEST_BOT_WEBHOOK is required when --test-webhook is used.")
        try:
            result = send_database_daily_report(
                settings,
                limit=args.limit,
                use_cache=args.use_cache,
                dry_run=args.dry_run,
                report_date=report_date,
                window_start_at=window_start_at,
                window_end_at=window_end_at,
                batch_id=args.batch_id,
                feishu_webhook_override=feishu_webhook_override,
                advance_watermark=not args.test_webhook,
                pdf_sent_only=args.pdf_sent_only,
            )
        except NoUnreportedSearchBatchError as exc:
            print(
                json.dumps(
                    {"status": "skipped", "reason": "no_unreported_batch", "message": str(exc)},
                    ensure_ascii=False,
                    indent=2,
                ),
                flush=True,
            )
            return 0
        except SearchBatchNotReadyError as exc:
            print(
                json.dumps(
                    {"status": "retry", "reason": "search_batch_not_ready", "message": str(exc)},
                    ensure_ascii=False,
                    indent=2,
                ),
                flush=True,
            )
            return 3
        print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
        return 0
    finally:
        lease.release()


def _resolve_report_date(args: argparse.Namespace) -> date | None:
    if args.batch_id and (args.report_date or args.yesterday or args.from_at or args.to_at):
        raise ValueError("--batch-id cannot be combined with date/window options.")
    if args.report_date and args.yesterday:
        raise ValueError("--report-date and --yesterday cannot be used together.")
    if (args.from_at or args.to_at) and (args.report_date or args.yesterday):
        raise ValueError("--from-at/--to-at cannot be used with --report-date or --yesterday.")
    if args.yesterday:
        return previous_local_date()
    if args.report_date:
        return date.fromisoformat(args.report_date)
    return None


def _resolve_custom_window(args: argparse.Namespace) -> tuple[datetime | None, datetime | None]:
    if not args.from_at and not args.to_at:
        return None, None
    if not args.from_at:
        raise ValueError("--from-at is required when --to-at is used.")
    window_start_at = _parse_local_datetime(args.from_at)
    window_end_at = _parse_local_datetime(args.to_at) if args.to_at else None
    if window_end_at is not None and window_end_at <= window_start_at:
        raise ValueError("--to-at must be later than --from-at.")
    return window_start_at, window_end_at


def _parse_local_datetime(value: str) -> datetime:
    normalized = value.strip().replace("T", " ")
    return datetime.fromisoformat(normalized)


if __name__ == "__main__":
    raise SystemExit(main())
