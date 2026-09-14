from __future__ import annotations

from datetime import datetime, timedelta

from liepin_rpa.candidate_decision_rules import (
    ANALYSIS_STATUS_MATCHED,
    ANALYSIS_STATUS_NEEDS_REVIEW,
    ANALYSIS_STATUS_NOT_RELEVANT,
    is_needs_review_match_result,
    is_not_relevant_match_result,
    is_relevant_match_result,
)

# Shared collection/portal/report rule: relevant only requires a valid score;
# needs_review must reach 90 before it becomes actionable.
ACTIONABLE_NEEDS_REVIEW_MIN_SCORE = 90
ACTIONABLE_MIN_SCORE = ACTIONABLE_NEEDS_REVIEW_MIN_SCORE
ACTIONABLE_DAREN_MIN_SCORE = 90
# Compatibility alias. There is no separate finance gate in the approved rule.
ACTIONABLE_FINANCE_MIN_SCORE = ACTIONABLE_NEEDS_REVIEW_MIN_SCORE
AI_ANALYSIS_VALIDITY = timedelta(days=7)


def actionable_report_min_score(job: object = None) -> int:
    """Return the single approved needs-review gate for every job."""

    return ACTIONABLE_NEEDS_REVIEW_MIN_SCORE


def actionable_analysis_valid_after(now: datetime | None = None) -> datetime:
    """Return the earliest analysis time allowed for automated actions and reports."""

    return (now or datetime.now()) - AI_ANALYSIS_VALIDITY


def is_analysis_result_fresh(analyzed_at: object, *, now: datetime | None = None) -> bool:
    """A successful score is valid for automated actions for seven 24-hour periods."""

    if isinstance(analyzed_at, str):
        try:
            analyzed_at = datetime.fromisoformat(analyzed_at.strip())
        except ValueError:
            return False
    if not isinstance(analyzed_at, datetime):
        return False
    current = now or datetime.now(analyzed_at.tzinfo)
    if current.tzinfo is None and analyzed_at.tzinfo is not None:
        current = current.replace(tzinfo=analyzed_at.tzinfo)
    if current.tzinfo is not None and analyzed_at.tzinfo is None:
        analyzed_at = analyzed_at.replace(tzinfo=current.tzinfo)
    age = current - analyzed_at
    return timedelta(0) <= age <= AI_ANALYSIS_VALIDITY


def is_relevant_result(
    match_result: object,
    *,
    analysis_status: object = None,
) -> bool:
    return is_relevant_match_result(match_result) or (
        match_result in (None, "") and analysis_status == ANALYSIS_STATUS_MATCHED
    )


def has_needs_review_result(
    match_result: object,
    *,
    analysis_status: object = None,
) -> bool:
    return is_needs_review_match_result(match_result) or (
        match_result in (None, "")
        and analysis_status == ANALYSIS_STATUS_NEEDS_REVIEW
    )


def is_actionable_needs_review_result(
    match_result: object,
    match_score: object,
    *,
    analysis_status: object = None,
    analysis_reason: object = None,
    min_score: int = ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
) -> bool:
    has_review_result = has_needs_review_result(
        match_result,
        analysis_status=analysis_status,
    )
    if not has_review_result or "Coze API" in str(analysis_reason or ""):
        return False
    try:
        return int(match_score) >= int(min_score)
    except (TypeError, ValueError):
        return False


def is_actionable_relevant_result(
    match_result: object,
    match_score: object,
    *,
    analysis_status: object = None,
    min_score: int = ACTIONABLE_DAREN_MIN_SCORE,
) -> bool:
    if not is_relevant_result(match_result, analysis_status=analysis_status):
        return False
    try:
        int(match_score)
    except (TypeError, ValueError):
        return False
    return True


def is_not_relevant_result(
    match_result: object,
    *,
    analysis_status: object = None,
) -> bool:
    return is_not_relevant_match_result(match_result) or (
        match_result in (None, "")
        and analysis_status == ANALYSIS_STATUS_NOT_RELEVANT
    )


def is_actionable_report_result(
    match_result: object,
    match_score: object,
    *,
    analysis_status: object = None,
    analysis_reason: object = None,
    min_score: int = ACTIONABLE_DAREN_MIN_SCORE,
) -> bool:
    if "Coze API" in str(analysis_reason or ""):
        return False
    try:
        score = int(match_score)
    except (TypeError, ValueError):
        return False
    if is_relevant_result(match_result, analysis_status=analysis_status):
        return is_actionable_relevant_result(
            match_result,
            score,
            analysis_status=analysis_status,
            min_score=min_score,
        )
    return is_actionable_needs_review_result(
        match_result,
        score,
        analysis_status=analysis_status,
        analysis_reason=analysis_reason,
        min_score=min_score,
    )
