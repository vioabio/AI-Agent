from __future__ import annotations

from liepin_rpa.candidate_decision_rules import (
    ANALYSIS_STATUS_MATCHED,
    ANALYSIS_STATUS_NEEDS_REVIEW,
    ANALYSIS_STATUS_NOT_RELEVANT,
    is_needs_review_match_result,
    is_not_relevant_match_result,
    is_relevant_match_result,
)

# Product rule shared by collection prioritization, exports, PDF selection and
# report metrics. Relevant candidates are actionable at every score; only
# needs_review candidates are score-gated.
ACTIONABLE_NEEDS_REVIEW_MIN_SCORE = 90


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
) -> bool:
    has_review_result = has_needs_review_result(
        match_result,
        analysis_status=analysis_status,
    )
    if not has_review_result or "Coze API" in str(analysis_reason or ""):
        return False
    try:
        return int(match_score) >= ACTIONABLE_NEEDS_REVIEW_MIN_SCORE
    except (TypeError, ValueError):
        return False


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
) -> bool:
    return is_relevant_result(match_result, analysis_status=analysis_status) or (
        is_actionable_needs_review_result(
            match_result,
            match_score,
            analysis_status=analysis_status,
            analysis_reason=analysis_reason,
        )
    )
