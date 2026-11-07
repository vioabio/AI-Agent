from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from liepin_rpa.candidate_db import build_candidate_db_preview, load_resume_report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--report-path",
        default="outputs/search_workflow/keyword_001/matched_candidate_01/report.json",
        help="Path to the collected candidate report.json file.",
    )
    parser.add_argument(
        "--output-path",
        default="outputs/db_preview/candidate_db_payload_preview.json",
        help="Where to write the preview payload JSON.",
    )
    return parser.parse_args()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()

    report_payload = load_resume_report(Path(args.report_path))
    preview = build_candidate_db_preview(report_payload)
    payload = asdict(preview)

    output_path = Path(args.output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
