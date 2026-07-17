from __future__ import annotations

import argparse
from datetime import date
import json
import sys

from liepin_rpa.config import get_settings
from liepin_rpa.db_daily_report import previous_local_date, send_database_daily_report
from liepin_rpa.logging_utils import configure_logging, get_logger

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
        help="Only report candidates successfully stored in the database yesterday. By default, reports since the last successful report.",
    )
    parser.add_argument(
        "--report-date",
        default=None,
        help="Only report candidates successfully stored on this local date, in YYYY-MM-DD format.",
    )
    return parser.parse_args()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()

    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)
    report_date = _resolve_report_date(args)
    result = send_database_daily_report(
        settings,
        limit=args.limit,
        use_cache=args.use_cache,
        dry_run=args.dry_run,
        report_date=report_date,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    return 0


def _resolve_report_date(args: argparse.Namespace) -> date | None:
    if args.report_date and args.yesterday:
        raise ValueError("--report-date and --yesterday cannot be used together.")
    if args.yesterday:
        return previous_local_date()
    if args.report_date:
        return date.fromisoformat(args.report_date)
    return None


if __name__ == "__main__":
    raise SystemExit(main())
