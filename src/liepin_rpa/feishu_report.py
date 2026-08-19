from __future__ import annotations

import json
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any
from urllib import error, request

from liepin_rpa.communication_status_policy import (
    communication_status_priority,
    is_sent_hit_status,
)
from liepin_rpa.config import Settings
from liepin_rpa.hr_review_reporting import (
    HR_REVIEW_STATUS_LABELS,
    HRReviewStatus,
    RecruitmentProgressSnapshot,
)
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.profile_collection.resume_schema import sanitize_candidate_name
from liepin_rpa.reporting_rules import (
    ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
    has_needs_review_result,
    is_actionable_needs_review_result,
    is_not_relevant_result,
    is_relevant_result,
)
from liepin_rpa.search_execution.result_filter import CandidateProcessOutcome
from liepin_rpa.search_execution.workflow import SearchWorkflowReport

LOGGER = get_logger(__name__)
FEISHU_DISPLAY_LIMIT = 3
FEISHU_REVIEW_DISPLAY_LIMIT = 5
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
    total_candidates_seen: int | None
    cumulative_screened_candidate_views: int | None
    cumulative_relevant_count: int | None
    cumulative_needs_review_count: int | None
    cumulative_not_relevant_count: int | None
    cumulative_greeted_count: int | None
    screened_candidate_views: int | None
    total_keywords: int
    hit_keywords: int
    profiles_collected: int
    unique_candidates: int
    greeted_count: int
    contacted_relevant_count: int
    skipped_recent_count: int
    relevant_count: int
    needs_review_count: int
    recollection_count: int
    not_relevant_count: int


@dataclass(slots=True)
class RoleReportSummary:
    role_keyword: str
    executed_keywords: int = 0
    profiles_collected: int = 0
    relevant_count: int = 0
    needs_review_count: int = 0
    recollection_count: int = 0
    not_relevant_count: int = 0


@dataclass(slots=True)
class JobPipelineMetrics:
    """Compatibility model for source-backed per-job funnel diagnostics."""

    job_profile_id: str
    job_title: str
    executed_keywords: int = 0
    detail_entries: int = 0
    official_share_links: int = 0
    identity_linked: int = 0
    shanghai_passed: int = 0
    shanghai_rejected: int = 0
    ai_analyzed: int = 0
    relevant_count: int = 0
    needs_review_count: int = 0
    not_relevant_count: int = 0
    greeting_success: int = 0
    greeting_failed: int = 0


@dataclass(slots=True)
class JobReportSummary:
    job_name: str
    search_directions: set[str] = field(default_factory=set)
    executed_keywords: int = 0
    screened_candidate_views: int = 0
    profiles_collected: int = 0
    relevant_count: int = 0
    needs_review_count: int = 0
    greeted_count: int = 0
    cumulative_screened_candidate_views: int = 0
    cumulative_relevant_count: int = 0
    cumulative_needs_review_count: int = 0
    cumulative_greeted_count: int = 0


def send_feishu_daily_report(
    settings: Settings,
    report: SearchWorkflowReport,
    *,
    detail_pdf_name: str | None = None,
    detail_pdf_url: str | None = None,
    detail_pdf_urls_by_job: dict[str, str | None] | None = None,
    window_start_at: datetime | None = None,
    window_end_at: datetime | None = None,
    total_candidates_seen: int | None = None,
    executed_keywords: int | None = None,
    executed_keywords_by_role: dict[str, int] | None = None,
    screened_candidate_views: int | None = None,
    screened_candidate_views_by_role: dict[str, int] | None = None,
    cumulative_screened_candidate_views: int | None = None,
    cumulative_relevant_count: int | None = None,
    cumulative_needs_review_count: int | None = None,
    cumulative_not_relevant_count: int | None = None,
    cumulative_greeted_count: int | None = None,
    cumulative_start_at: datetime | None = None,
    cumulative_end_at: datetime | None = None,
    cumulative_metrics_by_job: dict[str, dict[str, int]] | None = None,
    recruitment_progress: RecruitmentProgressSnapshot | None = None,
    search_batch_id: str | None = None,
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
        detail_pdf_urls_by_job=detail_pdf_urls_by_job,
        window_start_at=window_start_at,
        window_end_at=window_end_at,
        total_candidates_seen=total_candidates_seen,
        executed_keywords=executed_keywords,
        executed_keywords_by_role=executed_keywords_by_role,
        screened_candidate_views=screened_candidate_views,
        screened_candidate_views_by_role=screened_candidate_views_by_role,
        cumulative_screened_candidate_views=cumulative_screened_candidate_views,
        cumulative_relevant_count=cumulative_relevant_count,
        cumulative_needs_review_count=cumulative_needs_review_count,
        cumulative_not_relevant_count=cumulative_not_relevant_count,
        cumulative_greeted_count=cumulative_greeted_count,
        cumulative_start_at=cumulative_start_at,
        cumulative_end_at=cumulative_end_at,
        cumulative_metrics_by_job=cumulative_metrics_by_job,
        recruitment_progress=recruitment_progress,
        search_batch_id=search_batch_id,
    )
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    opener = request.build_opener(request.ProxyHandler({}))
    raw = ""
    for attempt in range(1, 4):
        req = request.Request(
            settings.feishu_bot_webhook,
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with opener.open(req, timeout=20) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
            break
        except error.URLError:
            if attempt >= 3:
                LOGGER.exception(
                    "Failed to send Feishu daily report",
                    extra={
                        "event": "feishu_report_failed",
                        "context": {"attempt": attempt},
                    },
                )
                return False
            LOGGER.warning(
                "Feishu daily report request failed and will be retried",
                extra={
                    "event": "feishu_report_retry",
                    "context": {"attempt": attempt},
                },
            )
            time.sleep(3 * attempt)

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
    detail_pdf_urls_by_job: dict[str, str | None] | None = None,
    window_start_at: datetime | None = None,
    window_end_at: datetime | None = None,
    total_candidates_seen: int | None = None,
    executed_keywords: int | None = None,
    executed_keywords_by_role: dict[str, int] | None = None,
    screened_candidate_views: int | None = None,
    screened_candidate_views_by_role: dict[str, int] | None = None,
    cumulative_screened_candidate_views: int | None = None,
    cumulative_relevant_count: int | None = None,
    cumulative_needs_review_count: int | None = None,
    cumulative_not_relevant_count: int | None = None,
    cumulative_greeted_count: int | None = None,
    cumulative_start_at: datetime | None = None,
    cumulative_end_at: datetime | None = None,
    cumulative_metrics_by_job: dict[str, dict[str, int]] | None = None,
    recruitment_progress: RecruitmentProgressSnapshot | None = None,
    search_batch_id: str | None = None,
) -> dict[str, Any]:
    summary = _build_summary(
        report,
        aggregated_candidates,
        total_candidates_seen=total_candidates_seen,
        executed_keywords=executed_keywords,
        screened_candidate_views=screened_candidate_views,
        cumulative_screened_candidate_views=cumulative_screened_candidate_views,
        cumulative_relevant_count=cumulative_relevant_count,
        cumulative_needs_review_count=cumulative_needs_review_count,
        cumulative_not_relevant_count=cumulative_not_relevant_count,
        cumulative_greeted_count=cumulative_greeted_count,
    )
    role_summaries = _build_role_summaries(
        aggregated_candidates,
        executed_keywords_by_role=executed_keywords_by_role or {},
    )
    job_summaries = _build_job_summaries(
        aggregated_candidates,
        executed_keywords_by_role=executed_keywords_by_role or {},
        screened_candidate_views_by_role=screened_candidate_views_by_role or {},
        cumulative_screened_candidate_views_by_role=(
            (cumulative_metrics_by_job or {}).get("screened_views_by_role", {})
        ),
        cumulative_metrics_by_job=cumulative_metrics_by_job or {},
    )
    report_date = datetime.now().strftime("%Y-%m-%d")
    elements: list[dict[str, Any]] = [
        *_build_management_summary_elements(
            summary,
            role_summaries,
            job_summaries=job_summaries,
            window_start_at=window_start_at,
            window_end_at=window_end_at,
            cumulative_start_at=cumulative_start_at,
            cumulative_end_at=cumulative_end_at,
            recruitment_progress=recruitment_progress,
            search_batch_id=search_batch_id,
        ),
        {"tag": "hr"},
        _markdown_block(
            _build_detail_documents_markdown(
                detail_pdf_urls_by_job
                or {"达人营销总监": detail_pdf_url, "财务共享与管理经理": None}
            )
        ),
    ]
    return {
        "msg_type": "interactive",
        "card": {
            "config": {"wide_screen_mode": True},
            "header": {
                "template": "blue",
                "title": {"tag": "plain_text", "content": f"重点岗位招聘日报｜{report_date}"},
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
        key=lambda item: (
            _candidate_sort_priority(item),
            -(item.match_score or -1),
            item.candidate_name,
        ),
    )


def build_report_candidates(outcomes: list[CandidateProcessOutcome]) -> list[ReportCandidate]:
    return _aggregate_candidates(outcomes)


def select_featured_candidates(candidates: list[ReportCandidate]) -> list[ReportCandidate]:
    return _select_candidates(candidates, _is_featured_candidate)


def select_relevant_candidates(candidates: list[ReportCandidate]) -> list[ReportCandidate]:
    """Return every relevant candidate; score gates only needs_review results."""

    return _select_candidates(candidates, _is_relevant_candidate)


def select_review_candidates(candidates: list[ReportCandidate]) -> list[ReportCandidate]:
    return _select_candidates(
        candidates,
        lambda item: _is_needs_review_candidate(item) and not _requires_recollection(item),
    )


def select_recollection_candidates(candidates: list[ReportCandidate]) -> list[ReportCandidate]:
    return _select_candidates(candidates, _requires_recollection)


def _normalize_candidate_name(value: str | None) -> str:
    normalized = sanitize_candidate_name(value)
    if normalized is None:
        return "未命名候选人"
    return normalized


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
    return communication_status_priority(hit_status)


def _candidate_sort_priority(candidate: ReportCandidate) -> int:
    return _hit_status_priority(candidate.hit_status)


def _build_summary(
    report: SearchWorkflowReport,
    aggregated_candidates: list[ReportCandidate],
    *,
    total_candidates_seen: int | None = None,
    executed_keywords: int | None = None,
    screened_candidate_views: int | None = None,
    cumulative_screened_candidate_views: int | None = None,
    cumulative_relevant_count: int | None = None,
    cumulative_needs_review_count: int | None = None,
    cumulative_not_relevant_count: int | None = None,
    cumulative_greeted_count: int | None = None,
) -> FeishuReportSummary:
    return FeishuReportSummary(
        total_candidates_seen=total_candidates_seen,
        cumulative_screened_candidate_views=cumulative_screened_candidate_views,
        cumulative_relevant_count=cumulative_relevant_count,
        cumulative_needs_review_count=cumulative_needs_review_count,
        cumulative_not_relevant_count=cumulative_not_relevant_count,
        cumulative_greeted_count=cumulative_greeted_count,
        screened_candidate_views=screened_candidate_views,
        total_keywords=executed_keywords
        if executed_keywords is not None
        else report.total_keywords,
        hit_keywords=report.total_keywords,
        profiles_collected=report.profiles_collected,
        unique_candidates=len(aggregated_candidates),
        greeted_count=sum(
            1 for item in aggregated_candidates if is_sent_hit_status(item.hit_status)
        ),
        contacted_relevant_count=sum(
            1
            for item in aggregated_candidates
            if is_sent_hit_status(item.hit_status) and _is_relevant_candidate(item)
        ),
        skipped_recent_count=sum(
            1 for item in aggregated_candidates if item.hit_status == "skipped_recent_continue"
        ),
        relevant_count=sum(1 for item in aggregated_candidates if _is_relevant_candidate(item)),
        needs_review_count=sum(
            1
            for item in aggregated_candidates
            if _is_needs_review_candidate(item)
            and not _requires_recollection(item)
            and item.match_score is not None
            and item.match_score >= ACTIONABLE_NEEDS_REVIEW_MIN_SCORE
        ),
        recollection_count=sum(1 for item in aggregated_candidates if _requires_recollection(item)),
        not_relevant_count=sum(
            1 for item in aggregated_candidates if _is_not_relevant_candidate(item)
        ),
    )


def _build_process_overview_markdown(
    summary: FeishuReportSummary,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> str:
    window_text = _format_report_window(window_start_at, window_end_at)
    return "\n".join(
        [
            "**搜索执行过程**",
            f"- 统计窗口：{window_text}",
            f"- 执行搜索组合：{summary.total_keywords} 组",
            f"- 有候选人进入详情的组合：{summary.hit_keywords} 组",
            f"- 查看候选人详情：{summary.profiles_collected} 位",
            f"- 打招呼成功：{summary.greeted_count}",
            f"- 已打招呼且匹配：{summary.contacted_relevant_count}",
            f"- 7天内跳过：{summary.skipped_recent_count}",
        ]
    )


def _build_pipeline_metrics_markdown(metrics: list[JobPipelineMetrics]) -> str:
    if not metrics:
        return "**岗位采集与转化流水线**\n- 当前统计窗口暂无岗位流水线数据。"
    blocks = ["**岗位采集与转化流水线**"]
    for item in metrics:
        blocks.extend(
            [
                "",
                f"**{item.job_title}**",
                f"- 搜索词完成：{item.executed_keywords}",
                (
                    f"- 进入详情：{item.detail_entries}｜正式链接：{item.official_share_links}"
                    f"｜已关联最终身份：{item.identity_linked}"
                ),
                f"- 上海意向：通过 {item.shanghai_passed}｜拒绝 {item.shanghai_rejected}",
                (
                    f"- AI分析：{item.ai_analyzed}｜relevant {item.relevant_count}"
                    f"｜needs_review {item.needs_review_count}"
                    f"｜not_relevant {item.not_relevant_count}"
                ),
                f"- 打招呼：成功 {item.greeting_success}｜失败 {item.greeting_failed}",
            ]
        )
    blocks.append("\n*正式链接按 candidate_resume_source_locators 中的 share_link 记录统计。*")
    return "\n".join(blocks)


def _build_role_summaries(
    candidates: list[ReportCandidate],
    *,
    executed_keywords_by_role: dict[str, int],
) -> list[RoleReportSummary]:
    summaries = {
        role: RoleReportSummary(role_keyword=role, executed_keywords=count)
        for role, count in executed_keywords_by_role.items()
        if role
    }
    for candidate in candidates:
        roles = candidate.role_keywords or {"未识别岗位"}
        for role in roles:
            summary = summaries.setdefault(role, RoleReportSummary(role_keyword=role))
            summary.profiles_collected += 1
            if _is_relevant_candidate(candidate):
                summary.relevant_count += 1
            elif _requires_recollection(candidate):
                summary.recollection_count += 1
            elif _is_needs_review_candidate(candidate):
                summary.needs_review_count += 1
            elif _is_not_relevant_candidate(candidate):
                summary.not_relevant_count += 1
    return sorted(
        summaries.values(),
        key=lambda item: (-item.executed_keywords, item.role_keyword),
    )


def _build_role_overview_markdown(
    summary: FeishuReportSummary,
    role_summaries: list[RoleReportSummary],
) -> str:
    if not role_summaries:
        return "**重点岗位招聘结果**\n- 本统计窗口暂无招聘结果。"
    search_terms = "、".join(item.role_keyword for item in role_summaries)
    cumulative_text = (
        f" · 累计跑过候选人 {summary.total_candidates_seen} 位"
        if summary.total_candidates_seen is not None
        else ""
    )
    lines = [
        "**🎯 达人营销总监招聘成果**",
        f"搜索覆盖｜{search_terms}",
        "",
        f"搜索执行｜{summary.total_keywords} 种组合{cumulative_text}",
        f"筛选结果｜匹配 {summary.relevant_count} 份 · 待复核 {summary.needs_review_count} 份",
        f"沟通进展｜已打招呼 {summary.greeted_count} 份",
    ]
    return "\n".join(lines)


def _build_job_summaries(
    candidates: list[ReportCandidate],
    *,
    executed_keywords_by_role: dict[str, int],
    screened_candidate_views_by_role: dict[str, int],
    cumulative_screened_candidate_views_by_role: dict[str, int] | None = None,
    cumulative_metrics_by_job: dict[str, dict[str, int]] | None = None,
) -> list[JobReportSummary]:
    summaries = {
        "达人营销总监": JobReportSummary(job_name="达人营销总监"),
        "财务共享与管理经理": JobReportSummary(job_name="财务共享与管理经理"),
    }
    for role, count in executed_keywords_by_role.items():
        job = summaries[_job_name_for_search_direction(role)]
        job.search_directions.add(role)
        job.executed_keywords += count
    for role, count in screened_candidate_views_by_role.items():
        job = summaries[_job_name_for_search_direction(role)]
        job.search_directions.add(role)
        job.screened_candidate_views += count
    for role, count in (cumulative_screened_candidate_views_by_role or {}).items():
        job = summaries[_job_name_for_search_direction(role)]
        job.cumulative_screened_candidate_views += count

    fingerprints_by_job: dict[str, set[str]] = {name: set() for name in summaries}
    for candidate in candidates:
        job_names = {
            _job_name_for_search_direction(role)
            for role in (candidate.role_keywords or {"未识别岗位"})
        }
        for job_name in job_names:
            job = summaries[job_name]
            if candidate.candidate_fingerprint in fingerprints_by_job[job_name]:
                continue
            fingerprints_by_job[job_name].add(candidate.candidate_fingerprint)
            job.profiles_collected += 1
            if _is_relevant_candidate(candidate):
                job.relevant_count += 1
            elif (
                _is_needs_review_candidate(candidate)
                and not _requires_recollection(candidate)
                and candidate.match_score is not None
                and candidate.match_score >= ACTIONABLE_NEEDS_REVIEW_MIN_SCORE
            ):
                job.needs_review_count += 1
            if is_sent_hit_status(candidate.hit_status):
                job.greeted_count += 1
    for job_name, job in summaries.items():
        cumulative = (cumulative_metrics_by_job or {}).get(job_name, {})
        job.cumulative_relevant_count = int(cumulative.get("relevant", 0))
        job.cumulative_needs_review_count = int(cumulative.get("needs_review", 0))
        job.cumulative_greeted_count = int(cumulative.get("greeted", 0))
    return [summaries["达人营销总监"], summaries["财务共享与管理经理"]]


def _job_name_for_search_direction(role_keyword: str) -> str:
    if "财务共享" in role_keyword or "财务" in role_keyword:
        return "财务共享与管理经理"
    return "达人营销总监"


def _job_summaries_from_role_summaries(
    role_summaries: list[RoleReportSummary],
) -> list[JobReportSummary]:
    jobs = {
        "达人营销总监": JobReportSummary(job_name="达人营销总监"),
        "财务共享与管理经理": JobReportSummary(job_name="财务共享与管理经理"),
    }
    for role in role_summaries:
        job = jobs[_job_name_for_search_direction(role.role_keyword)]
        job.search_directions.add(role.role_keyword)
        job.executed_keywords += role.executed_keywords
        job.profiles_collected += role.profiles_collected
        job.relevant_count += role.relevant_count
        job.needs_review_count += role.needs_review_count
    return [jobs["达人营销总监"], jobs["财务共享与管理经理"]]


def _build_management_summary_markdown(
    summary: FeishuReportSummary,
    role_summaries: list[RoleReportSummary],
) -> str:
    search_terms = "、".join(item.role_keyword for item in role_summaries) or "达人营销总监"
    return "\n".join(
        [
            "**招聘进展**",
            f"- 跑过候选人｜当天 {summary.unique_candidates} 份"
            f"｜累计 {_metric_text(summary.total_candidates_seen)} 份",
            f"- 匹配｜当天 {summary.relevant_count} 份"
            f"｜累计 {_metric_text(summary.cumulative_relevant_count)} 份",
            f"- 待人工复核｜当天 {summary.needs_review_count} 份"
            f"｜累计 {_metric_text(summary.cumulative_needs_review_count)} 份",
            f"- 已打招呼｜当天 {summary.greeted_count} 份"
            f"｜累计 {_metric_text(summary.cumulative_greeted_count)} 份",
            "",
            "**其他工作量**",
            f"- 当天执行搜索组合：{summary.total_keywords} 种",
            f"- 搜索覆盖：{search_terms}",
        ]
    )


def _build_management_summary_elements(
    summary: FeishuReportSummary,
    role_summaries: list[RoleReportSummary],
    *,
    job_summaries: list[JobReportSummary] | None = None,
    window_start_at: datetime | None = None,
    window_end_at: datetime | None = None,
    cumulative_start_at: datetime | None = None,
    cumulative_end_at: datetime | None = None,
    recruitment_progress: RecruitmentProgressSnapshot | None = None,
    search_batch_id: str | None = None,
) -> list[dict[str, Any]]:
    jobs = job_summaries or _job_summaries_from_role_summaries(role_summaries)
    period_lines = [
        f"累计区间｜{_format_cumulative_interval(cumulative_start_at, cumulative_end_at)}",
    ]
    if search_batch_id:
        period_lines.insert(0, f"当前批次｜{search_batch_id}")
        period_lines.insert(
            1,
            f"批次执行时间｜{_format_report_window(window_start_at, window_end_at)}",
        )
    elements: list[dict[str, Any]] = [
        _markdown_block(
            "\n".join(
                [
                    "**🎯 重点岗位招聘进展**",
                    "",
                    *period_lines,
                ]
            )
        )
    ]
    for index, job in enumerate(jobs):
        if index:
            elements.append({"tag": "hr"})
        elements.extend(_build_job_progress_elements(job))
    if recruitment_progress is not None:
        elements.extend(
            [
                {"tag": "hr"},
                _markdown_block(_build_recruitment_progress_markdown(recruitment_progress)),
            ]
        )
    return elements


def _build_recruitment_progress_markdown(
    progress: RecruitmentProgressSnapshot,
) -> str:
    status_lines = [
        f"- {HR_REVIEW_STATUS_LABELS[status]}：{progress.hr_status_counts.get(status.value, 0)} 人"
        for status in HRReviewStatus
    ]
    return "\n".join(
        [
            "**HR 候选人审核与招聘进度**",
            "",
            "**AI 原始判断（三态，不受人工结论影响）**",
            f"- 匹配：{progress.ai_relevant} 人",
            f"- 待复核：{progress.ai_needs_review} 人",
            f"- 不匹配：{progress.ai_not_relevant} 人",
            "",
            f"**AI 可行动待办**：{progress.actionable_pending} 人",
            "",
            "**HR 实际招聘进度**",
            *status_lines,
            f"- 本批次已反馈候选人：{progress.feedback_candidates_in_window} 人",
        ]
    )


def _build_job_progress_elements(job: JobReportSummary) -> list[dict[str, Any]]:
    progress_table = {
        "tag": "column_set",
        "flex_mode": "none",
        "columns": [
            _metric_column(
                2,
                "**招聘进展明细**\n简历卡片浏览人次\n岗位匹配\n待人工复核\n已打招呼",
                text_align="left",
            ),
            _metric_column(
                1,
                "**本批次**\n"
                f"{_formatted_metric_text(job.screened_candidate_views)}\n"
                f"{job.relevant_count} 份\n"
                f"{job.needs_review_count} 份\n"
                f"{job.greeted_count} 份",
            ),
            _metric_column(
                1,
                "**累计**\n"
                f"{_formatted_metric_text(job.cumulative_screened_candidate_views)}\n"
                f"{job.cumulative_relevant_count} 份\n"
                f"{job.cumulative_needs_review_count} 份\n"
                f"{job.cumulative_greeted_count} 份",
            ),
        ],
    }
    return [
        _markdown_block(f"**{job.job_name}**"),
        progress_table,
        _markdown_block(_build_single_job_strategy_markdown(job)),
    ]


def _build_single_job_strategy_markdown(job: JobReportSummary) -> str:
    lines = ["**下一步建议**"]
    if job.relevant_count:
        lines.append(f"- 已有 {job.relevant_count} 位90分以上明确匹配候选人，建议优先推进沟通。")
    if job.needs_review_count:
        lines.append(
            f"- 完成 {job.needs_review_count} 位90分以上待复核候选人的人工判断，"
            "确认是否进入沟通名单。"
        )
    if not job.relevant_count and not job.needs_review_count:
        lines.append("- 本次暂无90分以上候选人，建议扩大搜索范围或调整搜索词与筛选条件。")
    return "\n".join(lines)


def _metric_column(
    weight: int,
    content: str,
    *,
    text_align: str = "center",
) -> dict[str, Any]:
    return {
        "tag": "column",
        "width": "weighted",
        "weight": weight,
        "horizontal_align": text_align,
        "vertical_align": "top",
        "elements": [_markdown_block(content, text_align=text_align)],
    }


def _format_report_interval(
    start_at: datetime | None,
    end_at: datetime | None,
) -> str:
    if start_at is None:
        return "-"
    end_date = (
        (end_at - timedelta(microseconds=1)).date() if end_at is not None else start_at.date()
    )
    if start_at.date() == end_date:
        return start_at.strftime("%Y-%m-%d")
    return f"{start_at:%Y-%m-%d} 至 {end_date:%Y-%m-%d}"


def _format_cumulative_interval(
    start_at: datetime | None,
    end_at: datetime | None,
) -> str:
    if start_at is None or end_at is None:
        return "-"
    return f"{start_at:%Y-%m-%d} 至 {end_at:%Y-%m-%d}"


def _build_detail_document_markdown(detail_pdf_url: str | None) -> str:
    if detail_pdf_url:
        return f"**今日候选人明细**\n[查看完整候选人明细及判断依据]({detail_pdf_url})"
    return "**今日候选人明细**\n完整候选人明细文档暂时无法下载，请稍后重试。"


def _build_detail_documents_markdown(
    detail_pdf_urls_by_job: dict[str, str | None],
) -> str:
    lines = ["**今日候选人明细**"]
    for job_name in ("达人营销总监", "财务共享与管理经理"):
        detail_pdf_url = detail_pdf_urls_by_job.get(job_name)
        if detail_pdf_url:
            lines.append(f"- {job_name}：[查看候选人明细及判断依据]({detail_pdf_url})")
        else:
            lines.append(f"- {job_name}：暂无90分以上候选人")
    return "\n".join(lines)


def _metric_text(value: int | None) -> str:
    return str(value) if value is not None else "-"


def _formatted_metric_text(value: int | None) -> str:
    return f"{value:,}" if value is not None else "-"


def _build_strategy_markdown(role_summaries: list[RoleReportSummary]) -> str:
    if not role_summaries:
        return "**策略提示**\n- 暂无足够数据形成调整建议。"
    relevant_count = sum(item.relevant_count for item in role_summaries)
    review_count = sum(item.needs_review_count for item in role_summaries)
    executed_count = sum(item.executed_keywords for item in role_summaries)
    lines = ["**💡 下一步建议**"]
    if relevant_count:
        lines.append(f"- 达人营销总监：本轮已有 {relevant_count} 位明确匹配，建议优先推进沟通。")
    if review_count:
        lines.append(f"- 完成 {review_count} 位待复核候选人的人工判断，确认是否进入沟通名单。")
    if executed_count and not relevant_count and not review_count:
        lines.append("- 本轮暂无可推进候选人，建议调整品牌池、搜索覆盖词或前置筛选条件。")
    return "\n".join(lines)


def _build_daily_strategy_markdown(summary: FeishuReportSummary) -> str:
    lines = ["**💡 下一步建议**"]
    if summary.relevant_count:
        lines.append(
            f"- 达人营销总监：已有 {summary.relevant_count} 位90分以上明确匹配候选人，"
            "建议优先推进沟通。"
        )
    if summary.needs_review_count:
        lines.append(
            f"- 达人营销总监：完成 {summary.needs_review_count} 位90分以上待复核"
            "候选人的人工判断，确认是否进入沟通名单。"
        )
    if not summary.relevant_count and not summary.needs_review_count:
        lines.append(
            "- 达人营销总监：本次暂无90分以上候选人，建议扩大搜索范围或调整搜索词与筛选条件。"
        )
    lines.append(
        "- 财务共享与管理经理：本次暂无90分以上候选人，建议扩大搜索范围或调整搜索词与筛选条件。"
    )
    return "\n".join(lines)


def _build_job_strategy_markdown(job_summaries: list[JobReportSummary]) -> str:
    lines = ["**💡 下一步建议**"]
    for job in job_summaries:
        if job.relevant_count:
            lines.append(
                f"- {job.job_name}：已有 {job.relevant_count} 位明确匹配，建议优先推进沟通。"
            )
        if job.needs_review_count:
            lines.append(
                f"- {job.job_name}：完成 {job.needs_review_count} 位待复核候选人的人工判断，"
                "确认是否进入沟通名单。"
            )
        if not job.relevant_count and not job.needs_review_count and job.executed_keywords:
            lines.append(
                f"- {job.job_name}：本轮暂无匹配或待复核候选人，建议继续扩大搜索或调整搜索条件。"
            )
    if len(lines) == 1:
        lines.append("- 当前统计窗口暂无岗位执行数据。")
    return "\n".join(lines)


def _format_rate(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        return "-"
    rate = numerator / denominator
    if numerator > 0 and rate < 0.001:
        return "<0.1%"
    return f"{rate:.1%}"


def _build_pdf_markdown(
    detail_pdf_url: str | None,
    *,
    ranked_count: int,
    review_count: int,
) -> str:
    total_count = ranked_count + review_count
    lines = [
        "**PDF**",
        (
            f"- PDF 分为“明确匹配”和“待人工复核”两部分：匹配 {ranked_count} 位，"
            f"待复核 {review_count} 位，共 {total_count} 位。"
        ),
    ]
    if detail_pdf_url:
        lines.append(f"- 完整简历PDF：[点击下载]({detail_pdf_url})")
    elif total_count:
        lines.append(
            "- 完整简历PDF已生成，但附件上传失败，当前暂不可下载；候选人摘要已在下方展示。"
        )
    else:
        lines.append("- 当前没有生成PDF：本窗口内暂无符合PDF条件的候选人。")
    return "\n".join(lines)


def _build_candidate_section(candidates: list[ReportCandidate]) -> str:
    if not candidates:
        return "今日无符合“75分以上且匹配”条件的命中简历。"
    blocks = [_format_candidate_block(candidate) for candidate in candidates]
    return "\n\n".join(blocks)


def _build_review_candidate_section(candidates: list[ReportCandidate]) -> str:
    if not candidates:
        return "今日没有需要人工复核的简历。"
    return "\n\n".join(_format_review_candidate_block(candidate) for candidate in candidates)


def _build_recollection_candidate_section(candidates: list[ReportCandidate]) -> str:
    if not candidates:
        return "今日没有需要重新采集的简历。"
    blocks: list[str] = []
    for candidate in candidates:
        role_text = " / ".join(sorted(filter(None, candidate.role_keywords))) or "无"
        keywords_text = " / ".join(sorted(filter(None, candidate.search_keywords))) or "无"
        blocks.append(
            "\n".join(
                [
                    f"姓名：{_escape_markdown(candidate.candidate_name)}｜状态：需重新进入详情采集",
                    f"岗位：{role_text}｜关键词：{keywords_text}",
                    "原因：历史采集未保存完整简历正文，AI分析失败；重新采集后将自动复评。",
                ]
            )
        )
    return "\n\n".join(blocks)


def _format_review_candidate_block(candidate: ReportCandidate) -> str:
    score_text = str(candidate.match_score) if candidate.match_score is not None else "未评分"
    role_text = " / ".join(sorted(filter(None, candidate.role_keywords))) or "无"
    keywords_text = " / ".join(sorted(filter(None, candidate.search_keywords))) or "无"
    reason_text = _format_review_reason(candidate)
    evidence_text = (
        candidate.recent_experience_summary or "；".join(candidate.evidence_summary[:3]) or "无"
    )
    return "\n".join(
        [
            f"姓名：{_escape_markdown(candidate.candidate_name)}｜分数：{score_text}｜状态：待人工复核",
            f"岗位：{role_text}｜关键词：{keywords_text}",
            f"待复核原因：{reason_text}",
            f"简历证据：{evidence_text}",
        ]
    )


def _format_candidate_block(candidate: ReportCandidate) -> str:
    score_text = str(candidate.match_score) if candidate.match_score is not None else "未评分"
    keywords_text = " / ".join(sorted(filter(None, candidate.search_keywords))) or "无"
    role_text = " / ".join(sorted(filter(None, candidate.role_keywords))) or "无"
    recent_work_text = candidate.recent_experience_summary or (
        candidate.evidence_summary[0] if candidate.evidence_summary else "无"
    )
    matched_work_text = (
        candidate.matched_experience_summary or "；".join(candidate.evidence_summary[:3]) or "无"
    )
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
    if candidate.hit_status == "send_failed:message_sending_disabled":
        return "待沟通"
    if candidate.hit_status.startswith("send_failed:"):
        return "打招呼失败"
    if _is_relevant_candidate(candidate):
        return "匹配未打招呼"
    if _is_needs_review_candidate(candidate):
        return "待人工复核"
    if _is_below_needs_review_score(candidate):
        return f"不匹配（未达{ACTIONABLE_NEEDS_REVIEW_MIN_SCORE}分待复核线）"
    if is_not_relevant_result(
        candidate.match_result,
        analysis_status=candidate.analysis_status,
    ):
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
    return _is_relevant_candidate(item) and _is_report_age_allowed(item.candidate_age)


def _is_relevant_candidate(item: ReportCandidate) -> bool:
    return is_relevant_result(
        item.match_result,
        analysis_status=item.analysis_status,
    )


def _is_needs_review_candidate(item: ReportCandidate) -> bool:
    return is_actionable_needs_review_result(
        item.match_result,
        item.match_score,
        analysis_status=item.analysis_status,
        analysis_reason=item.analysis_reason,
    )


def _has_needs_review_result(item: ReportCandidate) -> bool:
    return has_needs_review_result(
        item.match_result,
        analysis_status=item.analysis_status,
    )


def _is_below_needs_review_score(item: ReportCandidate) -> bool:
    return bool(
        _has_needs_review_result(item)
        and not _is_coze_api_failure_candidate(item)
        and (item.match_score is None or item.match_score < ACTIONABLE_NEEDS_REVIEW_MIN_SCORE)
    )


def _is_coze_api_failure_candidate(item: ReportCandidate) -> bool:
    return bool(_has_needs_review_result(item) and "Coze API" in (item.analysis_reason or ""))


def _requires_recollection(item: ReportCandidate) -> bool:
    return (
        _has_needs_review_result(item)
        and "Coze API" in (item.analysis_reason or "")
        and not item.recent_experience_summary
        and not item.evidence_summary
    )


def _format_review_reason(item: ReportCandidate) -> str:
    if _requires_recollection(item):
        return "历史采集未保存完整简历正文，需重新进入详情采集后自动复评。"
    reason = _normalize_text(item.analysis_reason or "未提供复核原因")
    if "Coze API" in reason:
        return "AI分析暂时失败，系统将在日报生成时自动复评；本次暂需人工复核。"
    return reason


def _is_not_relevant_candidate(item: ReportCandidate) -> bool:
    if _is_needs_review_candidate(item) or _is_relevant_candidate(item):
        return False
    return bool(
        _is_below_needs_review_score(item)
        or is_not_relevant_result(
            item.match_result,
            analysis_status=item.analysis_status,
        )
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


def _markdown_block(content: str, *, text_align: str | None = None) -> dict[str, Any]:
    text: dict[str, Any] = {"tag": "lark_md", "content": content}
    if text_align is not None:
        text["text_align"] = text_align
    return {"tag": "div", "text": text}
