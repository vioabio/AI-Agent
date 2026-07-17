from __future__ import annotations

import json
from collections import defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path
import re

from sqlalchemy import text

from liepin_rpa.coze_workflow import CozeMatchResult, analyze_resume_record_with_coze
from liepin_rpa.config import Settings
from liepin_rpa.db import (
    build_experience_hash,
    ensure_resume_experience_schema,
    get_analysis_cache_record,
    get_engine,
    get_latest_daily_report_run,
    list_contacted_candidate_records,
    record_daily_report_run,
    upsert_analysis_cache_record,
)
from liepin_rpa.feishu_report import (
    build_report_candidates,
    select_featured_candidates,
    send_feishu_daily_report,
)
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.pdf_upload import upload_pdf_if_configured
from liepin_rpa.profile_collection.resume_schema import sanitize_candidate_name
from liepin_rpa.resume_pdf_export import build_daily_resume_pdf_path, export_featured_resume_pdf
from liepin_rpa.search_execution.result_filter import CandidateProcessOutcome
from liepin_rpa.search_execution.workflow import KeywordWorkflowResult, SearchWorkflowReport

LOGGER = get_logger(__name__)
WORK_CONTENT_PREFIX = "工作内容："
PROJECT_CONTENT_PREFIX = "项目内容："
PROFILE_CONTENT_PREFIX = "候选人信息："
WORK_ITEM_SEPARATOR = "；"


def send_database_daily_report(
    settings: Settings,
    *,
    limit: int | None = None,
    use_cache: bool = False,
    dry_run: bool = False,
    report_date: date | None = None,
) -> dict[str, object]:
    ensure_resume_experience_schema(settings)
    updated_from, updated_before, window_mode = _build_report_window(settings, report_date)
    contacted_records = list_contacted_candidate_records(
        settings,
        limit=limit,
        updated_from=updated_from,
        updated_before=updated_before,
    )
    latest_hits = _load_latest_hit_map(settings)

    candidate_outcomes = _build_candidate_outcomes(
        settings,
        latest_hits=latest_hits,
        contacted_records=contacted_records,
        use_cache=use_cache,
    )
    if not candidate_outcomes:
        return {
            "records_loaded": 0,
            "candidate_outcomes": 0,
            "feishu_sent": False,
            "window_mode": window_mode,
            "window_start_at": updated_from.isoformat() if updated_from is not None else None,
            "window_end_at": updated_before.isoformat() if updated_before is not None else None,
            "report_date": report_date.isoformat() if report_date is not None else None,
            "dry_run": dry_run,
        }

    detail_pdf_path = _export_featured_candidate_pdf(
        settings,
        candidate_outcomes=candidate_outcomes,
        contacted_records=contacted_records,
    )
    upload_result = upload_pdf_if_configured(settings, detail_pdf_path) if not dry_run else None
    detail_pdf_url = upload_result.public_url if upload_result is not None else None

    report = _build_report_from_db_snapshot(candidate_outcomes)
    feishu_sent = False if dry_run else send_feishu_daily_report(
        settings,
        report,
        detail_pdf_url=detail_pdf_url,
        window_start_at=updated_from,
        window_end_at=updated_before,
    )
    report_run_id = None
    if feishu_sent:
        report_run_id = record_daily_report_run(
            settings,
            window_start_at=updated_from,
            window_end_at=updated_before or datetime.now(),
            records_loaded=len(contacted_records),
            candidate_outcomes=len(candidate_outcomes),
            detail_pdf_path=str(detail_pdf_path) if detail_pdf_path is not None else None,
            detail_pdf_url=detail_pdf_url,
        )
    return {
        "records_loaded": len(contacted_records),
        "candidate_outcomes": len(candidate_outcomes),
        "feishu_sent": feishu_sent,
        "report_run_id": report_run_id,
        "detail_pdf_path": str(detail_pdf_path) if detail_pdf_path is not None else None,
        "detail_pdf_url": detail_pdf_url,
        "window_mode": window_mode,
        "window_start_at": updated_from.isoformat() if updated_from is not None else None,
        "window_end_at": updated_before.isoformat() if updated_before is not None else None,
        "report_date": report_date.isoformat() if report_date is not None else None,
        "dry_run": dry_run,
    }


def previous_local_date() -> date:
    return date.today() - timedelta(days=1)


def _build_report_date_window(report_date: date | None) -> tuple[datetime | None, datetime | None]:
    if report_date is None:
        return None, None
    start_at = datetime.combine(report_date, datetime.min.time())
    end_at = start_at + timedelta(days=1)
    return start_at, end_at


def _build_report_window(
    settings: Settings,
    report_date: date | None,
) -> tuple[datetime | None, datetime | None, str]:
    if report_date is not None:
        start_at, end_at = _build_report_date_window(report_date)
        return start_at, end_at, "fixed_date"
    latest_run = get_latest_daily_report_run(settings)
    end_at = datetime.now()
    if latest_run is None:
        return None, end_at, "since_last_success_or_all"
    sent_at = latest_run.get("sent_at")
    if isinstance(sent_at, datetime):
        return sent_at, end_at, "since_last_success"
    return None, end_at, "since_last_success_or_all"


def _build_candidate_outcomes(
    settings: Settings,
    *,
    latest_hits: dict[str, dict[str, object]],
    contacted_records: list[dict[str, object]],
    use_cache: bool,
) -> list[CandidateProcessOutcome]:
    contacted_by_fingerprint = {
        str(item.get("candidate_fingerprint") or ""): item
        for item in contacted_records
        if str(item.get("candidate_fingerprint") or "")
    }
    latest_cache_by_fingerprint = _load_latest_analysis_map(settings)

    all_fingerprints = set(latest_hits.keys()) | set(contacted_by_fingerprint.keys())
    outcomes: list[CandidateProcessOutcome] = []
    for candidate_fingerprint in all_fingerprints:
        record = contacted_by_fingerprint.get(candidate_fingerprint)
        latest_hit = latest_hits.get(candidate_fingerprint, {})
        outcome = _build_candidate_outcome(
            settings,
            candidate_fingerprint=candidate_fingerprint,
            record=record,
            latest_hit=latest_hit,
            latest_cache_by_fingerprint=latest_cache_by_fingerprint,
            use_cache=use_cache,
        )
        if outcome is not None:
            outcomes.append(outcome)
    return outcomes


def _build_candidate_outcome(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    record: dict[str, object] | None,
    latest_hit: dict[str, object],
    latest_cache_by_fingerprint: dict[str, dict[str, object]],
    use_cache: bool,
) -> CandidateProcessOutcome | None:
    candidate_name = sanitize_candidate_name(
        _optional_str((record or {}).get("candidate_name"))
        or _optional_str(latest_hit.get("candidate_name"))
    )
    search_keyword = (
        _optional_str(latest_hit.get("search_keyword"))
        or _optional_str((record or {}).get("search_keyword"))
        or ""
    )
    hit_status = _optional_str(latest_hit.get("hit_status")) or (
        "sent" if record is not None else "reviewed"
    )
    brand_keyword, role_keyword = _split_search_keyword(search_keyword)

    coze_result: CozeMatchResult | None = None
    normalized_analysis_status = _optional_str((record or {}).get("analysis_status")) or "未分析"
    experience = _optional_str((record or {}).get("experience")) or ""

    if record is not None and experience:
        experience_hash = build_experience_hash(experience)
        cache_record = None
        if use_cache:
            cache_record = get_analysis_cache_record(
                settings,
                candidate_fingerprint=candidate_fingerprint,
                experience_hash=experience_hash,
            )
        coze_result = _coze_result_from_cache_record(cache_record) if cache_record else None
        if coze_result is None:
            coze_result = analyze_resume_record_with_coze(
                settings,
                resume_record={
                    "id": int(record.get("id") or 0),
                    "candidate_name": candidate_name or "",
                    "source_platform": _optional_str(record.get("source_platform")) or "liepin",
                    "search_keyword": search_keyword,
                    "experience": experience,
                    "analysis_status": normalized_analysis_status,
                },
            )
        normalized_analysis_status = _derive_analysis_status(coze_result)
        upsert_analysis_cache_record(
            settings,
            candidate_fingerprint=candidate_fingerprint,
            experience_hash=experience_hash,
            analysis_status=normalized_analysis_status,
            match_result=coze_result.match_result,
            match_score=coze_result.match_score,
            score_breakdown=coze_result.score_breakdown,
            analysis_reason=coze_result.analysis_reason,
            evidence_summary=coze_result.evidence_summary,
        )
    else:
        latest_cache = latest_cache_by_fingerprint.get(candidate_fingerprint)
        coze_result = _coze_result_from_cache_record(latest_cache) if latest_cache is not None else None
        if coze_result is not None:
            normalized_analysis_status = _derive_analysis_status(coze_result)

    recent_experience_summary = _extract_recent_experience_summary(experience)
    matched_experience_summary = _extract_matched_experience_summary(
        evidence_summary=list(coze_result.evidence_summary) if coze_result is not None else [],
    )
    candidate_age = _extract_candidate_age(experience)

    return CandidateProcessOutcome(
        candidate_name=candidate_name,
        candidate_fingerprint=candidate_fingerprint,
        search_keyword=search_keyword,
        brand_keyword=brand_keyword,
        role_keyword=role_keyword,
        communication_label="继续沟通" if hit_status == "skipped_recent_continue" else "立即沟通",
        analysis_status=normalized_analysis_status,
        match_result=coze_result.match_result if coze_result is not None else None,
        match_score=coze_result.match_score if coze_result is not None else None,
        score_breakdown=dict(coze_result.score_breakdown) if coze_result is not None else {},
        analysis_reason=coze_result.analysis_reason if coze_result is not None else None,
        evidence_summary=list(coze_result.evidence_summary) if coze_result is not None else [],
        hit_status=hit_status,
        continue_without_db_record=False,
        message_send_success=hit_status in {"sent", "immediate_job_confirmed"},
        message_send_reason=hit_status,
        message_send_attempts=1 if hit_status in {"sent", "immediate_job_confirmed"} else 0,
        message_screenshot_path=None,
        recent_experience_summary=recent_experience_summary,
        matched_experience_summary=matched_experience_summary,
        candidate_age=candidate_age,
    )


def _build_report_from_db_snapshot(
    candidate_outcomes: list[CandidateProcessOutcome],
) -> SearchWorkflowReport:
    grouped: dict[str, list[CandidateProcessOutcome]] = defaultdict(list)
    for outcome in candidate_outcomes:
        grouped[outcome.search_keyword].append(outcome)

    keyword_runs: list[KeywordWorkflowResult] = []
    for index, search_keyword in enumerate(sorted(grouped.keys()), start=1):
        outcomes = grouped[search_keyword]
        brand_keyword, role_keyword = _split_search_keyword(search_keyword)
        keyword_runs.append(
            KeywordWorkflowResult(
                index=index,
                brand_keyword=brand_keyword,
                role_keyword=role_keyword,
                matched=any(
                    item.analysis_status == "匹配" or item.match_result == "relevant"
                    for item in outcomes
                ),
                profiles_collected=len(outcomes),
                end_reason="db_snapshot",
                report_path="",
                candidate_outcomes=outcomes,
            )
        )

    return SearchWorkflowReport(
        total_keywords=len(keyword_runs),
        matched_keywords=sum(1 for item in keyword_runs if item.matched),
        profiles_collected=len(candidate_outcomes),
        keyword_runs=keyword_runs,
        candidate_outcomes=candidate_outcomes,
        report_path="",
    )


def _export_featured_candidate_pdf(
    settings: Settings,
    *,
    candidate_outcomes: list[CandidateProcessOutcome],
    contacted_records: list[dict[str, object]],
) -> Path | None:
    report_candidates = build_report_candidates(candidate_outcomes)
    featured_candidates = select_featured_candidates(report_candidates)
    experience_by_fingerprint = {
        str(item.get("candidate_fingerprint") or ""): _optional_str(item.get("experience")) or ""
        for item in contacted_records
        if str(item.get("candidate_fingerprint") or "")
    }
    output_dir = settings.artifacts_dir / "daily_reports"
    output_path = build_daily_resume_pdf_path(
        output_dir,
        report_date=datetime.now().strftime("%Y-%m-%d_%H%M%S"),
    )
    exported = export_featured_resume_pdf(
        output_path=output_path,
        candidates=featured_candidates,
        experience_by_fingerprint=experience_by_fingerprint,
    )
    return exported


def _load_latest_hit_map(settings: Settings) -> dict[str, dict[str, object]]:
    engine = get_engine(settings)
    sql = text(
        """
        SELECT h.candidate_fingerprint, h.candidate_name, h.search_keyword, h.hit_status, h.created_at
        FROM candidate_resume_search_hits h
        INNER JOIN (
            SELECT candidate_fingerprint, MAX(id) AS max_id
            FROM candidate_resume_search_hits
            GROUP BY candidate_fingerprint
        ) latest
            ON latest.candidate_fingerprint = h.candidate_fingerprint
           AND latest.max_id = h.id
        """
    )
    with engine.begin() as connection:
        rows = connection.execute(sql).mappings().all()
    return {str(row["candidate_fingerprint"]): dict(row) for row in rows}


def _load_latest_analysis_map(settings: Settings) -> dict[str, dict[str, object]]:
    engine = get_engine(settings)
    sql = text(
        """
        SELECT c.candidate_fingerprint,
               c.analysis_status,
               c.match_result,
               c.match_score,
               c.score_breakdown_json,
               c.analysis_reason,
               c.evidence_summary_json,
               c.updated_at
        FROM candidate_resume_analysis_cache c
        INNER JOIN (
            SELECT candidate_fingerprint, MAX(id) AS max_id
            FROM candidate_resume_analysis_cache
            GROUP BY candidate_fingerprint
        ) latest
            ON latest.candidate_fingerprint = c.candidate_fingerprint
           AND latest.max_id = c.id
        """
    )
    with engine.begin() as connection:
        rows = connection.execute(sql).mappings().all()
    return {str(row["candidate_fingerprint"]): dict(row) for row in rows}


def _coze_result_from_cache_record(record: dict[str, object] | None) -> CozeMatchResult | None:
    if record is None:
        return None
    match_result = _optional_str(record.get("match_result"))
    analysis_status = _optional_str(record.get("analysis_status"))
    return CozeMatchResult(
        matched=bool(analysis_status == "匹配" or match_result == "relevant"),
        match_result=match_result,
        match_score=_optional_int(record.get("match_score")),
        score_breakdown=_parse_score_breakdown(record.get("score_breakdown_json")),
        analysis_reason=_optional_str(record.get("analysis_reason")),
        evidence_summary=_parse_string_list(record.get("evidence_summary_json")),
        raw_data="",
        parsed_payload={},
    )


def _parse_score_breakdown(value: object) -> dict[str, int]:
    payload = _parse_json_value(value)
    if not isinstance(payload, dict):
        return {}
    result: dict[str, int] = {}
    for key, raw in payload.items():
        if not isinstance(key, str):
            continue
        normalized = _optional_int(raw)
        if normalized is not None:
            result[key] = normalized
    return result


def _parse_string_list(value: object) -> list[str]:
    payload = _parse_json_value(value)
    if not isinstance(payload, list):
        return []
    return [item.strip() for item in payload if isinstance(item, str) and item.strip()]


def _parse_json_value(value: object) -> object:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return None


def _derive_analysis_status(result: CozeMatchResult) -> str:
    if result.match_result == "relevant" or result.matched:
        return "匹配"
    if result.match_result == "needs_review":
        return "待复核"
    return "不匹配"


def _split_search_keyword(search_keyword: str) -> tuple[str, str]:
    normalized = (search_keyword or "").replace("，", "|").replace(",", "|")
    if "|" not in normalized:
        return normalized.strip(), ""
    brand_keyword, role_keyword = normalized.split("|", 1)
    return brand_keyword.strip(), role_keyword.strip()


def _optional_str(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized or None


def _optional_int(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.isdigit():
            return int(stripped)
    return None


def _extract_candidate_age(experience: str) -> int | None:
    if not experience:
        return None
    profile_block = experience
    if WORK_CONTENT_PREFIX in profile_block:
        profile_block = profile_block.split(WORK_CONTENT_PREFIX, 1)[0]
    if PROJECT_CONTENT_PREFIX in profile_block:
        profile_block = profile_block.split(PROJECT_CONTENT_PREFIX, 1)[0]
    if PROFILE_CONTENT_PREFIX not in profile_block:
        return None
    match = re.search(r"年龄[:：]\s*([1-9]\d?)\s*岁", profile_block)
    if not match:
        return None
    age = int(match.group(1))
    if 16 <= age <= 80:
        return age
    return None


def _extract_recent_experience_summary(experience: str) -> str | None:
    if not experience:
        return None
    work_block = experience
    if PROJECT_CONTENT_PREFIX in work_block:
        work_block = work_block.split(PROJECT_CONTENT_PREFIX, 1)[0]
    if WORK_CONTENT_PREFIX in work_block:
        work_block = work_block.split(WORK_CONTENT_PREFIX, 1)[1]
    parts = [item.strip() for item in work_block.split(WORK_ITEM_SEPARATOR) if item.strip()]
    if len(parts) < 3:
        return None
    company_name = parts[0]
    position_name = parts[1]
    period_text = parts[2]
    return f"{company_name}｜{position_name}｜{period_text}"


def _extract_matched_experience_summary(*, evidence_summary: list[str]) -> str | None:
    for item in evidence_summary:
        summary = _parse_evidence_company_role_time(item)
        if summary:
            return summary
    return None


def _parse_evidence_company_role_time(text_value: str) -> str | None:
    if not text_value:
        return None
    normalized = " ".join(text_value.split())
    company_match = re.search(r"(?:任职公司|目标品牌任职证据|公司)[:：]\s*([^，,；;]+)", normalized)
    role_match = re.search(r"(?:岗位名称|岗位|职位)[:：]\s*([^，,；;]+)", normalized)
    time_match = re.search(r"(?:任职时间|时间)[:：]\s*([^，,；;]+)", normalized)
    company_name = company_match.group(1).strip() if company_match else None
    role_name = role_match.group(1).strip() if role_match else None
    time_text = time_match.group(1).strip() if time_match else None
    parts = [item for item in (company_name, role_name, time_text) if item]
    if not parts:
        return None
    return "｜".join(parts)
