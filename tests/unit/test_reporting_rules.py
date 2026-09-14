from liepin_rpa.reporting_rules import (
    ACTIONABLE_DAREN_MIN_SCORE,
    ACTIONABLE_FINANCE_MIN_SCORE,
    ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
    actionable_report_min_score,
    is_actionable_needs_review_result,
    is_actionable_relevant_result,
    is_actionable_report_result,
    is_not_relevant_result,
)


def test_actionable_report_population_uses_one_review_gate() -> None:
    assert ACTIONABLE_DAREN_MIN_SCORE == 90
    assert ACTIONABLE_FINANCE_MIN_SCORE == 90
    assert ACTIONABLE_NEEDS_REVIEW_MIN_SCORE == 90
    assert actionable_report_min_score("达人营销总监") == 90
    assert actionable_report_min_score({"财务共享与管理经理"}) == 90
    assert is_actionable_relevant_result("relevant", 1)
    assert is_actionable_report_result("relevant", 1)
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
