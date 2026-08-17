from __future__ import annotations

import json

from liepin_rpa.feishu_report import (
    JobReportSummary,
    ReportCandidate,
    _build_job_progress_elements,
    select_review_candidates,
)


def test_job_progress_card_separates_today_and_cumulative_metrics() -> None:
    job = JobReportSummary(
        job_name="达人营销总监",
        screened_candidate_views=864,
        relevant_count=0,
        needs_review_count=0,
        greeted_count=0,
        cumulative_screened_candidate_views=38_695,
        cumulative_relevant_count=197,
        cumulative_needs_review_count=67,
        cumulative_greeted_count=61,
    )

    rendered = json.dumps(_build_job_progress_elements(job), ensure_ascii=False)

    assert "达人营销总监" in rendered
    assert "招聘进展明细" in rendered
    assert "简历卡片浏览人次" in rendered
    assert "今日" in rendered
    assert "864" in rendered
    assert "累计" in rendered
    assert "38,695" in rendered
    assert "197 份" in rendered
    assert "67 份" in rendered
    assert "61 份" in rendered
    assert "简历不匹配" not in rendered
    assert "本次暂无90分以上候选人" in rendered


def test_coze_api_failure_is_not_counted_as_human_review_candidate() -> None:
    candidate = ReportCandidate(
        candidate_fingerprint="coze-system-failure",
        candidate_name="测试候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=99,
        analysis_reason="需要人工复核：Coze API 临时异常，code=4000",
        recent_experience_summary="候选人简历正文完整",
        evidence_summary=["已有完整简历证据"],
    )

    assert select_review_candidates([candidate]) == []


def test_only_needs_review_candidates_scoring_at_least_90_enter_review_list() -> None:
    below_threshold = ReportCandidate(
        candidate_fingerprint="review-89",
        candidate_name="89分候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=89,
    )
    at_threshold = ReportCandidate(
        candidate_fingerprint="review-90",
        candidate_name="90分候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=90,
    )

    assert select_review_candidates([below_threshold, at_threshold]) == [at_threshold]
