from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from liepin_rpa.config import get_settings
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.logging_utils import configure_logging
from liepin_rpa.profile_collection import CandidateProfileCollector


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output-dir",
        default="outputs/profile_collection/current_profile",
        help="Directory to store the profile collection captures and report.",
    )
    parser.add_argument(
        "--max-stagnant-swipes",
        type=int,
        default=3,
        help="Stop after this many consecutive ineffective swipes.",
    )
    parser.add_argument(
        "--max-iterations",
        type=int,
        default=80,
        help="Safety limit for capture iterations.",
    )
    parser.add_argument("--brand", default=None, help="Search brand keyword for source metadata.")
    parser.add_argument("--role", default=None, help="Search role keyword for source metadata.")
    parser.add_argument("--search-task-id", default=None, help="Search task id for source metadata.")
    return parser.parse_args()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()

    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)

    adb_runner = AdbRunner(settings.adb_executable)
    device = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)
    device.connect()
    device.ensure_unlocked()

    collector = CandidateProfileCollector(
        device=device,
        max_stagnant_swipes=args.max_stagnant_swipes,
        max_iterations=args.max_iterations,
    )
    report = collector.collect_current_profile(Path(args.output_dir))

    if any([args.brand, args.role, args.search_task_id]):
        from liepin_rpa.profile_collection.resume_schema import build_candidate_resume_from_debug_path

        structured_resume = build_candidate_resume_from_debug_path(
            Path(report.debug_report_path),
            search_brand_keyword=args.brand,
            search_role_keyword=args.role,
            search_task_id=args.search_task_id,
        )
        Path(report.report_path).write_text(
            json.dumps(structured_resume, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    print(
        json.dumps(
            {
                "report_path": report.report_path,
                "debug_report_path": report.debug_report_path,
                "raw_text_path": report.raw_text_path,
                "markdown_path": report.markdown_path,
                "total_iterations": report.total_iterations,
                "total_swipes": report.total_swipes,
                "total_expand_clicks": report.total_expand_clicks,
                "reached_bottom": report.reached_bottom,
                "collected_text_lines": len(report.collected_text_lines),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
