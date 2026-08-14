from __future__ import annotations

import json
import hashlib
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import re

from sqlalchemy import text

from liepin_rpa.coze_workflow import CozeMatchResult, analyze_resume_record_with_coze
from liepin_rpa.config import Settings
from liepin_rpa.db import (
    build_experience_hash,
    ensure_resume_experience_schema,
    get_analysis_cache_record,
    get_candidate_record_by_fingerprint,
    get_engine,
    start_daily_report_delivery,
    update_daily_report_delivery,
    upsert_analysis_cache_record,
)
from liepin_rpa.feishu_report import (
    ReportCandidate,
    build_report_candidates,
    select_featured_candidates,
    select_review_candidates,
    select_recollection_candidates,
    send_feishu_daily_report,
)
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.pdf_upload import upload_pdf_if_configured
from liepin_rpa.profile_collection.resume_schema import sanitize_candidate_name
from liepin_rpa.resume_pdf_export import build_daily_resume_pdf_path, export_featured_resume_pdf
from liepin_rpa.report_batch import BATCH_STATE_FILENAME, SearchBatchStateStore
from liepin_rpa.report_candidate_history import (
    REPORT_CANDIDATE_HISTORY_FILENAME,
    ReportCandidateHistoryStore,
)
from liepin_rpa.search_execution.result_filter import CandidateProcessOutcome
from liepin_rpa.search_execution.workflow import KeywordWorkflowResult, SearchWorkflowReport

LOGGER = get_logger(__name__)
DAILY_REPORT_DETAIL_MIN_SCORE = 90
WORK_CONTENT_PREFIX = "工作内容："
PROJECT_CONTENT_PREFIX = "项目内容："
PROFILE_CONTENT_PREFIX = "候选人信息："
WORK_ITEM_SEPARATOR = "；"
SHANGHAI_TIMEZONE = timezone(timedelta(hours=8))


def send_database_daily_report(
    settings: Settings,
    *,
    limit: int | None = None,
    use_cache: bool = False,
    dry_run: bool = False,
    report_date: date | None = None,
    window_start_at: datetime | None = None,
    window_end_at: datetime | None = None,
    batch_id: str | None = None,
    feishu_webhook_override: str | None = None,
    advance_watermark: bool = True,
) -> dict[str, object]:
    ensure_resume_experience_schema(settings)
    updated_from, updated_before, window_mode = _build_report_window(
        settings,
        report_date,
        window_start_at=window_start_at,
        window_end_at=window_end_at,
        batch_id=batch_id,
    )
    contacted_records = _load_report_candidate_records(
        settings,
        limit=limit,
        window_start_at=updated_from,
        window_end_at=updated_before,
    )
    latest_hits = _overlay_latest_hit_status(
        contacted_records,
        latest_hit_map=_load_latest_hit_map(settings),
    )

    candidate_outcomes = _build_candidate_outcomes(
        settings,
        latest_hits=latest_hits,
        contacted_records=contacted_records,
        use_cache=use_cache,
    )
    candidate_history = ReportCandidateHistoryStore(
        settings.artifacts_dir / REPORT_CANDIDATE_HISTORY_FILENAME
    )
    previously_reported_fingerprints = candidate_history.load_fingerprints()
    previously_reported_count = sum(
        1
        for outcome in candidate_outcomes
        if outcome.candidate_fingerprint in previously_reported_fingerprints
    )
    candidate_outcomes = [
        outcome
        for outcome in candidate_outcomes
        if outcome.candidate_fingerprint not in previously_reported_fingerprints
    ]
    total_candidates_seen = _count_total_seen_candidates(settings)
    cumulative_metrics = _count_cumulative_report_metrics(settings)
    cumulative_metrics_by_job = _count_cumulative_report_metrics_by_job(settings)
    cumulative_start_at, cumulative_end_at = _load_cumulative_report_window(settings)
    executed_keywords_by_role = _count_executed_keywords_by_role_from_log(
        settings,
        window_start_at=updated_from,
        window_end_at=updated_before,
    )
    executed_keywords = (
        sum(executed_keywords_by_role.values()) if executed_keywords_by_role is not None else None
    )
    screened_candidate_views_by_role = _count_screened_candidate_views_by_role_from_log(
        settings,
        window_start_at=updated_from,
        window_end_at=updated_before,
    )
    screened_candidate_views = (
        sum(screened_candidate_views_by_role.values())
        if screened_candidate_views_by_role is not None
        else None
    )
    cumulative_screened_views_by_role = _count_screened_candidate_views_by_role_from_log(
        settings,
        window_start_at=cumulative_start_at,
        window_end_at=datetime.now(),
    )
    cumulative_metrics_by_job["screened_views_by_role"] = (
        cumulative_screened_views_by_role or {}
    )
    cumulative_screened_candidate_views = (
        sum(cumulative_screened_views_by_role.values())
        if cumulative_screened_views_by_role is not None
        else None
    )
    if not candidate_outcomes:
        return {
            "records_loaded": 0,
            "candidate_outcomes": 0,
            "feishu_sent": False,
            "total_candidates_seen": total_candidates_seen,
            "executed_keywords": executed_keywords,
            "executed_keywords_by_role": executed_keywords_by_role,
            "screened_candidate_views": screened_candidate_views,
            "cumulative_screened_candidate_views": cumulative_screened_candidate_views,
            "window_mode": window_mode,
            "window_start_at": updated_from.isoformat() if updated_from is not None else None,
            "window_end_at": updated_before.isoformat() if updated_before is not None else None,
            "report_date": report_date.isoformat() if report_date is not None else None,
            "dry_run": dry_run,
            "advance_watermark": advance_watermark,
            "webhook_mode": "override" if feishu_webhook_override else "default",
        }
    report_key = _build_daily_report_key(updated_from, contacted_records)
    tracked_delivery = not dry_run and advance_watermark
    delivery_run: dict[str, object] | None = None
    if tracked_delivery:
        delivery_run = start_daily_report_delivery(
            settings,
            report_key=report_key,
            window_start_at=updated_from,
            window_end_at=updated_before or datetime.now(),
            records_loaded=len(contacted_records),
            candidate_outcomes=len(candidate_outcomes),
        )
        if delivery_run.get("delivery_status") == "sent":
            return {
                "records_loaded": len(contacted_records),
                "candidate_outcomes": len(candidate_outcomes),
                "feishu_sent": False,
                "already_sent": True,
                "report_run_id": int(delivery_run["id"]),
                "report_key": report_key,
                "detail_pdf_path": delivery_run.get("detail_pdf_path"),
                "detail_pdf_url": delivery_run.get("detail_pdf_url"),
                "window_mode": window_mode,
                "window_start_at": updated_from.isoformat() if updated_from is not None else None,
                "window_end_at": updated_before.isoformat() if updated_before is not None else None,
                "dry_run": dry_run,
                "advance_watermark": advance_watermark,
            }

    detail_pdf_paths_by_job = _export_candidate_pdfs_by_job(
        settings,
        candidate_outcomes=candidate_outcomes,
        contacted_records=contacted_records,
        report_key=report_key,
    )
    upload_results_by_job = {
        job_name: upload_pdf_if_configured(settings, pdf_path)
        for job_name, pdf_path in detail_pdf_paths_by_job.items()
    } if not dry_run else {}
    detail_pdf_urls_by_job = {
        job_name: (
            upload_results_by_job[job_name].public_url
            if job_name in upload_results_by_job else None
        )
        for job_name in ("达人营销总监", "财务共享与管理经理")
    }
    detail_pdf_path = detail_pdf_paths_by_job.get("达人营销总监")
    upload_result = upload_results_by_job.get("达人营销总监")
    detail_pdf_url = detail_pdf_urls_by_job.get("达人营销总监")
    if tracked_delivery:
        update_daily_report_delivery(
            settings,
            report_key=report_key,
            delivery_status="uploaded" if upload_result and upload_result.uploaded else "pdf_ready",
            detail_pdf_path=str(detail_pdf_path) if detail_pdf_path is not None else None,
            detail_pdf_url=detail_pdf_url,
            uploaded=bool(upload_result and upload_result.uploaded),
        )
    if detail_pdf_path is not None and upload_result is not None and not upload_result.uploaded:
        if tracked_delivery:
            update_daily_report_delivery(
                settings,
                report_key=report_key,
                delivery_status="upload_failed",
                detail_pdf_path=str(detail_pdf_path),
            )
        LOGGER.warning(
            "Sending daily report card without PDF link because upload failed",
            extra={"event": "daily_report_send_without_pdf", "context": {}},
        )

    report = _build_report_from_db_snapshot(candidate_outcomes)
    report_settings = (
        settings.model_copy(update={"feishu_bot_webhook": feishu_webhook_override})
        if feishu_webhook_override
        else settings
    )
    feishu_sent = False if dry_run else send_feishu_daily_report(
        report_settings,
        report,
        detail_pdf_url=detail_pdf_url,
        detail_pdf_urls_by_job=detail_pdf_urls_by_job,
        window_start_at=updated_from,
        window_end_at=updated_before,
        total_candidates_seen=total_candidates_seen,
        executed_keywords=executed_keywords,
        executed_keywords_by_role=executed_keywords_by_role,
        screened_candidate_views=screened_candidate_views,
        screened_candidate_views_by_role=screened_candidate_views_by_role,
        cumulative_screened_candidate_views=cumulative_screened_candidate_views,
        cumulative_relevant_count=cumulative_metrics["relevant"],
        cumulative_needs_review_count=cumulative_metrics["needs_review"],
        cumulative_not_relevant_count=cumulative_metrics["not_relevant"],
        cumulative_greeted_count=cumulative_metrics["greeted"],
        cumulative_start_at=cumulative_start_at,
        cumulative_end_at=cumulative_end_at,
        cumulative_metrics_by_job=cumulative_metrics_by_job,
    )
    report_run_id = int(delivery_run["id"]) if delivery_run is not None else None
    if tracked_delivery:
        update_daily_report_delivery(
            settings,
            report_key=report_key,
            delivery_status="sent" if feishu_sent else "feishu_failed",
            detail_pdf_path=str(detail_pdf_path) if detail_pdf_path is not None else None,
            detail_pdf_url=detail_pdf_url,
            uploaded=bool(upload_result and upload_result.uploaded),
            feishu_attempted=True,
        )
    if feishu_sent and advance_watermark:
        displayed_fingerprints = {
            candidate.candidate_fingerprint
            for candidate in build_report_candidates(candidate_outcomes)
            if candidate.match_score is not None
            and candidate.match_score >= DAILY_REPORT_DETAIL_MIN_SCORE
        }
        candidate_history.record_delivery(
            report_key=report_key,
            candidate_fingerprints=displayed_fingerprints,
            window_start_at=updated_from,
            window_end_at=updated_before,
        )
    return {
        "records_loaded": len(contacted_records),
        "candidate_outcomes": len(candidate_outcomes),
        "previously_reported_candidates_excluded": previously_reported_count,
        "feishu_sent": feishu_sent,
        "report_run_id": report_run_id,
        "report_key": report_key,
        "total_candidates_seen": total_candidates_seen,
        "executed_keywords": executed_keywords,
        "executed_keywords_by_role": executed_keywords_by_role,
        "screened_candidate_views": screened_candidate_views,
        "cumulative_screened_candidate_views": cumulative_screened_candidate_views,
        "detail_pdf_path": str(detail_pdf_path) if detail_pdf_path is not None else None,
        "detail_pdf_url": detail_pdf_url,
        "detail_pdf_paths_by_job": {
            job_name: str(path) if path is not None else None
            for job_name, path in detail_pdf_paths_by_job.items()
        },
        "detail_pdf_urls_by_job": detail_pdf_urls_by_job,
        "window_mode": window_mode,
        "window_start_at": updated_from.isoformat() if updated_from is not None else None,
        "window_end_at": updated_before.isoformat() if updated_before is not None else None,
        "report_date": report_date.isoformat() if report_date is not None else None,
        "dry_run": dry_run,
        "advance_watermark": advance_watermark,
        "webhook_mode": "override" if feishu_webhook_override else "default",
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
    *,
    window_start_at: datetime | None = None,
    window_end_at: datetime | None = None,
    batch_id: str | None = None,
) -> tuple[datetime | None, datetime | None, str]:
    if batch_id is not None and (
        report_date is not None or window_start_at is not None or window_end_at is not None
    ):
        raise ValueError("--batch-id cannot be combined with a date or custom report window.")
    if report_date is not None and (window_start_at is not None or window_end_at is not None):
        raise ValueError("--report-date cannot be used with a custom report window.")
    if window_start_at is not None or window_end_at is not None:
        return window_start_at, window_end_at or datetime.now(), "custom_window"
    if report_date is not None:
        start_at, end_at = _build_report_date_window(report_date)
        return start_at, end_at, "fixed_date"
    batch_window = SearchBatchStateStore(
        settings.artifacts_dir / BATCH_STATE_FILENAME
    ).latest_completed_window(batch_id=batch_id)
    return batch_window.started_at, batch_window.ended_at, "completed_search_batch"


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

    # Daily reports must be scoped to candidates contacted in the current report window.
    # Search-hit/cache tables are only metadata helpers; including their full history here
    # would resend old candidates when no new records were loaded.
    all_fingerprints = set(contacted_by_fingerprint.keys())
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
    job_profile_id = _optional_str((record or {}).get("job_profile_id"))
    if job_profile_id == "finance_shared_management_v1":
        brand_keyword = ""
        role_keyword = "财务共享与管理经理"
    elif job_profile_id == "daren_live_bd_v1" and not role_keyword:
        role_keyword = "达人营销总监"
    analysis_settings = _analysis_settings_for_search_keyword(
        settings,
        search_keyword,
        job_profile_id=job_profile_id,
    )

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
                analysis_version=analysis_settings.coze_analysis_version,
            )
            if cache_record is None:
                cache_record = latest_cache_by_fingerprint.get(candidate_fingerprint)
        coze_result = _coze_result_from_cache_record(cache_record) if cache_record else None
        if coze_result is not None and _is_transient_api_review(coze_result) and not use_cache:
            LOGGER.info(
                "Retrying transient Coze analysis while building daily report",
                extra={
                    "event": "daily_report_coze_reanalysis",
                    "context": {"candidate_fingerprint": candidate_fingerprint},
                },
            )
            coze_result = None
        if coze_result is None:
            coze_result = analyze_resume_record_with_coze(
                analysis_settings,
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
            experience=experience,
            analysis_version=analysis_settings.coze_analysis_version,
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
        message_send_success=hit_status == "sent",
        message_send_reason=hit_status,
        message_send_attempts=1 if hit_status == "sent" else 0,
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


def _export_candidate_pdfs_by_job(
    settings: Settings,
    *,
    candidate_outcomes: list[CandidateProcessOutcome],
    contacted_records: list[dict[str, object]],
    report_key: str,
) -> dict[str, Path | None]:
    report_candidates = build_report_candidates(candidate_outcomes)
    report_candidates = [
        candidate
        for candidate in report_candidates
        if candidate.match_score is not None
        and candidate.match_score >= DAILY_REPORT_DETAIL_MIN_SCORE
    ]
    candidate_groups = {
        "达人营销总监": [
            candidate for candidate in report_candidates
            if not any("财务" in role for role in candidate.role_keywords)
        ],
        "财务共享与管理经理": [
            candidate for candidate in report_candidates
            if any("财务" in role for role in candidate.role_keywords)
        ],
    }
    experience_by_fingerprint = {
        str(item.get("candidate_fingerprint") or ""): _optional_str(item.get("experience")) or ""
        for item in contacted_records
        if str(item.get("candidate_fingerprint") or "")
    }
    all_candidates = [candidate for items in candidate_groups.values() for candidate in items]
    _backfill_pdf_experience_from_database(
        settings,
        featured_candidates=all_candidates,
        experience_by_fingerprint=experience_by_fingerprint,
    )
    resume_link_by_fingerprint = _load_candidate_resume_links(
        settings,
        candidate_fingerprints={
            candidate.candidate_fingerprint
            for candidate in all_candidates
        },
    )
    output_dir = settings.artifacts_dir / "daily_reports"
    exported_by_job: dict[str, Path | None] = {}
    filename_prefixes = {
        "达人营销总监": "daren_marketing_candidates",
        "财务共享与管理经理": "finance_shared_management_candidates",
    }
    for job_name, job_candidates in candidate_groups.items():
        featured_candidates = _deduplicate_pdf_candidates(
            select_featured_candidates(job_candidates),
            experience_by_fingerprint=experience_by_fingerprint,
        )
        review_candidates = _deduplicate_pdf_candidates(
            select_review_candidates(job_candidates),
            experience_by_fingerprint=experience_by_fingerprint,
        )
        recollection_candidates = select_recollection_candidates(job_candidates)
        detail_candidates = [
            candidate for candidate in job_candidates
            if candidate not in featured_candidates
            and candidate not in review_candidates
            and candidate not in recollection_candidates
        ]
        output_path = output_dir / f"{filename_prefixes[job_name]}_{report_key}.pdf"
        exported_by_job[job_name] = export_featured_resume_pdf(
            output_path=output_path,
            candidates=featured_candidates,
            review_candidates=review_candidates,
            other_candidates=detail_candidates,
            recollection_candidates=recollection_candidates,
            experience_by_fingerprint=experience_by_fingerprint,
            resume_link_by_fingerprint=resume_link_by_fingerprint,
            job_name=job_name,
        )
    return exported_by_job


def _analysis_settings_for_search_keyword(
    settings: Settings,
    search_keyword: str,
    *,
    job_profile_id: str | None = None,
) -> Settings:
    analysis_version = (
        settings.coze_finance_analysis_version
        if job_profile_id == "finance_shared_management_v1" or "财务" in search_keyword
        else settings.coze_jd_analysis_version
    )
    return settings.model_copy(update={"coze_analysis_version": analysis_version})


def _load_candidate_resume_links(
    settings: Settings,
    *,
    candidate_fingerprints: set[str],
) -> dict[str, str]:
    """Load the latest saved official Liepin share link for report candidates."""
    if not candidate_fingerprints:
        return {}
    engine = get_engine(settings)
    sql = text(
        """
        SELECT locator.candidate_fingerprint, locator.detail_deep_link
        FROM candidate_resume_source_locators locator
        INNER JOIN (
            SELECT candidate_fingerprint, MAX(id) AS max_id
            FROM candidate_resume_source_locators
            WHERE locator_quality = 'share_link'
              AND detail_deep_link LIKE 'https://mr.liepin.com/resume/view-share-res%'
            GROUP BY candidate_fingerprint
        ) latest
          ON latest.max_id = locator.id
        """
    )
    with engine.begin() as connection:
        rows = connection.execute(sql).mappings().all()
    return {
        str(row["candidate_fingerprint"]): str(row["detail_deep_link"])
        for row in rows
        if str(row.get("candidate_fingerprint") or "") in candidate_fingerprints
        and str(row.get("detail_deep_link") or "").strip()
    }


def _build_daily_report_key(
    window_start_at: datetime | None,
    contacted_records: list[dict[str, object]],
) -> str:
    parts = [window_start_at.isoformat() if window_start_at is not None else "first_report"]
    for record in sorted(contacted_records, key=lambda item: str(item.get("candidate_fingerprint") or "")):
        parts.append(
            "|".join(
                (
                    str(record.get("candidate_fingerprint") or ""),
                    str(record.get("created_at") or record.get("updated_at") or ""),
                )
            )
        )
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()[:24]


def _deduplicate_pdf_candidates(
    candidates: list[ReportCandidate],
    *,
    experience_by_fingerprint: dict[str, str],
) -> list[ReportCandidate]:
    """Last-line PDF guard for legacy rows that were created before identity merge."""
    deduplicated: list[ReportCandidate] = []
    seen_experience_hashes: set[str] = set()
    duplicate_count = 0
    for candidate in candidates:
        experience = experience_by_fingerprint.get(candidate.candidate_fingerprint) or ""
        experience_hash = build_experience_hash(experience) if experience.strip() else None
        if experience_hash and experience_hash in seen_experience_hashes:
            duplicate_count += 1
            continue
        if experience_hash:
            seen_experience_hashes.add(experience_hash)
        deduplicated.append(candidate)
    if duplicate_count:
        LOGGER.warning(
            "Skipped duplicate resume entries during PDF export",
            extra={
                "event": "daily_pdf_duplicate_resume_skipped",
                "context": {"duplicate_count": duplicate_count},
            },
        )
    return deduplicated


def _backfill_pdf_experience_from_database(
    settings: Settings,
    *,
    featured_candidates: list[object],
    experience_by_fingerprint: dict[str, str],
) -> None:
    for candidate in featured_candidates:
        fingerprint = str(getattr(candidate, "candidate_fingerprint", "") or "")
        if not fingerprint or experience_by_fingerprint.get(fingerprint, "").strip():
            continue
        record = get_candidate_record_by_fingerprint(settings, fingerprint)
        if record is None:
            LOGGER.warning(
                "Featured candidate has no database resume text for PDF export",
                extra={
                    "event": "daily_pdf_experience_missing",
                    "context": {
                        "candidate_name": getattr(candidate, "candidate_name", ""),
                        "candidate_fingerprint": fingerprint,
                    },
                },
            )
            continue
        experience_by_fingerprint[fingerprint] = _optional_str(record.get("experience")) or ""


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


def _overlay_latest_hit_status(
    window_records: list[dict[str, object]],
    *,
    latest_hit_map: dict[str, dict[str, object]],
) -> dict[str, dict[str, object]]:
    """Keep the report window membership while showing each candidate's current contact status."""
    merged: dict[str, dict[str, object]] = {}
    for record in window_records:
        fingerprint = str(record.get("candidate_fingerprint") or "")
        if not fingerprint:
            continue
        current = latest_hit_map.get(fingerprint) or {}
        item = dict(record)
        if current.get("hit_status"):
            item["hit_status"] = current["hit_status"]
        merged[fingerprint] = item
    return merged


def _load_report_candidate_records(
    settings: Settings,
    *,
    limit: int | None,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> list[dict[str, object]]:
    """Load the latest hit per candidate in the report window, including collect-only hits."""
    engine = get_engine(settings)
    conditions: list[str] = []
    params: dict[str, object] = {
        "legacy_analysis_version": settings.coze_analysis_version,
        "jd_analysis_version": settings.coze_jd_analysis_version,
        "finance_analysis_version": settings.coze_finance_analysis_version,
    }
    if window_start_at is not None:
        conditions.append("created_at >= :window_start_at")
        params["window_start_at"] = _to_database_local_datetime(window_start_at)
    if window_end_at is not None:
        conditions.append("created_at < :window_end_at")
        params["window_end_at"] = _to_database_local_datetime(window_end_at)
    where_clause = "WHERE " + " AND ".join(conditions) if conditions else ""
    limit_clause = "LIMIT :row_limit" if isinstance(limit, int) and limit > 0 else ""
    if limit_clause:
        params["row_limit"] = limit
    sql = text(
        f"""
        SELECT h.candidate_fingerprint,
               h.candidate_name,
               h.search_keyword,
               h.hit_status,
               h.created_at,
               locator.job_profile_id,
               COALESCE(c.experience, r.experience) AS experience,
               COALESCE(c.match_score, r.match_score) AS match_score,
               COALESCE(c.analysis_status, r.analysis_status) AS analysis_status,
               COALESCE(c.updated_at, r.updated_at, h.created_at) AS updated_at,
               COALESCE(r.source_platform, 'liepin') AS source_platform
        FROM candidate_resume_search_hits h
        INNER JOIN (
            SELECT candidate_fingerprint, MAX(id) AS max_id
            FROM candidate_resume_search_hits
            {where_clause}
            GROUP BY candidate_fingerprint
        ) latest
          ON latest.candidate_fingerprint = h.candidate_fingerprint
         AND latest.max_id = h.id
        LEFT JOIN (
            SELECT ranked_cache.*
            FROM (
                SELECT cache.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY cache.candidate_fingerprint
                           ORDER BY
                               CASE
                                   WHEN cache.analysis_version = :jd_analysis_version THEN 3
                                   WHEN cache.analysis_version = :finance_analysis_version THEN 2
                                   ELSE 1
                               END DESC,
                               cache.id DESC
                       ) AS report_rank
                FROM candidate_resume_analysis_cache cache
                WHERE cache.analysis_version IN (
                    :jd_analysis_version,
                    :finance_analysis_version,
                    :legacy_analysis_version
                )
            ) ranked_cache
            WHERE ranked_cache.report_rank = 1
        ) c ON c.candidate_fingerprint = h.candidate_fingerprint
        LEFT JOIN (
            SELECT l.*
            FROM candidate_resume_source_locators l
            INNER JOIN (
                SELECT candidate_fingerprint, MAX(id) AS max_id
                FROM candidate_resume_source_locators
                GROUP BY candidate_fingerprint
            ) latest_locator ON latest_locator.max_id = l.id
        ) locator ON locator.candidate_fingerprint = h.candidate_fingerprint
        LEFT JOIN candidate_resume_experience_records r
          ON r.candidate_fingerprint = h.candidate_fingerprint
        ORDER BY h.created_at DESC
        {limit_clause}
        """
    )
    with engine.begin() as connection:
        return [dict(row) for row in connection.execute(sql, params).mappings().all()]


def _count_total_seen_candidates(settings: Settings) -> int:
    engine = get_engine(settings)
    sql = text(
        """
        SELECT COUNT(*) AS total_count
        FROM (
            SELECT candidate_fingerprint
            FROM candidate_resume_search_hits
            WHERE candidate_fingerprint IS NOT NULL
              AND candidate_fingerprint <> ''
            UNION
            SELECT candidate_fingerprint
            FROM candidate_resume_experience_records
            WHERE candidate_fingerprint IS NOT NULL
              AND candidate_fingerprint <> ''
        ) all_candidates
        """
    )
    with engine.begin() as connection:
        row = connection.execute(sql).mappings().first()
    return int((row or {}).get("total_count") or 0)


def _count_cumulative_report_metrics(settings: Settings) -> dict[str, int]:
    engine = get_engine(settings)
    analysis_sql = text(
        """
        SELECT
            SUM(CASE WHEN match_result = 'relevant' THEN 1 ELSE 0 END) AS relevant_count,
            SUM(CASE WHEN match_result = 'not_relevant' THEN 1 ELSE 0 END) AS not_relevant_count,
            SUM(
                CASE
                    WHEN match_result = 'needs_review'
                     AND NOT (
                         analysis_reason LIKE '%Coze API%'
                         AND (experience IS NULL OR TRIM(experience) = '')
                         AND (
                             evidence_summary_json IS NULL
                             OR TRIM(evidence_summary_json) IN ('', '[]')
                         )
                     )
                    THEN 1 ELSE 0
                END
            ) AS needs_review_count
        FROM candidate_resume_analysis_cache c
        INNER JOIN (
            SELECT candidate_fingerprint, MAX(id) AS max_id
            FROM candidate_resume_analysis_cache
            GROUP BY candidate_fingerprint
        ) latest ON latest.max_id = c.id
        """
    )
    greeted_sql = text(
        """
        SELECT COUNT(DISTINCT candidate_fingerprint) AS greeted_count
        FROM candidate_resume_search_hits
        WHERE hit_status = 'sent'
        """
    )
    with engine.begin() as connection:
        analysis = connection.execute(analysis_sql).mappings().first() or {}
        greeted = connection.execute(greeted_sql).mappings().first() or {}
    return {
        "relevant": int(analysis.get("relevant_count") or 0),
        "needs_review": int(analysis.get("needs_review_count") or 0),
        "not_relevant": int(analysis.get("not_relevant_count") or 0),
        "greeted": int(greeted.get("greeted_count") or 0),
    }


def _count_cumulative_report_metrics_by_job(
    settings: Settings,
) -> dict[str, dict[str, int]]:
    engine = get_engine(settings)
    analysis_sql = text(
        """
        WITH latest_hit AS (
            SELECT ranked_hit.*
            FROM (
                SELECT hit.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY hit.candidate_fingerprint
                           ORDER BY hit.id DESC
                       ) AS hit_rank
                FROM candidate_resume_search_hits hit
            ) ranked_hit
            WHERE ranked_hit.hit_rank = 1
        ),
        latest_locator AS (
            SELECT ranked_locator.*
            FROM (
                SELECT locator.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY locator.candidate_fingerprint
                           ORDER BY locator.id DESC
                       ) AS locator_rank
                FROM candidate_resume_source_locators locator
                WHERE locator.candidate_fingerprint IS NOT NULL
                  AND locator.candidate_fingerprint <> ''
            ) ranked_locator
            WHERE ranked_locator.locator_rank = 1
        ),
        latest_analysis AS (
            SELECT ranked_analysis.*
            FROM (
                SELECT analysis.*,
                       latest_hit.search_keyword,
                       latest_locator.job_profile_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY analysis.candidate_fingerprint
                           ORDER BY analysis.id DESC
                       ) AS analysis_rank
                FROM candidate_resume_analysis_cache analysis
                LEFT JOIN latest_hit
                  ON latest_hit.candidate_fingerprint = analysis.candidate_fingerprint
                LEFT JOIN latest_locator
                  ON latest_locator.candidate_fingerprint = analysis.candidate_fingerprint
            ) ranked_analysis
            WHERE ranked_analysis.analysis_rank = 1
        )
        SELECT
            CASE
                WHEN analysis.job_profile_id = 'finance_shared_management_v1'
                    THEN '财务共享与管理经理'
                WHEN analysis.job_profile_id = 'daren_live_bd_v1'
                    THEN '达人营销总监'
                WHEN analysis.analysis_version = :finance_version
                     OR analysis.search_keyword LIKE '%财务%'
                    THEN '财务共享与管理经理'
                ELSE '达人营销总监'
            END AS job_name,
            SUM(CASE WHEN analysis.match_result = 'relevant' THEN 1 ELSE 0 END) AS relevant_count,
            SUM(
                CASE
                    WHEN analysis.match_result = 'needs_review'
                     AND NOT (
                         analysis.analysis_reason LIKE '%Coze API%'
                         AND (analysis.experience IS NULL OR TRIM(analysis.experience) = '')
                         AND (
                             analysis.evidence_summary_json IS NULL
                             OR TRIM(analysis.evidence_summary_json) IN ('', '[]')
                         )
                     )
                    THEN 1 ELSE 0
                END
            ) AS needs_review_count
        FROM latest_analysis analysis
        GROUP BY job_name
        """
    )
    greeted_sql = text(
        """
        WITH latest_sent_hit AS (
            SELECT ranked_hit.*
            FROM (
                SELECT hit.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY hit.candidate_fingerprint
                           ORDER BY hit.id DESC
                       ) AS hit_rank
                FROM candidate_resume_search_hits hit
                WHERE hit.hit_status = 'sent'
            ) ranked_hit
            WHERE ranked_hit.hit_rank = 1
        ),
        latest_locator AS (
            SELECT ranked_locator.*
            FROM (
                SELECT locator.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY locator.candidate_fingerprint
                           ORDER BY locator.id DESC
                       ) AS locator_rank
                FROM candidate_resume_source_locators locator
                WHERE locator.candidate_fingerprint IS NOT NULL
                  AND locator.candidate_fingerprint <> ''
            ) ranked_locator
            WHERE ranked_locator.locator_rank = 1
        )
        SELECT
            CASE WHEN latest_locator.job_profile_id = 'finance_shared_management_v1'
                       OR latest_sent_hit.search_keyword LIKE '%财务%'
                 THEN '财务共享与管理经理' ELSE '达人营销总监' END AS job_name,
            COUNT(*) AS greeted_count
        FROM latest_sent_hit
        LEFT JOIN latest_locator
          ON latest_locator.candidate_fingerprint = latest_sent_hit.candidate_fingerprint
        GROUP BY job_name
        """
    )
    result = {
        "达人营销总监": {"relevant": 0, "needs_review": 0, "greeted": 0},
        "财务共享与管理经理": {"relevant": 0, "needs_review": 0, "greeted": 0},
    }
    with engine.begin() as connection:
        rows = connection.execute(
            analysis_sql,
            {
                "finance_version": settings.coze_finance_analysis_version,
            },
        ).mappings().all()
        greeted_rows = connection.execute(greeted_sql).mappings().all()
    for row in rows:
        job_name = str(row.get("job_name") or "达人营销总监")
        result[job_name] = {
            "relevant": int(row.get("relevant_count") or 0),
            "needs_review": int(row.get("needs_review_count") or 0),
            "greeted": 0,
        }
    for row in greeted_rows:
        job_name = str(row.get("job_name") or "达人营销总监")
        result[job_name]["greeted"] = int(row.get("greeted_count") or 0)
    return result


def _load_cumulative_report_window(
    settings: Settings,
) -> tuple[datetime | None, datetime | None]:
    engine = get_engine(settings)
    sql = text(
        """
        SELECT MIN(first_seen_at) AS start_at, MAX(last_seen_at) AS end_at
        FROM (
            SELECT MIN(created_at) AS first_seen_at, MAX(created_at) AS last_seen_at
            FROM candidate_resume_search_hits
            UNION ALL
            SELECT MIN(created_at) AS first_seen_at, MAX(updated_at) AS last_seen_at
            FROM candidate_resume_experience_records
            UNION ALL
            SELECT MIN(created_at) AS first_seen_at, MAX(updated_at) AS last_seen_at
            FROM candidate_resume_analysis_cache
        ) report_activity
        """
    )
    with engine.begin() as connection:
        row = connection.execute(sql).mappings().first() or {}
    return row.get("start_at"), row.get("end_at")


def _count_executed_keywords_from_log(
    settings: Settings,
    *,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> int | None:
    counts = _count_executed_keywords_by_role_from_log(
        settings,
        window_start_at=window_start_at,
        window_end_at=window_end_at,
    )
    return sum(counts.values()) if counts is not None else None


def _count_executed_keywords_by_role_from_log(
    settings: Settings,
    *,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> dict[str, int] | None:
    if window_start_at is None or window_end_at is None:
        return None
    log_path = settings.logs_dir / "app.log"
    if not log_path.exists():
        return None
    start_utc = _local_datetime_to_utc(window_start_at)
    end_utc = _local_datetime_to_utc(window_end_at)
    counts: dict[str, int] = defaultdict(int)
    log_paths = sorted(settings.logs_dir.glob("app.log*"))
    for candidate_log_path in log_paths:
        if not candidate_log_path.is_file():
            continue
        for line in candidate_log_path.read_text(encoding="utf-8", errors="replace").splitlines():
            if '"event": "keyword_run_start"' not in line:
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            timestamp = _parse_log_timestamp(payload.get("timestamp"))
            if timestamp is None:
                continue
            if start_utc <= timestamp < end_utc:
                role_keyword = _optional_str(payload.get("role_keyword")) or "未识别岗位"
                counts[role_keyword] += 1
    return dict(counts)


def _count_screened_candidate_views_by_role_from_log(
    settings: Settings,
    *,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> dict[str, int] | None:
    if window_start_at is None or window_end_at is None:
        return None
    start_utc = _local_datetime_to_utc(window_start_at)
    end_utc = _local_datetime_to_utc(window_end_at)
    events: list[tuple[datetime, dict[str, object]]] = []
    for log_path in sorted(settings.logs_dir.glob("app.log*")):
        if not log_path.is_file():
            continue
        for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
            if not any(
                marker in line
                for marker in (
                    '"event": "keyword_run_start"',
                    '"event": "result_page_iteration"',
                    '"event": "keyword_run_complete"',
                    '"event": "keyword_run_failed"',
                )
            ):
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            timestamp = _parse_log_timestamp(payload.get("timestamp"))
            if timestamp is not None and start_utc <= timestamp < end_utc:
                events.append((timestamp, payload))

    counts: dict[str, int] = defaultdict(int)
    current_role: str | None = None
    current_screened = 0

    def finish_current() -> None:
        nonlocal current_role, current_screened
        if current_role:
            counts[current_role] += current_screened
        current_role = None
        current_screened = 0

    for _, payload in sorted(events, key=lambda item: item[0]):
        event = _optional_str(payload.get("event"))
        if event == "keyword_run_start":
            finish_current()
            current_role = _optional_str(payload.get("role_keyword")) or "未识别岗位"
        elif event == "result_page_iteration" and current_role:
            screened_total = _optional_int(payload.get("screened_total")) or 0
            visible_candidates = _optional_int(payload.get("visible_candidates")) or 0
            current_screened = max(current_screened, screened_total, visible_candidates)
        elif event in {"keyword_run_complete", "keyword_run_failed"} and current_role:
            final_screened = _optional_int(payload.get("screened_candidates"))
            if final_screened is not None:
                current_screened = max(current_screened, final_screened)
            finish_current()
    finish_current()
    return dict(counts)


def _to_database_local_datetime(value: datetime) -> datetime:
    """Return a naive Asia/Shanghai value for database `created_at` comparisons."""
    if value.tzinfo is None:
        return value
    return value.astimezone(SHANGHAI_TIMEZONE).replace(tzinfo=None)


def _local_datetime_to_utc(value: datetime) -> datetime:
    """Convert the local batch window only for comparison with UTC JSON log timestamps."""
    if value.tzinfo is not None:
        return value.astimezone(timezone.utc)
    return (value - timedelta(hours=8)).replace(tzinfo=timezone.utc)


def _parse_log_timestamp(value: object) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


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
               c.updated_at,
               c.analysis_version,
               c.id
        FROM candidate_resume_analysis_cache c
        WHERE c.analysis_version IN (
            :jd_analysis_version,
            :finance_analysis_version,
            :legacy_analysis_version
        )
        ORDER BY c.candidate_fingerprint,
                 CASE
                     WHEN c.analysis_version = :jd_analysis_version THEN 3
                     WHEN c.analysis_version = :finance_analysis_version THEN 2
                     ELSE 1
                 END DESC,
                 c.id DESC
        """
    )
    with engine.begin() as connection:
        rows = connection.execute(
            sql,
            {
                "jd_analysis_version": settings.coze_jd_analysis_version,
                "finance_analysis_version": settings.coze_finance_analysis_version,
                "legacy_analysis_version": settings.coze_analysis_version,
            },
        ).mappings().all()
    result: dict[str, dict[str, object]] = {}
    for row in rows:
        fingerprint = str(row["candidate_fingerprint"])
        result.setdefault(fingerprint, dict(row))
    return result


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


def _is_transient_api_review(result: CozeMatchResult) -> bool:
    return result.match_result == "needs_review" and "Coze API" in (result.analysis_reason or "")


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
