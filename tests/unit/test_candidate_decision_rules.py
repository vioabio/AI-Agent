from liepin_rpa.candidate_decision_rules import (
    ANALYSIS_STATUS_MATCHED,
    ANALYSIS_STATUS_NEEDS_REVIEW,
    ANALYSIS_STATUS_NOT_RELEVANT,
    allows_automatic_communication,
    analysis_status_for_match_result,
    beauty_relevance_matches_result,
)


def test_ai_tri_state_has_one_persisted_status_mapping() -> None:
    assert analysis_status_for_match_result("relevant") == ANALYSIS_STATUS_MATCHED
    assert analysis_status_for_match_result("needs_review") == ANALYSIS_STATUS_NEEDS_REVIEW
    assert analysis_status_for_match_result("not_relevant") == ANALYSIS_STATUS_NOT_RELEVANT
    assert analysis_status_for_match_result(None) == ANALYSIS_STATUS_NEEDS_REVIEW
    assert analysis_status_for_match_result("unexpected") == ANALYSIS_STATUS_NEEDS_REVIEW


def test_only_explicit_relevant_authorizes_automatic_communication() -> None:
    assert allows_automatic_communication("relevant") is True
    assert allows_automatic_communication("needs_review") is False
    assert allows_automatic_communication("not_relevant") is False
    assert allows_automatic_communication(None) is False


def test_beauty_relevant_must_exactly_match_canonical_result() -> None:
    assert beauty_relevance_matches_result(True, "relevant") is True
    assert beauty_relevance_matches_result(False, "needs_review") is True
    assert beauty_relevance_matches_result(False, "not_relevant") is True
    assert beauty_relevance_matches_result(True, "needs_review") is False
    assert beauty_relevance_matches_result(True, "not_relevant") is False
    assert beauty_relevance_matches_result(False, "relevant") is False
