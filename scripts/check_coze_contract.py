from __future__ import annotations

import argparse
import json
import sys
import time

from liepin_rpa.config import get_settings
from liepin_rpa.coze_workflow import _validate_coze_payload, analyze_resume_record_with_coze


EXPECTED_TOP_LEVEL_KEYS = {
    "beauty_relevant",
    "match_result",
    "match_score",
    "score_breakdown",
    "analysis_reason",
    "evidence_summary",
}
SCORE_LIMITS = {
    "industry_depth": 25,
    "beauty_continuity": 15,
    "role_evidence": 60,
}


CASES = (
    (
        "high_match",
        "needs_review",
        {
            "id": 91001,
            "candidate_name": "测试候选人A",
            "source_platform": "contract_test",
            "search_keyword": "达人营销总监 海蓝之谜",
            "analysis_status": "未执行",
            "experience": (
                "2021.03-2025.06，甲美妆公司，达人营销总监。负责抖音和小红书达人营销团队，"
                "管理12人团队；建立头部、腰部、垂类达人分层资源库，年度合作达人320名；"
                "负责达人专场、短直联动、直播间投流及复盘。2024年本人负责项目成交GMV "
                "1.2亿元，简历原文注明同比增长35%，投放ROI由2.8提升至4.1。"
                "2018.01-2021.02，乙日化公司，媒介经理，负责KOL投放与代理商管理。"
            ),
        },
    ),
    (
        "not_relevant",
        "needs_review",
        {
            "id": 91002,
            "candidate_name": "测试候选人B",
            "source_platform": "contract_test",
            "search_keyword": "达人营销总监 SK-II",
            "analysis_status": "未执行",
            "experience": (
                "2022.05-2025.07，某护肤品牌，新媒体经理。负责公众号、微博和小红书内容发布，"
                "也曾对接达人。简历写有数字3000万，但未注明指标名称、归属范围或候选人承担的责任；"
                "未提供团队规模、达人分层、直播电商或ROI职责。"
            ),
        },
    ),
    (
        "not_relevant",
        "not_relevant",
        {
            "id": 91003,
            "candidate_name": "测试候选人C",
            "source_platform": "contract_test",
            "search_keyword": "达人营销总监 海蓝之谜",
            "analysis_status": "未执行",
            "experience": (
                "2020.01-2025.07，某制造企业，财务主管。负责总账、税务申报、费用审核和年度预算，"
                "管理2名会计。experience中未提供美妆、快消、医疗、达人营销、媒介、直播或电商职责。"
            ),
        },
    ),
)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    settings = get_settings()
    args = _parse_args()
    failures: list[str] = []

    for case_name, expected_result, resume_record in CASES:
        if args.case and case_name != args.case:
            continue
        started_at = time.monotonic()
        result = analyze_resume_record_with_coze(settings, resume_record=resume_record)
        payload = result.parsed_payload
        errors = _contract_errors(payload)
        if payload.get("match_result") != expected_result:
            errors.append(
                f"expected match_result={expected_result}, got {payload.get('match_result')}"
            )
        if errors:
            failures.extend(f"{case_name}: {error}" for error in errors)
        print(
            json.dumps(
                {
                    "case": case_name,
                    "elapsed_seconds": round(time.monotonic() - started_at, 1),
                    "expected_result": expected_result,
                    "payload": payload,
                    "errors": errors,
                },
                ensure_ascii=False,
                indent=2,
            ),
            flush=True,
        )

    if failures:
        print(json.dumps({"status": "failed", "failures": failures}, ensure_ascii=False, indent=2))
        return 1
    case_count = 1 if args.case else len(CASES)
    print(json.dumps({"status": "passed", "cases": case_count}, ensure_ascii=False, indent=2))
    return 0


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--case",
        choices=[case_name for case_name, _, _ in CASES],
        default=None,
        help="Run only one contract case.",
    )
    return parser.parse_args()


def _contract_errors(payload: dict[str, object]) -> list[str]:
    errors = _validate_coze_payload(payload)
    if set(payload) != EXPECTED_TOP_LEVEL_KEYS:
        errors.append("top-level fields do not exactly match the published contract")

    breakdown = payload.get("score_breakdown")
    if isinstance(breakdown, dict):
        for field_name, limit in SCORE_LIMITS.items():
            value = breakdown.get(field_name)
            if (
                not isinstance(value, int)
                or isinstance(value, bool)
                or not 0 <= value <= limit
            ):
                errors.append(f"{field_name} must be an integer between 0 and {limit}")

    match_result = payload.get("match_result")
    match_score = payload.get("match_score")
    role_evidence = breakdown.get("role_evidence") if isinstance(breakdown, dict) else None
    if payload.get("beauty_relevant") != (match_result == "relevant"):
        errors.append("beauty_relevant and match_result are inconsistent")
    if match_result == "relevant" and not (
        isinstance(match_score, int)
        and match_score >= 80
        and isinstance(role_evidence, int)
        and role_evidence >= 48
    ):
        errors.append("relevant result violates the score thresholds")
    return errors


if __name__ == "__main__":
    raise SystemExit(main())
