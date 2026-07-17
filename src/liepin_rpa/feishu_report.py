from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable
from urllib import error, request

from liepin_rpa.config import Settings
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.profile_collection.resume_schema import sanitize_candidate_name
from liepin_rpa.search_execution.result_filter import CandidateProcessOutcome
from liepin_rpa.search_execution.workflow import SearchWorkflowReport

LOGGER = get_logger(__name__)
MIN_DISPLAY_SCORE = 75
FEISHU_DISPLAY_LIMIT = 3
MIN_REPORT_AGE = 32
MAX_REPORT_AGE = 44


@dataclass(slots=True)
class ReportCandidate:
    candidate_fingerprint: str
    candidate_name: str
    search_keywords: set[str] = field(default_factory=set)
    brand_keywords: set[str] = field(default_factory=set)
    role_keywords: set[str] = field(default_factory=set)
    analysis_status: str = "未分析"
    match_result: str | None = None
    match_score: int | None = None
    score_breakdown: dict[str, int] = field(default_factory=dict)
    analysis_reason: str | None = None
    evidence_summary: list[str] = field(default_factory=list)
    communication_label: str | None = None
    hit_status: str = "reviewed"
    continue_without_db_record: bool = False
    message_send_success: bool = False
    message_send_reason: str = "not_attempted"
    message_send_attempts: int = 0
    message_screenshot_path: str | None = None
    recent_experience_summary: str | None = None
    matched_experience_summary: str | None = None
    candidate_age: int | None = None


@dataclass(slots=True)
class FeishuReportSummary:
    total_keywords: int
    profiles_collected: int
    unique_candidates: int
    greeted_count: int
    contacted_relevant_count: int
    skipped_recent_count: int
    relevant_count: int
    not_relevant_count: int


def send_feishu_daily_report(
    settings: Settings,
    report: SearchWorkflowReport,
    *,
    detail_pdf_name: str | None = None,
    detail_pdf_url: str | None = None,
    window_start_at: datetime | None = None,
    window_end_at: datetime | None = None,
) -> bool:
    if not settings.feishu_bot_webhook:
        LOGGER.info(
            "Skipping Feishu report because webhook is not configured",
            extra={"event": "feishu_report_skip", "context": {}},
        )
        return False

    aggregated_candidates = _aggregate_candidates(report.candidate_outcomes)
    payload = _build_feishu_payload(
        report,
        aggregated_candidates,
        detail_pdf_name=detail_pdf_name,
        detail_pdf_url=detail_pdf_url,
        window_start_at=window_start_at,
        window_end_at=window_end_at,
    )
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = request.Request(
        settings.feishu_bot_webhook,
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
    except error.URLError:
        LOGGER.exception(
            "Failed to send Feishu daily report",
            extra={"event": "feishu_report_failed", "context": {}},
        )
        return False

    try:
        response_payload = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        response_payload = {"raw": raw}
    ok = response_payload.get("StatusCode") == 0
    if ok:
        LOGGER.info(
            "Feishu daily report sent successfully",
            extra={
                "event": "feishu_report_sent",
                "context": {
                    "candidate_count": len(aggregated_candidates),
                    "total_keywords": report.total_keywords,
                },
            },
        )
    else:
        LOGGER.warning(
            "Feishu daily report returned non-success response",
            extra={
                "event": "feishu_report_non_success",
                "context": {"response": response_payload},
            },
        )
    return ok


def _build_feishu_payload(
    report: SearchWorkflowReport,
    aggregated_candidates: list[ReportCandidate],
    *,
    detail_pdf_name: str | None = None,
    detail_pdf_url: str | None = None,
    window_start_at: datetime | None = None,
    window_end_at: datetime | None = None,
) -> dict[str, Any]:
    summary = _build_summary(report, aggregated_candidates)
    ranked_candidates = select_featured_candidates(aggregated_candidates)
    displayed_candidates = ranked_candidates[:FEISHU_DISPLAY_LIMIT]
    today = datetime.now().strftime("%Y-%m-%d")
    elements: list[dict[str, Any]] = [
        _markdown_block(_build_overview_markdown(summary, window_start_at, window_end_at)),
        *([_markdown_block(f"完整简历PDF：[点击下载]({detail_pdf_url})")] if detail_pdf_url else []),
        _markdown_block(
            f"日报仅展示当前统计窗口内分数最高的 {FEISHU_DISPLAY_LIMIT} 位；PDF包含当前统计窗口内全部"
            f"“{MIN_DISPLAY_SCORE}分以上、已打招呼且匹配”的候选人。"
        ),
        {"tag": "hr"},
        _section_header(
            f"75分以上已打招呼且匹配候选人：展示前{len(displayed_candidates)}位 / 共{len(ranked_candidates)}位"
        ),
        _markdown_block(_build_candidate_section(displayed_candidates)),
    ]
    return {
        "msg_type": "interactive",
        "card": {
            "config": {"wide_screen_mode": True},
            "header": {
                "template": "blue",
                "title": {"tag": "plain_text", "content": f"猎聘美妆招聘自动日报｜{today}"},
            },
            "elements": elements,
        },
    }


def _aggregate_candidates(outcomes: list[CandidateProcessOutcome]) -> list[ReportCandidate]:
    aggregated: dict[str, ReportCandidate] = {}
    for outcome in outcomes:
        name = _normalize_candidate_name(outcome.candidate_name)
        candidate = aggregated.get(outcome.candidate_fingerprint)
        if candidate is None:
            candidate = ReportCandidate(
                candidate_fingerprint=outcome.candidate_fingerprint,
                candidate_name=name,
            )
            aggregated[outcome.candidate_fingerprint] = candidate
        candidate.search_keywords.add(outcome.search_keyword)
        candidate.brand_keywords.add(outcome.brand_keyword)
        candidate.role_keywords.add(outcome.role_keyword)
        if _should_apply_outcome(outcome, candidate):
            candidate.analysis_status = outcome.analysis_status
            candidate.match_result = outcome.match_result
            candidate.match_score = outcome.match_score
            candidate.score_breakdown = dict(outcome.score_breakdown)
            candidate.analysis_reason = outcome.analysis_reason
            candidate.evidence_summary = list(outcome.evidence_summary)
            candidate.communication_label = outcome.communication_label
            candidate.hit_status = outcome.hit_status
            candidate.continue_without_db_record = outcome.continue_without_db_record
            candidate.message_send_success = outcome.message_send_success
            candidate.message_send_reason = outcome.message_send_reason
            candidate.message_send_attempts = outcome.message_send_attempts
            candidate.message_screenshot_path = outcome.message_screenshot_path
            candidate.recent_experience_summary = outcome.recent_experience_summary
            candidate.matched_experience_summary = outcome.matched_experience_summary
            candidate.candidate_age = outcome.candidate_age
            candidate.candidate_name = name
    return sorted(
        aggregated.values(),
        key=lambda item: (_candidate_sort_priority(item), -(item.match_score or -1), item.candidate_name),
    )


def build_report_candidates(outcomes: list[CandidateProcessOutcome]) -> list[ReportCandidate]:
    return _aggregate_candidates(outcomes)


def select_featured_candidates(candidates: list[ReportCandidate]) -> list[ReportCandidate]:
    return _select_candidates(candidates, _is_featured_candidate)


def _normalize_candidate_name(value: str | None) -> str:
    normalized = sanitize_candidate_name(value)
    if normalized is None:
        return "未命名候选人"
    cleaned = normalized.replace("*", "").strip()
    return cleaned or "未命名候选人"


def _should_apply_outcome(outcome: CandidateProcessOutcome, candidate: ReportCandidate) -> bool:
    if candidate.match_result is None and candidate.analysis_status == "未分析":
        return True
    return _is_better_outcome(outcome, candidate)


def _is_better_outcome(outcome: CandidateProcessOutcome, candidate: ReportCandidate) -> bool:
    current_priority = _hit_status_priority(candidate.hit_status)
    new_priority = _hit_status_priority(outcome.hit_status)
    if new_priority != current_priority:
        return new_priority > current_priority
    current_score = candidate.match_score or -1
    new_score = outcome.match_score or -1
    if new_score != current_score:
        return new_score > current_score
    return outcome.message_send_success and not candidate.message_send_success


def _hit_status_priority(hit_status: str) -> int:
    if hit_status in {"sent", "immediate_job_confirmed"}:
        return 5
    if hit_status == "skipped_recent_continue":
        return 4
    if hit_status.startswith("send_failed:"):
        return 3
    if hit_status == "continue_without_db_record":
        return 2
    if hit_status == "reviewed":
        return 1
    return 0


def _candidate_sort_priority(candidate: ReportCandidate) -> int:
    return _hit_status_priority(candidate.hit_status)


def _build_summary(
    report: SearchWorkflowReport,
    aggregated_candidates: list[ReportCandidate],
) -> FeishuReportSummary:
    return FeishuReportSummary(
        total_keywords=report.total_keywords,
        profiles_collected=report.profiles_collected,
        unique_candidates=len(aggregated_candidates),
        greeted_count=sum(
            1
            for item in aggregated_candidates
            if item.hit_status in {"sent", "immediate_job_confirmed"}
        ),
        contacted_relevant_count=sum(
            1
            for item in aggregated_candidates
            if item.hit_status in {"sent", "immediate_job_confirmed"}
            and (item.analysis_status == "匹配" or item.match_result == "relevant")
        ),
        skipped_recent_count=sum(
            1 for item in aggregated_candidates if item.hit_status == "skipped_recent_continue"
        ),
        relevant_count=sum(
            1
            for item in aggregated_candidates
            if item.analysis_status == "匹配" or item.match_result == "relevant"
        ),
        not_relevant_count=sum(
            1
            for item in aggregated_candidates
            if item.match_result in {"needs_review", "not_relevant"}
            or item.analysis_status in {"待复核", "不匹配"}
        ),
    )


def _build_overview_markdown(
    summary: FeishuReportSummary,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> str:
    window_text = _format_report_window(window_start_at, window_end_at)
    return "\n".join(
        [
            "**今日概览**",
            f"- 统计窗口：{window_text}",
            f"- 执行关键词：{summary.total_keywords}",
            f"- 进入详情页：{summary.profiles_collected}",
            f"- 去重候选人：{summary.unique_candidates}",
            f"- 打招呼成功：{summary.greeted_count}",
            f"- 已打招呼且匹配：{summary.contacted_relevant_count}",
            f"- 7天内跳过：{summary.skipped_recent_count}",
            f"- 匹配：{summary.relevant_count}｜不匹配（含待复核）：{summary.not_relevant_count}",
        ]
    )


def _build_candidate_section(candidates: list[ReportCandidate]) -> str:
    if not candidates:
        return "今日无符合“75分以上、已打招呼且匹配”条件的候选人。"
    blocks = [_format_candidate_block(candidate) for candidate in candidates]
    return "\n\n".join(blocks)


def _format_candidate_block(candidate: ReportCandidate) -> str:
    score_text = str(candidate.match_score) if candidate.match_score is not None else "未评分"
    keywords_text = " / ".join(sorted(filter(None, candidate.search_keywords))) or "无"
    role_text = " / ".join(sorted(filter(None, candidate.role_keywords))) or "无"
    recent_work_text = candidate.recent_experience_summary or "无"
    matched_work_text = candidate.matched_experience_summary or "无"
    age_text = f"{candidate.candidate_age}岁" if candidate.candidate_age is not None else "无"
    display_name = _escape_markdown(candidate.candidate_name)
    return "\n".join(
        [
            f"姓名：{display_name}｜年龄：{age_text}｜分数：{score_text}｜状态：{_format_status(candidate)}",
            f"关键词：{keywords_text}",
            f"岗位：{role_text}",
            f"最近一段经历：{recent_work_text}",
            f"匹配经历：{matched_work_text}",
        ]
    )


def _format_report_window(
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> str:
    if window_start_at is None and window_end_at is None:
        return "未限定"
    if window_start_at is None:
        return f"首次发送至 {window_end_at:%Y-%m-%d %H:%M}" if window_end_at else "首次发送至当前"
    if window_end_at is None:
        return f"{window_start_at:%Y-%m-%d %H:%M} 至当前"
    return f"{window_start_at:%Y-%m-%d %H:%M} 至 {window_end_at:%Y-%m-%d %H:%M}"


def _format_status(candidate: ReportCandidate) -> str:
    if candidate.hit_status == "sent":
        return "已打招呼"
    if candidate.hit_status == "immediate_job_confirmed":
        return "已确认打招呼岗位"
    if candidate.hit_status == "skipped_recent_continue":
        return "7天内已打过招呼"
    if candidate.hit_status.startswith("send_failed:"):
        return "打招呼失败"
    if candidate.analysis_status == "匹配":
        return "匹配未打招呼"
    if candidate.match_result == "needs_review" or candidate.analysis_status == "待复核":
        return "不匹配（待复核）"
    if candidate.analysis_status == "不匹配" or candidate.match_result == "not_relevant":
        return "不匹配"
    return candidate.hit_status


def _select_candidates(
    candidates: list[ReportCandidate],
    predicate: Callable[[ReportCandidate], bool],
) -> list[ReportCandidate]:
    filtered = [item for item in candidates if predicate(item)]
    return sorted(
        filtered,
        key=lambda item: (
            -(item.match_score if item.match_score is not None else -1),
            item.candidate_name,
        ),
    )


def _is_featured_candidate(item: ReportCandidate) -> bool:
    return (
        item.hit_status in {"sent", "immediate_job_confirmed"}
        and (item.analysis_status == "匹配" or item.match_result == "relevant")
        and item.match_score is not None
        and item.match_score >= MIN_DISPLAY_SCORE
        and _is_report_age_allowed(item.candidate_age)
    )


def _is_report_age_allowed(age: int | None) -> bool:
    if age is None:
        return True
    return MIN_REPORT_AGE <= age <= MAX_REPORT_AGE


def _normalize_text(text: str) -> str:
    return " ".join(text.split())


def _escape_markdown(text: str) -> str:
    escaped = text
    for ch in ("\\", "*", "_", "`", "[", "]", "(", ")"):
        escaped = escaped.replace(ch, f"\\{ch}")
    return escaped


def _section_header(title: str) -> dict[str, Any]:
    return {"tag": "div", "text": {"tag": "plain_text", "content": title}}


def _markdown_block(content: str) -> dict[str, Any]:
    return {"tag": "div", "text": {"tag": "lark_md", "content": content}}
