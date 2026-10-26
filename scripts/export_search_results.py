from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

from liepin_rpa.config import get_settings
from liepin_rpa.logging_utils import configure_logging
from liepin_rpa.search_results_export import (
    export_search_results,
    format_summary,
    resolve_custom_scope,
    resolve_latest_run_scope,
    resolve_today_scope,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--checkpoint-file",
        default=None,
        help=(
            "Keyword checkpoint file for including no-candidate keyword rows. "
            "Defaults to the checkpoint from the latest search run when available."
        ),
    )
    parser.add_argument(
        "--latest-run",
        action="store_true",
        help="Export only the latest search workflow run. This is the default when no time option is set.",
    )
    parser.add_argument(
        "--today",
        action="store_true",
        help="Export search results from local today.",
    )
    parser.add_argument(
        "--from-at",
        default=None,
        help="Export hits created at or after this local datetime, e.g. 2026-07-29 13:00:00.",
    )
    parser.add_argument(
        "--to-at",
        default=None,
        help="Export hits created before this local datetime. Defaults to now when --from-at is set.",
    )
    parser.add_argument(
        "--output-file",
        default=None,
        help="CSV output path. Defaults to artifacts/search_results/search_results_<scope>_<timestamp>.csv.",
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
    checkpoint_file = Path(args.checkpoint_file) if args.checkpoint_file else None
    scope = _resolve_scope(args, settings, checkpoint_file=checkpoint_file)
    result = export_search_results(
        settings,
        scope=scope,
        output_file=Path(args.output_file) if args.output_file else None,
    )
    print(json.dumps(format_summary(result), ensure_ascii=False, indent=2), flush=True)
    return 0


def _resolve_scope(args: argparse.Namespace, settings, *, checkpoint_file: Path | None):
    selected_modes = sum(
        1
        for enabled in (
            args.latest_run,
            args.today,
            bool(args.from_at or args.to_at),
        )
        if enabled
    )
    if selected_modes > 1:
        raise ValueError("Use only one of --latest-run, --today, or --from-at/--to-at.")
    if args.today:
        return resolve_today_scope(settings, checkpoint_file=checkpoint_file)
    if args.from_at or args.to_at:
        if not args.from_at:
            raise ValueError("--from-at is required when --to-at is used.")
        window_start_at = _parse_local_datetime(args.from_at)
        window_end_at = _parse_local_datetime(args.to_at) if args.to_at else datetime.now()
        if window_end_at <= window_start_at:
            raise ValueError("--to-at must be later than --from-at.")
        return resolve_custom_scope(
            checkpoint_file=checkpoint_file,
            window_start_at=window_start_at,
            window_end_at=window_end_at,
        )
    return resolve_latest_run_scope(settings, checkpoint_file=checkpoint_file)


def _parse_local_datetime(value: str) -> datetime:
    return datetime.fromisoformat(value.strip().replace("T", " "))


if __name__ == "__main__":
    raise SystemExit(main())
