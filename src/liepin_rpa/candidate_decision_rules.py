from __future__ import annotations

MATCH_RESULT_RELEVANT = "relevant"
MATCH_RESULT_NEEDS_REVIEW = "needs_review"
MATCH_RESULT_NOT_RELEVANT = "not_relevant"

VALID_MATCH_RESULTS = frozenset(
    {
        MATCH_RESULT_RELEVANT,
        MATCH_RESULT_NEEDS_REVIEW,
        MATCH_RESULT_NOT_RELEVANT,
    }
)

ANALYSIS_STATUS_MATCHED = "匹配"
ANALYSIS_STATUS_NEEDS_REVIEW = "待复核"
ANALYSIS_STATUS_NOT_RELEVANT = "不匹配"


def is_relevant_match_result(match_result: object) -> bool:
    return match_result == MATCH_RESULT_RELEVANT


def is_needs_review_match_result(match_result: object) -> bool:
    return match_result == MATCH_RESULT_NEEDS_REVIEW


def is_not_relevant_match_result(match_result: object) -> bool:
    return match_result == MATCH_RESULT_NOT_RELEVANT


def allows_automatic_communication(match_result: object) -> bool:
    """Only an explicit relevant result authorizes outbound communication."""

    return is_relevant_match_result(match_result)


def analysis_status_for_match_result(match_result: object) -> str:
    """Map the canonical AI tri-state to its persisted Chinese status.

    Unknown or missing values fail closed to human review instead of being
    silently treated as relevant or not relevant.
    """

    if is_relevant_match_result(match_result):
        return ANALYSIS_STATUS_MATCHED
    if is_not_relevant_match_result(match_result):
        return ANALYSIS_STATUS_NOT_RELEVANT
    return ANALYSIS_STATUS_NEEDS_REVIEW


def beauty_relevance_matches_result(
    beauty_relevant: object,
    match_result: object,
) -> bool:
    """Validate Coze's compatibility boolean against the canonical tri-state."""

    if not isinstance(beauty_relevant, bool) or match_result not in VALID_MATCH_RESULTS:
        return False
    return beauty_relevant is is_relevant_match_result(match_result)
