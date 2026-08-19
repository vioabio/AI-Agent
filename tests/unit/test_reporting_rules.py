from liepin_rpa.reporting_rules import (
    ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
    is_actionable_needs_review_result,
    is_actionable_report_result,
    is_not_relevant_result,
)


def test_actionable_report_population_requires_candidate_result_and_90_points() -> None:
    assert ACTIONABLE_NEEDS_REVIEW_MIN_SCORE == 90
    assert is_actionable_report_result("relevant", 90)
    assert not is_actionable_report_result("relevant", 89)
    assert not is_actionable_report_result("relevant", None)
    assert is_actionable_report_result("needs_review", 90)
    assert not is_actionable_report_result("needs_review", 89)
    assert not is_actionable_report_result("not_relevant", 100)


def test_explicit_match_result_wins_over_conflicting_legacy_status() -> None:
    assert not is_actionable_needs_review_result(
        "not_relevant",
        99,
        analysis_status="待复核",
    )
    assert is_actionable_needs_review_result(
        None,
        90,
        analysis_status="待复核",
    )
    assert not is_not_relevant_result(
        "relevant",
        analysis_status="不匹配",
    )
    assert is_not_relevant_result(
        "not_relevant",
        analysis_status="匹配",
    )


def test_transient_coze_failure_is_not_an_actionable_review() -> None:
    assert not is_actionable_needs_review_result(
        "needs_review",
        99,
        analysis_reason="需要人工复核：Coze API 临时异常，code=4000",
    )
