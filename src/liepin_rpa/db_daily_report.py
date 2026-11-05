from __future__ import annotations

from liepin_rpa.analysis_execution import (
    has_analysis_execution_failure, legacy_analysis_success_sql,
)

import json
import hashlib
from collections import defaultdict
from dataclasses import asdict
from datetime import UTC, date, datetime, timedelta, timezone
from pathlib import Path
import re

from sqlalchemy import bindparam, text

from liepin_rpa.analysis_policy import current_analysis_version
from liepin_rpa.candidate_identity import (
    candidate_identity_evidence_matches,
    extract_candidate_age as extract_identity_age,
)
from liepin_rpa.candidate_decision_rules import analysis_status_for_match_result
from liepin_rpa.coze_workflow import CozeMatchResult
from liepin_rpa.config import Settings
from liepin_rpa.communication_status_policy import (
    SENT_HIT_STATUSES,
    is_sent_hit_status,
    prefer_communication_hit_status,
)
from liepin_rpa.db import (
    build_experience_hash,
    get_engine,
    start_daily_report_delivery,
    update_daily_report_delivery,
)
from liepin_rpa.feishu_report import (
    ReportCandidate,
    build_report_candidates,
    select_relevant_candidates,
    select_review_candidates,
    send_feishu_daily_report,
)
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.pdf_upload import upload_pdf_if_configured
from liepin_rpa.profile_collection.resume_schema import sanitize_candidate_name
from liepin_rpa.resume_pdf_export import build_daily_resume_pdf_path, export_featured_resume_pdf
from liepin_rpa.report_batch import (
    BATCH_STATE_FILENAME,
    NoUnreportedSearchBatchError,
    SearchBatchStateError,
    SearchBatchStateStore,
    SearchBatchWindow,
)
from liepin_rpa.report_candidate_history import (
    REPORT_CANDIDATE_HISTORY_FILENAME,
    ReportCandidateHistoryStore,
)
from liepin_rpa.hr_review_reporting import HRReviewStatus, RecruitmentProgressSnapshot
from liepin_rpa.reporting_rules import (
    ACTIONABLE_DAREN_MIN_SCORE,
    ACTIONABLE_FINANCE_MIN_SCORE,
    ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
    has_needs_review_result,
    is_actionable_report_result,
    is_analysis_result_fresh,
    is_not_relevant_result,
    is_relevant_result,
)
from liepin_rpa.search_execution.result_filter import (
    RECENT_ANALYSIS_SKIP_DAYS,
    CandidateProcessOutcome,
)
from liepin_rpa.search_execution.workflow import KeywordWorkflowResult, SearchWorkflowReport

LOGGER = get_logger(__name__)
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
    pdf_sent_only: bool = False,
) -> dict[str, object]:
    updated_from, updated_before, window_mode, resolved_batch_id = _build_report_window(
        settings,
        report_date,
        window_start_at=window_start_at,
        window_end_at=window_end_at,
        batch_id=batch_id,
    )
    candidate_batch_id = resolved_batch_id if window_mode == "completed_search_batch" else None
    contacted_records = _load_report_candidate_records(
        settings,
        limit=limit,
        window_start_at=updated_from,
        window_end_at=updated_before,
        search_batch_id=candidate_batch_id,
    )
    latest_hits = _build_window_hit_map(contacted_records)

    candidate_outcomes = _build_candidate_outcomes(
        settings,
        latest_hits=latest_hits,
        contacted_records=contacted_records,
        use_cache=use_cache,
        search_batch_id=resolved_batch_id,
        batch_started_at=updated_from,
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
    # Exact completed-batch delivery is idempotent by report_key/search_batch_id.
    # Candidate history remains an audit trail only; using it as a lifetime denylist
    # would contradict the approved seven-day re-analysis rule.
    total_candidates_seen = _count_total_seen_candidates(settings)
    cumulative_metrics = _count_cumulative_report_metrics(settings)
    cumulative_metrics_by_job = _count_cumulative_report_metrics_by_job(settings)
    recruitment_progress = load_recruitment_progress_snapshot(
        settings,
        window_start_at=updated_from,
        window_end_at=updated_before,
        search_batch_id=candidate_batch_id,
    )
    report_job_profile_ids = {
        str(record.get("job_profile_id") or "")
        for record in contacted_records
        if str(record.get("job_profile_id") or "")
    }
    cumulative_start_at, cumulative_end_at = _load_cumulative_report_window(
        settings,
        job_profile_ids=report_job_profile_ids,
    )
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
    report_key = _build_daily_report_key(
        updated_from,
        contacted_records,
        search_batch_id=candidate_batch_id,
    )
    tracked_delivery = not dry_run and advance_watermark
    if not candidate_outcomes:
        if tracked_delivery and resolved_batch_id is not None:
            _mark_reported_batch_if_registered(
                settings,
                batch_id=resolved_batch_id,
                report_key=report_key,
                delivery_status="skipped_no_new_candidates",
            )
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
            "batch_id": resolved_batch_id,
            "report_key": report_key,
            "window_start_at": updated_from.isoformat() if updated_from is not None else None,
            "window_end_at": updated_before.isoformat() if updated_before is not None else None,
            "report_date": report_date.isoformat() if report_date is not None else None,
            "dry_run": dry_run,
            "advance_watermark": advance_watermark,
            "webhook_mode": "override" if feishu_webhook_override else "default",
            "recruitment_progress": asdict(recruitment_progress),
        }
    delivery_run: dict[str, object] | None = None
    if tracked_delivery:
        delivery_run = start_daily_report_delivery(
            settings,
            report_key=report_key,
            window_start_at=updated_from,
            window_end_at=updated_before or datetime.now(),
            records_loaded=len(contacted_records),
            candidate_outcomes=len(candidate_outcomes),
            search_batch_id=candidate_batch_id,
        )
        if delivery_run.get("delivery_status") == "sent":
            if resolved_batch_id is not None:
                _mark_reported_batch_if_registered(
                    settings,
                    batch_id=resolved_batch_id,
                    report_key=report_key,
                    delivery_status="already_sent",
                )
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
                "batch_id": resolved_batch_id,
                "window_start_at": updated_from.isoformat() if updated_from is not None else None,
                "window_end_at": updated_before.isoformat() if updated_before is not None else None,
                "dry_run": dry_run,
                "advance_watermark": advance_watermark,
                "recruitment_progress": asdict(recruitment_progress),
            }

    detail_pdf_paths_by_job = (
        {}
        if dry_run
        else _export_candidate_pdfs_by_job(
            settings,
            candidate_outcomes=candidate_outcomes,
            contacted_records=contacted_records,
            report_key=report_key,
            sent_only=pdf_sent_only,
        )
    )
    upload_results_by_job = {
        job_name: upload_pdf_if_configured(settings, pdf_path)
        for job_name, pdf_path in detail_pdf_paths_by_job.items()
    } if not dry_run else {}
    detail_pdf_urls_by_job = {
        job_name: (
            _version_pdf_public_url(
                upload_results_by_job[job_name].public_url,
                detail_pdf_paths_by_job.get(job_name),
            )
            if job_name in upload_results_by_job
            else None
        )
        for job_name in detail_pdf_paths_by_job
    }
    primary_job_name = next(
        (job_name for job_name, path in detail_pdf_paths_by_job.items() if path is not None),
        "达人营销总监",
    )
    detail_pdf_path = detail_pdf_paths_by_job.get(primary_job_name)
    upload_result = upload_results_by_job.get(primary_job_name)
    detail_pdf_url = detail_pdf_urls_by_job.get(primary_job_name)
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
        recruitment_progress=recruitment_progress,
        search_batch_id=candidate_batch_id,
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
        displayed_fingerprints = _select_actionable_candidate_fingerprints(candidate_outcomes)
        candidate_history.record_delivery(
            report_key=report_key,
            candidate_fingerprints=displayed_fingerprints,
            window_start_at=updated_from,
            window_end_at=updated_before,
        )
        if resolved_batch_id is not None:
            _mark_reported_batch_if_registered(
                settings,
                batch_id=resolved_batch_id,
                report_key=report_key,
                delivery_status="sent",
            )
    return {
        "records_loaded": len(contacted_records),
        "candidate_outcomes": len(candidate_outcomes),
        "previously_reported_candidates_excluded": 0,
        "previously_reported_candidates_observed": previously_reported_count,
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
        "batch_id": resolved_batch_id,
        "window_start_at": updated_from.isoformat() if updated_from is not None else None,
        "window_end_at": updated_before.isoformat() if updated_before is not None else None,
        "report_date": report_date.isoformat() if report_date is not None else None,
        "dry_run": dry_run,
        "advance_watermark": advance_watermark,
        "webhook_mode": "override" if feishu_webhook_override else "default",
        "recruitment_progress": asdict(recruitment_progress),
    }


def previous_local_date() -> date:
    return date.today() - timedelta(days=1)


def _mark_reported_batch_if_registered(
    settings: Settings,
    *,
    batch_id: str,
    report_key: str,
    delivery_status: str,
) -> None:
    """Update the dual-job registry when present; standalone runs are DB-tracked."""

    try:
        SearchBatchStateStore(
            settings.artifacts_dir / BATCH_STATE_FILENAME
        ).mark_reported(
            batch_id=batch_id,
            report_key=report_key,
            delivery_status=delivery_status,
        )
    except SearchBatchStateError:
        if not batch_id.startswith("standalone-"):
            raise
        LOGGER.info(
            "Standalone report delivery is tracked in the database only",
            extra={
                "event": "standalone_report_registry_skip",
                "context": {"batch_id": batch_id, "delivery_status": delivery_status},
            },
        )


def _legacy_current_analysis_ctes(*, locator_where: str = "") -> str:
    """Build the read-only current-resume/current-job/current-policy selector."""

    scope_suffix = f" AND {locator_where}" if locator_where else ""
    return f"""
        scoped_candidate_jobs AS (
            SELECT l.candidate_fingerprint,
                   l.job_profile_id,
                   MAX(l.id) AS locator_id
            FROM candidate_resume_source_locators l
            WHERE l.candidate_fingerprint IS NOT NULL
              AND l.candidate_fingerprint <> ''
              AND l.job_profile_id IS NOT NULL
              {scope_suffix}
            GROUP BY l.candidate_fingerprint, l.job_profile_id
        ),
        ranked_resume_locators AS (
            SELECT l.candidate_fingerprint,
                   l.experience_hash,
                   ROW_NUMBER() OVER (
                       PARTITION BY l.candidate_fingerprint
                       ORDER BY l.created_at DESC, l.id DESC
                   ) AS resume_rank
            FROM candidate_resume_source_locators l
            WHERE l.experience_hash IS NOT NULL
              AND TRIM(l.experience_hash) <> ''
        ),
        latest_resume_locators AS (
            SELECT candidate_fingerprint, experience_hash
            FROM ranked_resume_locators
            WHERE resume_rank = 1
        ),
        current_candidate_jobs AS (
            SELECT scoped.candidate_fingerprint,
                   scoped.job_profile_id,
                   scoped.locator_id,
                   COALESCE(NULLIF(record.experience_hash, ''), locator.experience_hash)
                       AS current_experience_hash
            FROM scoped_candidate_jobs scoped
            LEFT JOIN candidate_resume_experience_records record
              ON record.candidate_fingerprint = scoped.candidate_fingerprint
            LEFT JOIN latest_resume_locators locator
              ON locator.candidate_fingerprint = scoped.candidate_fingerprint
        ),
        ranked_current_analysis AS (
            SELECT analysis.*,
                   current_job.job_profile_id,
                   current_job.locator_id,
                   ROW_NUMBER() OVER (
                       PARTITION BY analysis.candidate_fingerprint,
                                    current_job.job_profile_id
                       ORDER BY analysis.created_at DESC, analysis.id DESC
                   ) AS analysis_rank
            FROM current_candidate_jobs current_job
            INNER JOIN candidate_resume_analysis_cache analysis
              ON analysis.candidate_fingerprint = current_job.candidate_fingerprint
             AND analysis.experience_hash = current_job.current_experience_hash
             AND analysis.analysis_version = CASE
                    WHEN current_job.job_profile_id = 'finance_shared_management_v1'
                        THEN :finance_analysis_version
                    WHEN current_job.job_profile_id = 'daren_live_bd_v1'
                        THEN :jd_analysis_version
                    WHEN current_job.job_profile_id = 'fde_ai_deployment_engineer_v1'
                        THEN :fde_analysis_version
                    WHEN current_job.job_profile_id = 'ai_development_engineer_v1'
                        THEN :ai_development_analysis_version
                    ELSE '__no_active_analysis_policy__'
                 END
             AND analysis.match_result IS NOT NULL
             AND analysis.match_score IS NOT NULL
        ),
        current_analysis AS (
            SELECT *
            FROM ranked_current_analysis
            WHERE analysis_rank = 1
              AND {legacy_analysis_success_sql("ranked_current_analysis.analysis_reason", "ranked_current_analysis.execution_status")}
        )
    """


def _active_analysis_params(settings: Settings) -> dict[str, object]:
    jd_version, finance_version, fde_version, legacy_version = _active_report_analysis_versions(settings)
    return {
        "jd_analysis_version": jd_version,
        "finance_analysis_version": finance_version,
        "fde_analysis_version": fde_version,
        "ai_development_analysis_version": str(
            getattr(settings, "llm_ai_development_analysis_version", legacy_version)
        ),
    }


def _active_report_analysis_versions(settings: Settings) -> tuple[str, str, str, str]:
    """Return active job policies while retaining legacy-test compatibility."""

    legacy_version = str(getattr(settings, "coze_analysis_version", "legacy"))
    provider = str(getattr(settings, "resume_analysis_provider", "coze")).strip().lower()
    if provider == "openai_compatible":
        jd_version = str(
            getattr(
                settings,
                "llm_jd_analysis_version",
                getattr(settings, "coze_jd_analysis_version", legacy_version),
            )
        )
        finance_version = str(
            getattr(
                settings,
                "llm_finance_analysis_version",
                getattr(settings, "coze_finance_analysis_version", legacy_version),
            )
        )
    else:
        jd_version = str(getattr(settings, "coze_jd_analysis_version", legacy_version))
        finance_version = str(
            getattr(settings, "coze_finance_analysis_version", legacy_version)
        )
    fde_version = str(
        getattr(settings, "llm_fde_analysis_version", legacy_version)
    )
    return jd_version, finance_version, fde_version, legacy_version


def load_recruitment_progress_snapshot(
    settings: Settings,
    *,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
    search_batch_id: str | None = None,
) -> RecruitmentProgressSnapshot:
    """Read AI facts and HR progress for one batch or an explicit legacy window."""

    engine = get_engine(settings)
    locator_conditions: list[str] = []
    event_conditions: list[str] = []
    params: dict[str, object] = {}
    if search_batch_id is not None:
        params["search_batch_id"] = search_batch_id
        locator_conditions.append("l.search_batch_id = :search_batch_id")
    elif window_start_at is not None:
        params["window_start_at"] = _to_database_local_datetime(window_start_at)
        locator_conditions.append("l.created_at >= :window_start_at")
        event_conditions.append("e.created_at >= :window_start_at")
    if search_batch_id is None and window_end_at is not None:
        params["window_end_at"] = _to_database_local_datetime(window_end_at)
        locator_conditions.append("l.created_at < :window_end_at")
        event_conditions.append("e.created_at < :window_end_at")
    locator_scope = " AND ".join(locator_conditions)
    event_where = " AND " + " AND ".join(event_conditions) if event_conditions else ""
    params.update(_active_analysis_params(settings))
    rows_sql = text(
        f"""
        WITH {_legacy_current_analysis_ctes(locator_where=locator_scope)}
        SELECT scoped.candidate_fingerprint,
               scoped.job_profile_id,
               r.manual_review_status,
               r.manual_suppress_report,
               c.analysis_status,
               c.match_result,
               c.match_score,
               c.analysis_reason,
               c.execution_status, c.error_code, c.retryable, c.retry_after,
               c.analysis_run_id, c.analyzed_text_hash,
               c.created_at AS analyzed_at
        FROM scoped_candidate_jobs scoped
        LEFT JOIN candidate_resume_experience_records r
          ON r.candidate_fingerprint = scoped.candidate_fingerprint
        LEFT JOIN current_analysis c
          ON c.candidate_fingerprint = scoped.candidate_fingerprint
         AND c.job_profile_id = scoped.job_profile_id
        """
    )
    if search_batch_id is not None:
        feedback_sql = text(
            f"""
            SELECT COUNT(DISTINCT e.candidate_fingerprint) AS feedback_count
            FROM candidate_resume_manual_review_events e
            INNER JOIN (
                SELECT DISTINCT l.candidate_fingerprint
                FROM candidate_resume_source_locators l
                {"WHERE " + " AND ".join(locator_conditions) if locator_conditions else ""}
            ) batch_candidates
              ON batch_candidates.candidate_fingerprint = e.candidate_fingerprint
            """
        )
    else:
        feedback_sql = text(
            f"""
            SELECT COUNT(DISTINCT e.candidate_fingerprint) AS feedback_count
            FROM candidate_resume_manual_review_events e
            WHERE 1 = 1
              {event_where}
            """
        )
    with engine.begin() as connection:
        rows = connection.execute(rows_sql, params).mappings().all()
        feedback_row = connection.execute(feedback_sql, params).mappings().first()

    hr_status_counts = {status.value: 0 for status in HRReviewStatus}
    ai_relevant = 0
    ai_needs_review = 0
    ai_not_relevant = 0
    actionable_pending = 0
    for row in rows:
        match_result = row.get("match_result")
        analysis_status = row.get("analysis_status")
        match_score = row.get("match_score")
        analysis_reason = row.get("analysis_reason")
        analysis_fresh = is_analysis_result_fresh(row.get("analyzed_at"))
        if is_relevant_result(match_result, analysis_status=analysis_status):
            ai_relevant += 1
        elif has_needs_review_result(match_result, analysis_status=analysis_status):
            ai_needs_review += 1
        elif is_not_relevant_result(match_result, analysis_status=analysis_status):
            ai_not_relevant += 1

        raw_status = str(row.get("manual_review_status") or "").strip()
        formal_status = raw_status if raw_status in hr_status_counts else None
        if formal_status and formal_status != HRReviewStatus.PENDING_REVIEW.value:
            hr_status_counts[formal_status] += 1
            continue
        if bool(row.get("manual_suppress_report")):
            continue
        if analysis_fresh and is_actionable_report_result(
            match_result,
            match_score,
            analysis_status=analysis_status,
            analysis_reason=analysis_reason,
            execution_status=row.get("execution_status"),
        ):
            actionable_pending += 1
            hr_status_counts[HRReviewStatus.PENDING_REVIEW.value] += 1

    feedback_count = (
        _optional_int(feedback_row["feedback_count"]) if feedback_row is not None else None
    )
    return RecruitmentProgressSnapshot(
        ai_relevant=ai_relevant,
        ai_needs_review=ai_needs_review,
        ai_not_relevant=ai_not_relevant,
        actionable_pending=actionable_pending,
        hr_status_counts=hr_status_counts,
        feedback_candidates_in_window=feedback_count or 0,
    )


def _version_pdf_public_url(public_url: str | None, pdf_path: Path | None) -> str | None:
    """Use the PDF content digest as a cache key for Feishu's embedded preview."""

    if not public_url or pdf_path is None or not pdf_path.exists():
        return public_url
    digest = hashlib.sha256(pdf_path.read_bytes()).hexdigest()[:12]
    separator = "&" if "?" in public_url else "?"
    return f"{public_url}{separator}v={digest}"


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
) -> tuple[datetime | None, datetime | None, str, str | None]:
    if batch_id is not None and (
        report_date is not None or window_start_at is not None or window_end_at is not None
    ):
        raise ValueError("--batch-id cannot be combined with a date or custom report window.")
    if report_date is not None and (window_start_at is not None or window_end_at is not None):
        raise ValueError("--report-date cannot be used with a custom report window.")
    if window_start_at is not None or window_end_at is not None:
        return window_start_at, window_end_at or datetime.now(), "custom_window", None
    if report_date is not None:
        start_at, end_at = _build_report_date_window(report_date)
        return start_at, end_at, "fixed_date", None
    try:
        batch_window = SearchBatchStateStore(
            settings.artifacts_dir / BATCH_STATE_FILENAME
        ).latest_unreported_completed_window(batch_id=batch_id)
    except NoUnreportedSearchBatchError:
        if not batch_id or not batch_id.startswith("standalone-"):
            raise
        batch_window = _load_standalone_batch_window(settings, batch_id=batch_id)
    return (
        batch_window.started_at,
        batch_window.ended_at,
        "completed_search_batch",
        batch_window.batch_id,
    )


def _load_standalone_batch_window(
    settings: Settings,
    *,
    batch_id: str,
) -> SearchBatchWindow:
    """Resolve one completed standalone job from its immutable database batch id."""

    sql = text(
        """
        SELECT MIN(created_at) AS started_at, MAX(created_at) AS ended_at
        FROM candidate_resume_source_locators
        WHERE search_batch_id = :batch_id
        """
    )
    with get_engine(settings).connect() as connection:
        row = connection.execute(sql, {"batch_id": batch_id}).mappings().first() or {}
    started_at = row.get("started_at")
    ended_at = row.get("ended_at")
    if not isinstance(started_at, datetime) or not isinstance(ended_at, datetime):
        raise NoUnreportedSearchBatchError(
            f"Standalone search batch `{batch_id}` was not found in the database."
        )
    return SearchBatchWindow(
        batch_id=batch_id,
        started_at=started_at,
        ended_at=ended_at + timedelta(seconds=1),
    )


def _build_candidate_outcomes(
    settings: Settings,
    *,
    latest_hits: dict[str, dict[str, object]],
    contacted_records: list[dict[str, object]],
    use_cache: bool,
    search_batch_id: str | None = None,
    batch_started_at: datetime | None = None,
) -> list[CandidateProcessOutcome]:
    contacted_by_fingerprint = {
        str(item.get("candidate_fingerprint") or ""): item
        for item in contacted_records
        if str(item.get("candidate_fingerprint") or "")
    }
    # Daily reports must be scoped to candidates contacted in the current report window.
    # Search-hit/cache tables are only metadata helpers; including their full history here
    # would resend old candidates when no new records were loaded.
    all_fingerprints = set(contacted_by_fingerprint.keys())
    prior_analysis_fingerprints: set[str] = set()
    prior_identity_evidence: list[dict[str, object]] = []
    if search_batch_id and batch_started_at is not None:
        prior_analysis_fingerprints, prior_identity_evidence = (
            _load_prior_analysis_identity_scope(
                settings,
                analyzed_from=batch_started_at - timedelta(days=RECENT_ANALYSIS_SKIP_DAYS),
                analyzed_before=batch_started_at,
            )
        )
    outcomes: list[CandidateProcessOutcome] = []
    for candidate_fingerprint in all_fingerprints:
        record = contacted_by_fingerprint.get(candidate_fingerprint)
        current_hit_status = _optional_str((record or {}).get("hit_status")) or ""
        if current_hit_status == "skipped_recent_analysis":
            continue
        if (
            search_batch_id
            and batch_started_at is not None
            and (
                candidate_fingerprint in prior_analysis_fingerprints
                or _matches_prior_analysis_identity_alias(
                    candidate_fingerprint=candidate_fingerprint,
                    record=record,
                    prior_identity_evidence=prior_identity_evidence,
                )
            )
            # A prior analysis should suppress an untouched/reviewed duplicate,
            # but it must not hide a communication result produced by the
            # current batch. Otherwise a current successful send is silently
            # removed from the daily greeting count.
            and current_hit_status not in SENT_HIT_STATUSES
            and not current_hit_status.startswith("send_failed:")
            and current_hit_status
            not in {"continue_without_db_record", "skipped_recent_continue"}
        ):
            continue
        latest_hit = latest_hits.get(candidate_fingerprint, {})
        outcome = _build_candidate_outcome(
            settings,
            candidate_fingerprint=candidate_fingerprint,
            record=record,
            latest_hit=latest_hit,
            use_cache=use_cache,
        )
        if outcome is not None and not _is_coze_api_failure_outcome(outcome):
            outcomes.append(outcome)
    return outcomes


def _load_prior_analysis_identity_scope(
    settings: Settings,
    *,
    analyzed_from: datetime,
    analyzed_before: datetime,
) -> tuple[set[str], list[dict[str, object]]]:
    """Load the exact seven-day identity scope used by the HR portal."""

    sql = text(
        f"""
        SELECT a.candidate_fingerprint,
               a.experience,
               l.candidate_name,
               l.candidate_card_text
        FROM candidate_resume_analysis_cache a
        LEFT JOIN candidate_resume_source_locators l
          ON l.candidate_fingerprint = a.candidate_fingerprint
        WHERE a.match_score IS NOT NULL
          AND a.match_result IN ('relevant', 'needs_review', 'not_relevant')
          AND {legacy_analysis_success_sql("a.analysis_reason", "a.execution_status")}
          AND a.created_at >= :analyzed_from
          AND a.created_at < :analyzed_before
        """
    )
    engine = get_engine(settings)
    with engine.connect() as connection:
        rows = [
            dict(row)
            for row in connection.execute(
                sql,
                {
                    "analyzed_from": _to_database_local_datetime(analyzed_from),
                    "analyzed_before": _to_database_local_datetime(analyzed_before),
                },
            ).mappings()
        ]
    return (
        {
            str(row.get("candidate_fingerprint") or "")
            for row in rows
            if str(row.get("candidate_fingerprint") or "")
        },
        [
            row
            for row in rows
            if _optional_str(row.get("candidate_name"))
            and _optional_str(row.get("candidate_card_text"))
            and _optional_str(row.get("experience"))
        ],
    )


def _matches_prior_analysis_identity_alias(
    *,
    candidate_fingerprint: str,
    record: dict[str, object] | None,
    prior_identity_evidence: list[dict[str, object]],
) -> bool:
    if record is None:
        return False
    return any(
        _optional_str(prior.get("candidate_fingerprint")) != candidate_fingerprint
        and candidate_identity_evidence_matches(
            first_name=prior.get("candidate_name"),
            first_age=extract_identity_age(_optional_str(prior.get("experience")) or ""),
            first_card_text=prior.get("candidate_card_text"),
            first_experience=prior.get("experience"),
            second_name=record.get("candidate_name"),
            second_age=extract_identity_age(
                _optional_str(record.get("candidate_card_text")) or ""
            ),
            second_card_text=record.get("candidate_card_text"),
            second_experience=record.get("experience"),
        )
        for prior in prior_identity_evidence
    )


def _build_candidate_outcome(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    record: dict[str, object] | None,
    latest_hit: dict[str, object],
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
    # A missing hit fact is not proof that a message was sent.  Older report
    # code inferred ``sent`` merely because a resume record existed, which
    # could inflate the greeting count when the communication attempt failed
    # or its hit row was unavailable.
    hit_status = _optional_str(latest_hit.get("hit_status")) or "reviewed"
    brand_keyword, role_keyword = _split_search_keyword(search_keyword)
    job_profile_id = _optional_str((record or {}).get("job_profile_id"))
    if job_profile_id == "finance_shared_management_v1":
        brand_keyword = ""
        role_keyword = "财务共享与管理经理"
    elif job_profile_id == "fde_ai_deployment_engineer_v1":
        brand_keyword = ""
        role_keyword = "AI前沿部署工程师（FDE）"
    elif job_profile_id == "ai_development_engineer_v1":
        brand_keyword = ""
        role_keyword = "AI开发工程师"
    elif job_profile_id == "daren_live_bd_v1" and not role_keyword:
        role_keyword = "达人营销总监"
    coze_result: CozeMatchResult | None = None
    normalized_analysis_status = _optional_str((record or {}).get("analysis_status")) or "未分析"
    experience = _optional_str((record or {}).get("experience")) or ""

    if record is not None and experience:
        # Reports consume persisted facts only. They must never invoke AI, update a
        # cache timestamp, or fall back to a score for an older resume/job policy.
        coze_result = _coze_result_from_cache_record(record)
        if coze_result is not None and not is_analysis_result_fresh(
            record.get("analyzed_at")
        ):
            coze_result = None
        if coze_result is not None:
            normalized_analysis_status = analysis_status_for_match_result(
                coze_result.match_result
            )
    # Without a current complete resume, the candidate remains unscored. Never
    # revive a globally latest result from a historical resume.

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
        execution_status=coze_result.execution_status if coze_result is not None else None,
        evidence_summary=list(coze_result.evidence_summary) if coze_result is not None else [],
        hit_status=hit_status,
        continue_without_db_record=False,
        message_send_success=is_sent_hit_status(hit_status),
        message_send_reason=hit_status,
        message_send_attempts=1 if is_sent_hit_status(hit_status) else 0,
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
                    is_relevant_result(
                        item.match_result,
                        analysis_status=item.analysis_status,
                    )
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
    sent_only: bool = False,
) -> dict[str, Path | None]:
    all_report_candidates = build_report_candidates(candidate_outcomes)
    jobs_in_scope = {
        _job_name_for_role_keyword(role)
        for candidate in all_report_candidates
        for role in (candidate.role_keywords or {""})
    }
    if jobs_in_scope & {"达人营销总监", "财务共享与管理经理"}:
        jobs_in_scope.update({"达人营销总监", "财务共享与管理经理"})
    # PDF uses the same actionable rule as the collector and portal.
    report_candidates = (
        select_relevant_candidates(all_report_candidates)
        + select_review_candidates(all_report_candidates)
    )
    if sent_only:
        report_candidates = [
            candidate
            for candidate in report_candidates
            if is_sent_hit_status(candidate.hit_status)
        ]
    all_candidate_groups = {
        "达人营销总监": [
            candidate for candidate in report_candidates
            if any("达人营销" in role for role in candidate.role_keywords)
        ],
        "财务共享与管理经理": [
            candidate for candidate in report_candidates
            if any("财务" in role for role in candidate.role_keywords)
        ],
        "AI前沿部署工程师（FDE）": [
            candidate for candidate in report_candidates
            if any("FDE" in role or "AI前沿部署" in role for role in candidate.role_keywords)
        ],
        "AI开发工程师": [
            candidate for candidate in report_candidates
            if any("AI开发" in role for role in candidate.role_keywords)
        ],
    }
    candidate_groups = {
        job_name: candidates
        for job_name, candidates in all_candidate_groups.items()
        if job_name in jobs_in_scope
    }
    if sent_only:
        cumulative_review_groups, cumulative_review_experience = {}, {}
    else:
        cumulative_review_groups, cumulative_review_experience = (
            _load_cumulative_review_candidates_by_job(settings)
        )
    experience_by_fingerprint = {
        str(item.get("candidate_fingerprint") or ""): _optional_str(item.get("experience")) or ""
        for item in contacted_records
        if str(item.get("candidate_fingerprint") or "")
    }
    for fingerprint, experience in cumulative_review_experience.items():
        experience_by_fingerprint.setdefault(fingerprint, experience)
    current_fingerprints_by_job = {
        job_name: {candidate.candidate_fingerprint for candidate in candidates}
        for job_name, candidates in candidate_groups.items()
    }
    historical_review_groups = {
        job_name: [
            candidate
            for candidate in cumulative_review_groups.get(job_name, [])
            if candidate.candidate_fingerprint not in current_fingerprints_by_job[job_name]
        ]
        for job_name in candidate_groups
    }
    all_candidates = [candidate for items in candidate_groups.values() for candidate in items]
    all_candidates.extend(
        candidate
        for items in historical_review_groups.values()
        for candidate in items
    )
    # Candidate records were already restricted to rows with a current complete
    # resume. Avoid a fallback query that could initialize schema on a read path.
    resume_link_by_fingerprint = _load_candidate_resume_links(
        settings,
        candidate_fingerprints={
            candidate.candidate_fingerprint
            for candidate in all_candidates
        },
    )
    interaction_note_by_fingerprint = _load_candidate_interaction_notes(
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
        "AI前沿部署工程师（FDE）": "fde_ai_deployment_engineer_candidates",
        "AI开发工程师": "ai_development_engineer_candidates",
    }
    for job_name, job_candidates in candidate_groups.items():
        relevant_candidates = _deduplicate_pdf_candidates(
            select_relevant_candidates(job_candidates),
            experience_by_fingerprint=experience_by_fingerprint,
        )
        review_candidates = _deduplicate_pdf_candidates(
            select_review_candidates(job_candidates),
            experience_by_fingerprint=experience_by_fingerprint,
        )
        cumulative_review_candidates = _deduplicate_pdf_candidates(
            historical_review_groups[job_name],
            experience_by_fingerprint=experience_by_fingerprint,
        )
        output_path = output_dir / f"{filename_prefixes[job_name]}_{report_key}.pdf"
        exported_by_job[job_name] = export_featured_resume_pdf(
            output_path=output_path,
            candidates=relevant_candidates,
            review_candidates=review_candidates,
            cumulative_review_candidates=cumulative_review_candidates,
            experience_by_fingerprint=experience_by_fingerprint,
            resume_link_by_fingerprint=resume_link_by_fingerprint,
            interaction_note_by_fingerprint=interaction_note_by_fingerprint,
            job_name=job_name,
        )
    return exported_by_job


def _job_name_for_role_keyword(role_keyword: object) -> str:
    normalized = str(role_keyword or "")
    if "FDE" in normalized or "AI前沿部署" in normalized:
        return "AI前沿部署工程师（FDE）"
    if "AI开发工程师" in normalized or "AI开发" in normalized:
        return "AI开发工程师"
    if "财务" in normalized:
        return "财务共享与管理经理"
    return "达人营销总监"


def _load_cumulative_review_candidates_by_job(
    settings: Settings,
) -> tuple[dict[str, list[ReportCandidate]], dict[str, str]]:
    """Load the same latest high-score review population used by cumulative metrics."""

    engine = get_engine(settings)
    sql = text(
        f"""
        WITH {_legacy_current_analysis_ctes()},
        latest_hit AS (
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
        )
        SELECT analysis.*,
               COALESCE(
                   analysis.hit_candidate_name,
                   record.candidate_name,
                   '未命名候选人'
               ) AS candidate_name,
               COALESCE(analysis.experience, record.experience, '') AS resume_experience,
               CASE
                   WHEN analysis.job_profile_id = 'finance_shared_management_v1'
                       THEN '财务共享与管理经理'
                   WHEN analysis.job_profile_id = 'fde_ai_deployment_engineer_v1'
                       THEN 'AI前沿部署工程师（FDE）'
                   WHEN analysis.job_profile_id = 'ai_development_engineer_v1'
                       THEN 'AI开发工程师'
                   ELSE '达人营销总监'
               END AS job_name
        FROM (
            SELECT current_analysis.*,
                   latest_hit.candidate_name AS hit_candidate_name,
                   latest_hit.search_keyword,
                   latest_hit.hit_status
            FROM current_analysis
            LEFT JOIN latest_hit
              ON latest_hit.candidate_fingerprint = current_analysis.candidate_fingerprint
        ) analysis
        LEFT JOIN candidate_resume_experience_records record
          ON record.candidate_fingerprint = analysis.candidate_fingerprint
        WHERE analysis.match_result = 'needs_review'
          AND {legacy_analysis_success_sql("analysis.analysis_reason", "analysis.execution_status")}
          AND COALESCE(analysis.match_score, 0) >= :needs_review_min_score
          AND COALESCE(record.manual_suppress_report, 0) = 0
        ORDER BY job_name, analysis.match_score DESC, candidate_name
        """
    )
    with engine.begin() as connection:
        rows = (
            connection.execute(
                sql,
                {
                    **_active_analysis_params(settings),
                    "needs_review_min_score": ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
                },
            )
            .mappings()
            .all()
        )

    groups: dict[str, list[ReportCandidate]] = {
        "达人营销总监": [],
        "财务共享与管理经理": [],
        "AI前沿部署工程师（FDE）": [],
    }
    experience_by_fingerprint: dict[str, str] = {}
    for row in rows:
        fingerprint = str(row.get("candidate_fingerprint") or "")
        if not fingerprint:
            continue
        job_name = str(row.get("job_name") or "达人营销总监")
        experience = _optional_str(row.get("resume_experience")) or ""
        evidence_summary = _parse_string_list(row.get("evidence_summary_json"))
        search_keyword = _optional_str(row.get("search_keyword")) or job_name
        candidate_name = sanitize_candidate_name(row.get("candidate_name")) or "未命名候选人"
        groups.setdefault(job_name, []).append(
            ReportCandidate(
                candidate_fingerprint=fingerprint,
                candidate_name=candidate_name,
                search_keywords={search_keyword},
                role_keywords={job_name},
                analysis_status=_optional_str(row.get("analysis_status")) or "待复核",
                match_result="needs_review",
                match_score=_optional_int(row.get("match_score")),
                score_breakdown=_parse_score_breakdown(row.get("score_breakdown_json")),
                analysis_reason=_optional_str(row.get("analysis_reason")),
                execution_status=_optional_str(row.get("execution_status")),
                evidence_summary=evidence_summary,
                hit_status=_optional_str(row.get("hit_status")) or "reviewed",
                recent_experience_summary=_extract_recent_experience_summary(experience),
                matched_experience_summary=_extract_matched_experience_summary(
                    evidence_summary=evidence_summary
                ),
                candidate_age=_extract_candidate_age(experience),
            )
        )
        experience_by_fingerprint[fingerprint] = experience
    return groups, experience_by_fingerprint


def _analysis_settings_for_search_keyword(
    settings: Settings,
    search_keyword: str,
    *,
    job_profile_id: str | None = None,
) -> Settings:
    resolved_job = job_profile_id or (
        "finance_shared_management_v1" if "财务" in search_keyword else "daren_live_bd_v1"
    )
    return settings.model_copy(
        update={"coze_analysis_version": current_analysis_version(settings, resolved_job)}
    )


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


def _select_actionable_candidate_fingerprints(
    candidate_outcomes: list[CandidateProcessOutcome],
) -> set[str]:
    report_candidates = build_report_candidates(candidate_outcomes)
    return {
        candidate.candidate_fingerprint
        for candidate in (
            select_relevant_candidates(report_candidates)
            + select_review_candidates(report_candidates)
        )
    }


def _load_candidate_interaction_notes(
    settings: Settings,
    *,
    candidate_fingerprints: set[str],
) -> dict[str, str]:
    """Load the latest platform interaction hint for each report candidate."""

    if not candidate_fingerprints:
        return {}
    engine = get_engine(settings)
    sql = text(
        """
        SELECT locator.candidate_fingerprint, locator.platform_interaction_note
        FROM candidate_resume_source_locators locator
        INNER JOIN (
            SELECT candidate_fingerprint, MAX(id) AS max_id
            FROM candidate_resume_source_locators
            WHERE platform_interaction_note IS NOT NULL
              AND platform_interaction_note <> ''
            GROUP BY candidate_fingerprint
        ) latest
          ON latest.max_id = locator.id
        """
    )
    with engine.begin() as connection:
        rows = connection.execute(sql).mappings().all()
    return {
        str(row["candidate_fingerprint"]): str(row["platform_interaction_note"])
        for row in rows
        if str(row.get("candidate_fingerprint") or "") in candidate_fingerprints
        and str(row.get("platform_interaction_note") or "").strip()
    }


def _build_daily_report_key(
    window_start_at: datetime | None,
    contacted_records: list[dict[str, object]],
    *,
    search_batch_id: str | None = None,
) -> str:
    parts = [
        f"batch:{search_batch_id}"
        if search_batch_id
        else (window_start_at.isoformat() if window_start_at is not None else "first_report")
    ]
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
    """Deprecated read-path hook kept for test and caller compatibility.

    Report candidates are now loaded only when their current complete resume is
    present, so no schema-initializing fallback query is permitted here.
    """


def _load_latest_hit_map(settings: Settings) -> dict[str, dict[str, object]]:
    engine = get_engine(settings)
    sql = text(
        """
        SELECT candidate_fingerprint, candidate_name, search_keyword,
               hit_status, created_at
        FROM candidate_resume_search_hits
        ORDER BY candidate_fingerprint, id
        """
    )
    with engine.begin() as connection:
        rows = connection.execute(sql).mappings().all()
    merged: dict[str, dict[str, object]] = {}
    for row in rows:
        fingerprint = str(row["candidate_fingerprint"])
        item = dict(row)
        previous = merged.get(fingerprint)
        if previous is not None:
            item["hit_status"] = prefer_communication_hit_status(
                previous.get("hit_status"), item.get("hit_status")
            )
        merged[fingerprint] = item
    return merged


def _overlay_latest_hit_status(
    window_records: list[dict[str, object]],
    *,
    latest_hit_map: dict[str, dict[str, object]],
) -> dict[str, dict[str, object]]:
    """Keep report membership fixed while preserving later verified communication facts."""

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


def _build_window_hit_map(
    window_records: list[dict[str, object]],
) -> dict[str, dict[str, object]]:
    """Keep both report membership and communication status inside one batch."""

    return {
        str(record["candidate_fingerprint"]): dict(record)
        for record in window_records
        if str(record.get("candidate_fingerprint") or "")
    }


def _load_report_candidate_records(
    settings: Settings,
    *,
    limit: int | None,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
    search_batch_id: str | None = None,
) -> list[dict[str, object]]:
    """Load current candidate/job analysis facts for one immutable report scope."""
    engine = get_engine(settings)
    hit_conditions: list[str] = []
    locator_conditions: list[str] = []
    params = _active_analysis_params(settings)
    if search_batch_id is not None:
        hit_conditions.append("h.search_batch_id = :search_batch_id")
        locator_conditions.append("l.search_batch_id = :search_batch_id")
        params["search_batch_id"] = search_batch_id
    elif window_start_at is not None:
        hit_conditions.append("h.created_at >= :window_start_at")
        locator_conditions.append("l.created_at >= :window_start_at")
        params["window_start_at"] = _to_database_local_datetime(window_start_at)
    if search_batch_id is None and window_end_at is not None:
        hit_conditions.append("h.created_at < :window_end_at")
        locator_conditions.append("l.created_at < :window_end_at")
        params["window_end_at"] = _to_database_local_datetime(window_end_at)
    hit_where = " AND ".join(hit_conditions)
    locator_where = " AND ".join(locator_conditions)
    limit_clause = "LIMIT :row_limit" if isinstance(limit, int) and limit > 0 else ""
    if limit_clause:
        params["row_limit"] = limit
    sql = text(
        f"""
        WITH {_legacy_current_analysis_ctes(locator_where=locator_where)},
        ranked_hits AS (
            SELECT h.*,
                   ROW_NUMBER() OVER (
                       PARTITION BY h.candidate_fingerprint
                       ORDER BY
                           CASE
                               WHEN h.hit_status IN ('sent', 'message_sent') THEN 5
                               WHEN h.hit_status = 'skipped_recent_continue' THEN 4
                               WHEN h.hit_status LIKE 'send_failed:%' THEN 3
                               WHEN h.hit_status = 'continue_without_db_record' THEN 2
                               WHEN h.hit_status = 'reviewed' THEN 1
                               ELSE 0
                           END DESC,
                           h.created_at DESC,
                           h.id DESC
                   ) AS hit_rank
            FROM candidate_resume_search_hits h
            WHERE h.candidate_fingerprint IS NOT NULL
              AND h.candidate_fingerprint <> ''
              {f"AND {hit_where}" if hit_where else ""}
        ),
        latest_hits AS (
            SELECT * FROM ranked_hits WHERE hit_rank = 1
        )
        SELECT h.candidate_fingerprint,
               h.candidate_name,
               h.search_keyword,
               h.hit_status,
               h.search_batch_id,
               h.created_at,
               c.job_profile_id,
               current_locator.candidate_card_text,
               COALESCE(c.experience, r.experience) AS experience,
               c.match_score AS match_score,
               c.analysis_status AS analysis_status,
               c.match_result AS match_result,
               c.analysis_reason AS analysis_reason,
               c.execution_status, c.error_code, c.retryable, c.retry_after,
               c.analysis_run_id, c.analyzed_text_hash,
               c.score_breakdown_json,
               c.evidence_summary_json,
               c.created_at AS analyzed_at,
               r.manual_review_status,
               r.manual_review_feedback,
               r.manual_suppress_report,
               c.created_at AS updated_at,
               COALESCE(r.source_platform, 'liepin') AS source_platform
        FROM current_analysis c
        INNER JOIN latest_hits h
          ON h.candidate_fingerprint = c.candidate_fingerprint
        LEFT JOIN candidate_resume_experience_records r
          ON r.candidate_fingerprint = h.candidate_fingerprint
        LEFT JOIN candidate_resume_source_locators current_locator
          ON current_locator.id = c.locator_id
        WHERE COALESCE(r.manual_suppress_report, 0) = 0
          AND h.hit_status <> 'skipped_recent_analysis'
          AND COALESCE(c.experience, r.experience, '') <> ''
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
        f"""
        WITH {_legacy_current_analysis_ctes()}
        SELECT
            SUM(CASE WHEN c.match_result = 'relevant' THEN 1 ELSE 0 END) AS relevant_count,
            SUM(
                CASE
                    WHEN c.match_result = 'not_relevant'
                      OR (
                          c.match_result = 'needs_review'
                          AND {legacy_analysis_success_sql("c.analysis_reason", "c.execution_status")}
                          AND COALESCE(c.match_score, 0) < :needs_review_min_score
                      )
                    THEN 1 ELSE 0
                END
            ) AS not_relevant_count,
            SUM(
                CASE
                    WHEN c.match_result = 'needs_review'
                     AND {legacy_analysis_success_sql("c.analysis_reason", "c.execution_status")}
                     AND COALESCE(c.match_score, 0) >= :needs_review_min_score
                    THEN 1 ELSE 0
                END
            ) AS needs_review_count
        FROM current_analysis c
        LEFT JOIN candidate_resume_experience_records r
          ON r.candidate_fingerprint = c.candidate_fingerprint
        WHERE COALESCE(r.manual_suppress_report, 0) = 0
        """
    )
    greeted_sql = text(
        """
        SELECT COUNT(DISTINCT candidate_fingerprint) AS greeted_count
        FROM candidate_resume_search_hits
        WHERE hit_status IN :sent_hit_statuses
        """
    ).bindparams(bindparam("sent_hit_statuses", expanding=True))
    with engine.begin() as connection:
        analysis = (
            connection.execute(
                analysis_sql,
                {
                    **_active_analysis_params(settings),
                    "needs_review_min_score": ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
                },
            )
            .mappings()
            .first()
            or {}
        )
        greeted = (
            connection.execute(
                greeted_sql,
                {"sent_hit_statuses": tuple(SENT_HIT_STATUSES)},
            )
            .mappings()
            .first()
            or {}
        )
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
        f"""
        WITH {_legacy_current_analysis_ctes()}
        SELECT
            CASE
                WHEN analysis.job_profile_id = 'finance_shared_management_v1'
                    THEN '财务共享与管理经理'
                WHEN analysis.job_profile_id = 'fde_ai_deployment_engineer_v1'
                    THEN 'AI前沿部署工程师（FDE）'
                WHEN analysis.job_profile_id = 'ai_development_engineer_v1'
                    THEN 'AI开发工程师'
                ELSE '达人营销总监'
            END AS job_name,
            SUM(CASE WHEN analysis.match_result = 'relevant' THEN 1 ELSE 0 END) AS relevant_count,
            SUM(
                CASE
                    WHEN analysis.match_result = 'needs_review'
                     AND {legacy_analysis_success_sql("analysis.analysis_reason", "analysis.execution_status")}
                     AND COALESCE(analysis.match_score, 0) >= :needs_review_min_score
                    THEN 1 ELSE 0
                END
            ) AS needs_review_count
        FROM current_analysis analysis
        LEFT JOIN candidate_resume_experience_records record
          ON record.candidate_fingerprint = analysis.candidate_fingerprint
        WHERE COALESCE(record.manual_suppress_report, 0) = 0
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
                WHERE hit.hit_status IN :sent_hit_statuses
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
            CASE
                WHEN latest_locator.job_profile_id = 'finance_shared_management_v1'
                     OR latest_sent_hit.search_keyword LIKE '%财务%'
                    THEN '财务共享与管理经理'
                WHEN latest_locator.job_profile_id = 'fde_ai_deployment_engineer_v1'
                     OR latest_sent_hit.search_keyword LIKE '%FDE%'
                    THEN 'AI前沿部署工程师（FDE）'
                WHEN latest_locator.job_profile_id = 'ai_development_engineer_v1'
                     OR latest_sent_hit.search_keyword LIKE '%AI开发工程师%'
                    THEN 'AI开发工程师'
                ELSE '达人营销总监'
            END AS job_name,
            COUNT(*) AS greeted_count
        FROM latest_sent_hit
        LEFT JOIN latest_locator
          ON latest_locator.candidate_fingerprint = latest_sent_hit.candidate_fingerprint
        GROUP BY job_name
        """
    ).bindparams(bindparam("sent_hit_statuses", expanding=True))
    result = {
        "达人营销总监": {"relevant": 0, "needs_review": 0, "greeted": 0},
        "财务共享与管理经理": {"relevant": 0, "needs_review": 0, "greeted": 0},
    }
    with engine.begin() as connection:
        rows = (
            connection.execute(
                analysis_sql,
                {
                    **_active_analysis_params(settings),
                    "needs_review_min_score": ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
                },
            )
            .mappings()
            .all()
        )
        greeted_rows = (
            connection.execute(
                greeted_sql,
                {"sent_hit_statuses": tuple(SENT_HIT_STATUSES)},
            )
            .mappings()
            .all()
        )
    for row in rows:
        job_name = str(row.get("job_name") or "达人营销总监")
        result[job_name] = {
            "relevant": int(row.get("relevant_count") or 0),
            "needs_review": int(row.get("needs_review_count") or 0),
            "greeted": 0,
        }
    for row in greeted_rows:
        job_name = str(row.get("job_name") or "达人营销总监")
        result.setdefault(
            job_name,
            {"relevant": 0, "needs_review": 0, "greeted": 0},
        )
        result[job_name]["greeted"] = int(row.get("greeted_count") or 0)
    return result


def _load_cumulative_report_window(
    settings: Settings,
    *,
    job_profile_ids: set[str] | None = None,
) -> tuple[datetime | None, datetime | None]:
    engine = get_engine(settings)
    if job_profile_ids == {"fde_ai_deployment_engineer_v1"}:
        sql = text(
            """
            SELECT MIN(created_at) AS start_at, MAX(created_at) AS end_at
            FROM candidate_resume_source_locators
            WHERE job_profile_id = 'fde_ai_deployment_engineer_v1'
            """
        )
        with engine.begin() as connection:
            row = connection.execute(sql).mappings().first() or {}
        return row.get("start_at"), row.get("end_at")
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
    jd_analysis_version, finance_analysis_version, fde_analysis_version, legacy_analysis_version = (
        _active_report_analysis_versions(settings)
    )
    sql = text(
        """
        SELECT c.candidate_fingerprint,
               c.analysis_status,
               c.match_result,
               c.match_score,
               c.score_breakdown_json,
               c.analysis_reason,
               c.execution_status, c.error_code, c.retryable, c.retry_after,
               c.analysis_run_id, c.analyzed_text_hash,
               c.evidence_summary_json,
               c.updated_at,
               c.analysis_version,
               c.id
        FROM candidate_resume_analysis_cache c
        WHERE c.analysis_version IN (
            :jd_analysis_version,
            :finance_analysis_version,
            :fde_analysis_version,
            :legacy_analysis_version
        )
        ORDER BY c.candidate_fingerprint,
                 CASE
                     WHEN c.analysis_version IN (
                         :jd_analysis_version,
                         :finance_analysis_version,
                         :fde_analysis_version
                     ) THEN 2
                     ELSE 1
                 END DESC,
                 c.id DESC
        """
    )
    with engine.begin() as connection:
        rows = connection.execute(
            sql,
            {
            "jd_analysis_version": jd_analysis_version,
            "finance_analysis_version": finance_analysis_version,
            "fde_analysis_version": fde_analysis_version,
            "legacy_analysis_version": legacy_analysis_version,
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
        matched=is_relevant_result(
            match_result,
            analysis_status=analysis_status,
        ),
        match_result=match_result,
        match_score=_optional_int(record.get("match_score")),
        score_breakdown=_parse_score_breakdown(record.get("score_breakdown_json")),
        analysis_reason=_optional_str(record.get("analysis_reason")),
        evidence_summary=_parse_string_list(record.get("evidence_summary_json")),
        raw_data="",
        parsed_payload={},
        execution_status=record.get("execution_status"),
        error_code=record.get("error_code"),
        retryable=bool(record.get("retryable")),
        retry_after=record.get("retry_after"),
        analysis_run_id=record.get("analysis_run_id"),
        analyzed_text_hash=record.get("analyzed_text_hash"),
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


def _is_coze_api_failure_outcome(outcome: CandidateProcessOutcome) -> bool:
    """Exclude provider execution failures from human-review report statistics."""
    return has_analysis_execution_failure(
        outcome.analysis_reason, execution_status=outcome.execution_status
    )


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
