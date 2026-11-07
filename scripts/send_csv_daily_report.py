from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, datetime, timedelta
import json
from pathlib import Path
import re
import sys
from urllib import error, request

from liepin_rpa.config import get_settings
from liepin_rpa.logging_utils import configure_logging

DEFAULT_CSV_PATH = Path(r"D:\jianli\liepin_candidates.csv")
DEFAULT_WATERMARK_PATH = Path("artifacts/csv_daily_report_watermark.json")
EXPECTED_FIELDS = [
    "\u91c7\u96c6\u65f6\u95f4",
    "\u59d3\u540d",
    "\u5e74\u9f84",
    "\u5de5\u4f5c\u7ecf\u5386\u6458\u8981",
    "\u662f\u5426\u5339\u914d",
    "\u603b\u5206",
    "\u884c\u4e1a\u6df1\u5ea6\u5206",
    "\u884c\u4e1a\u8fde\u7eed\u6027\u5206",
    "\u5c97\u4f4d\u8bc1\u636e\u5206",
]
DISPLAY_LIMIT = 3
MATCHED_STATUS = "\u5339\u914d"
NOT_MATCHED_STATUS = "\u4e0d\u5339\u914d"


@dataclass(slots=True)
class CsvCandidate:
    collected_at: datetime
    name: str
    age: str
    experience: str
    match_status: str
    score: int
    industry_depth_score: int
    continuity_score: int
    role_evidence_score: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv-path", default=str(DEFAULT_CSV_PATH))
    parser.add_argument("--watermark-file", default=str(DEFAULT_WATERMARK_PATH))
    parser.add_argument(
        "--report-date",
        default=None,
        help="Report rows collected on this date, in YYYY-MM-DD format.",
    )
    parser.add_argument(
        "--test-webhook",
        action="store_true",
        help="Send to FEISHU_TEST_BOT_WEBHOOK and do not advance the official CSV report watermark.",
    )
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()
    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)

    csv_path = Path(args.csv_path)
    watermark_path = Path(args.watermark_file)
    all_rows = load_csv_candidates(csv_path)
    window_start, window_end, window_mode = build_report_window(watermark_path, args.report_date)
    window_rows = [
        item
        for item in all_rows
        if window_start <= item.collected_at < window_end
    ]
    payload = build_feishu_payload(
        window_start=window_start,
        window_end=window_end,
        all_candidates=all_rows,
        window_candidates=window_rows,
    )

    webhook = settings.feishu_test_bot_webhook if args.test_webhook else settings.feishu_bot_webhook
    sent = False if args.dry_run else send_feishu_payload(webhook, payload)
    advance_watermark = sent and not args.test_webhook and args.report_date is None
    if advance_watermark:
        write_watermark(watermark_path, window_start=window_start, window_end=window_end)

    result = {
        "csv_path": str(csv_path),
        "window_mode": window_mode,
        "window_start_at": window_start.isoformat(sep=" "),
        "window_end_at": window_end.isoformat(sep=" "),
        "total_rows": len(all_rows),
        "window_rows": len(window_rows),
        "window_matched": sum(1 for item in window_rows if item.match_status == MATCHED_STATUS),
        "window_not_matched": sum(1 for item in window_rows if item.match_status != MATCHED_STATUS),
        "feishu_sent": sent,
        "advance_watermark": advance_watermark,
        "webhook_mode": "test" if args.test_webhook else "default",
        "dry_run": args.dry_run,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    return 0


def build_report_window(
    watermark_path: Path,
    report_date_value: str | None,
) -> tuple[datetime, datetime, str]:
    if report_date_value:
        report_date = date.fromisoformat(report_date_value)
        start_at = datetime.combine(report_date, datetime.min.time())
        return start_at, start_at + timedelta(days=1), "fixed_date"
    last_sent_at = read_last_sent_at(watermark_path)
    end_at = datetime.now()
    if last_sent_at is None:
        return datetime.min, end_at, "since_last_success_or_all"
    return last_sent_at, end_at, "since_last_success"


def read_last_sent_at(watermark_path: Path) -> datetime | None:
    if not watermark_path.exists():
        return None
    try:
        payload = json.loads(watermark_path.read_text(encoding="utf-8"))
    except Exception:
        return None
    raw_value = payload.get("sent_at")
    if not isinstance(raw_value, str):
        return None
    try:
        return datetime.fromisoformat(raw_value)
    except ValueError:
        return None


def write_watermark(
    watermark_path: Path,
    *,
    window_start: datetime,
    window_end: datetime,
) -> None:
    watermark_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "sent_at": datetime.now().isoformat(timespec="seconds"),
        "window_start_at": window_start.isoformat(timespec="seconds"),
        "window_end_at": window_end.isoformat(timespec="seconds"),
    }
    watermark_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def load_csv_candidates(csv_path: Path) -> list[CsvCandidate]:
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV file not found: {csv_path}")
    raw_text = csv_path.read_text(encoding="utf-8-sig", errors="replace")
    repaired_text = repair_missing_row_breaks(raw_text)
    rows = list(csv.DictReader(repaired_text.splitlines()))
    if not rows:
        return []
    missing_fields = [field for field in EXPECTED_FIELDS if field not in rows[0]]
    if missing_fields:
        raise ValueError(f"CSV missing required fields: {missing_fields}")

    candidates: list[CsvCandidate] = []
    for row in rows:
        if None in row:
            continue
        collected_at = parse_collected_at(row.get("\u91c7\u96c6\u65f6\u95f4"))
        if collected_at is None:
            continue
        candidates.append(
            CsvCandidate(
                collected_at=collected_at,
                name=clean_text(row.get("\u59d3\u540d")),
                age=clean_text(row.get("\u5e74\u9f84")),
                experience=clean_text(row.get("\u5de5\u4f5c\u7ecf\u5386\u6458\u8981")),
                match_status=clean_text(row.get("\u662f\u5426\u5339\u914d")) or NOT_MATCHED_STATUS,
                score=parse_int(row.get("\u603b\u5206")),
                industry_depth_score=parse_int(row.get("\u884c\u4e1a\u6df1\u5ea6\u5206")),
                continuity_score=parse_int(row.get("\u884c\u4e1a\u8fde\u7eed\u6027\u5206")),
                role_evidence_score=parse_int(row.get("\u5c97\u4f4d\u8bc1\u636e\u5206")),
            )
        )
    return candidates


def repair_missing_row_breaks(text: str) -> str:
    return re.sub(
        r'(?<=[0-9])(?="\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}",)',
        "\n",
        text,
    )


def parse_collected_at(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.strptime(value.strip(), "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return None


def clean_text(value: str | None) -> str:
    if not value:
        return ""
    return " ".join(value.replace("\\n", " ").split()).strip()


def parse_int(value: str | None) -> int:
    if value is None:
        return 0
    stripped = str(value).strip().strip('"')
    if not stripped:
        return 0
    try:
        return int(float(stripped))
    except ValueError:
        return 0


def build_feishu_payload(
    *,
    window_start: datetime,
    window_end: datetime,
    all_candidates: list[CsvCandidate],
    window_candidates: list[CsvCandidate],
) -> dict[str, object]:
    ranked = sorted(
        (item for item in window_candidates if item.match_status == MATCHED_STATUS),
        key=lambda item: (-item.score, item.name),
    )
    displayed = ranked[:DISPLAY_LIMIT]
    return {
        "msg_type": "interactive",
        "card": {
            "config": {"wide_screen_mode": True},
            "header": {
                "template": "blue",
                "title": {
                    "tag": "plain_text",
                    "content": f"\u88ab\u52a8\u7b5b\u9009\u5019\u9009\u4eba\u65e5\u62a5 | {datetime.now():%Y-%m-%d}",
                },
            },
            "elements": [
                markdown_block(build_total_overview(all_candidates)),
                {"tag": "hr"},
                markdown_block(build_today_overview(window_start, window_end, window_candidates)),
                {"tag": "hr"},
                section_header(f"\u4eca\u65e5\u5339\u914d\u5019\u9009\u4eba\uff1a\u5c55\u793a\u524d{len(displayed)}\u4f4d / \u5171{len(ranked)}\u4f4d"),
                markdown_block(build_candidate_section(displayed)),
            ],
        },
    }


def build_total_overview(candidates: list[CsvCandidate]) -> str:
    matched = sum(1 for item in candidates if item.match_status == MATCHED_STATUS)
    return "\n".join(
        [
            "**\u5168\u90e8\u6982\u89c8**",
            f"- CSV\u7d2f\u8ba1\u5019\u9009\u4eba\uff1a{len(candidates)}",
            f"- \u7d2f\u8ba1\u5339\u914d\uff1a{matched}",
            f"- \u7d2f\u8ba1\u4e0d\u5339\u914d\uff1a{len(candidates) - matched}",
        ]
    )


def build_today_overview(
    window_start: datetime,
    window_end: datetime,
    candidates: list[CsvCandidate],
) -> str:
    matched = sum(1 for item in candidates if item.match_status == MATCHED_STATUS)
    high_score = sum(
        1
        for item in candidates
        if item.match_status == MATCHED_STATUS and item.score >= 75
    )
    average_score = round(sum(item.score for item in candidates) / len(candidates), 1) if candidates else 0
    return "\n".join(
        [
            "**\u4eca\u65e5\u6982\u89c8**",
            f"- \u7edf\u8ba1\u7a97\u53e3\uff1a{format_window(window_start, window_end)}",
            f"- \u672c\u7a97\u53e3\u5019\u9009\u4eba\uff1a{len(candidates)}",
            f"- \u672c\u7a97\u53e3\u5339\u914d\uff1a{matched}",
            f"- \u672c\u7a97\u53e3\u4e0d\u5339\u914d\uff1a{len(candidates) - matched}",
            f"- 75\u5206\u4ee5\u4e0a\uff1a{high_score}",
            f"- \u5e73\u5747\u5206\uff1a{average_score}",
        ]
    )


def build_candidate_section(candidates: list[CsvCandidate]) -> str:
    if not candidates:
        return "\u672c\u7a97\u53e3\u6682\u65e0\u5339\u914d\u5019\u9009\u4eba\u3002"
    return "\n\n".join(format_candidate(item) for item in candidates)


def format_candidate(candidate: CsvCandidate) -> str:
    experience = candidate.experience
    if len(experience) > 180:
        experience = experience[:180].rstrip() + "..."
    return "\n".join(
        [
            f"\u59d3\u540d\uff1a{escape_markdown(candidate.name or '\u672a\u547d\u540d\u5019\u9009\u4eba')}\uff5c\u5e74\u9f84\uff1a{candidate.age or '\u65e0'}\uff5c\u5206\u6570\uff1a{candidate.score}\uff5c\u72b6\u6001\uff1a{candidate.match_status}",
            (
                "\u8bc4\u5206\u62c6\u89e3\uff1a"
                f"\u884c\u4e1a\u6df1\u5ea6 {candidate.industry_depth_score}\uff5c"
                f"\u884c\u4e1a\u8fde\u7eed\u6027 {candidate.continuity_score}\uff5c"
                f"\u5c97\u4f4d\u8bc1\u636e {candidate.role_evidence_score}"
            ),
            f"\u7ecf\u5386\u6458\u8981\uff1a{escape_markdown(experience or '\u65e0')}",
        ]
    )


def format_window(window_start: datetime, window_end: datetime) -> str:
    if window_start == datetime.min:
        return f"\u9996\u6b21\u53d1\u9001\u81f3 {window_end:%Y-%m-%d %H:%M}"
    return f"{window_start:%Y-%m-%d %H:%M} \u81f3 {window_end:%Y-%m-%d %H:%M}"


def send_feishu_payload(webhook: str | None, payload: dict[str, object]) -> bool:
    if not webhook:
        raise ValueError("Feishu webhook is not configured.")
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = request.Request(
        webhook,
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
    except error.URLError:
        return False
    try:
        response_payload = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        return False
    return response_payload.get("StatusCode") == 0


def section_header(title: str) -> dict[str, object]:
    return {"tag": "div", "text": {"tag": "plain_text", "content": title}}


def markdown_block(content: str) -> dict[str, object]:
    return {"tag": "div", "text": {"tag": "lark_md", "content": content}}


def escape_markdown(text: str) -> str:
    escaped = text
    for ch in ("\\", "*", "_", "`", "[", "]", "(", ")"):
        escaped = escaped.replace(ch, f"\\{ch}")
    return escaped


if __name__ == "__main__":
    raise SystemExit(main())
