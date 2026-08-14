from __future__ import annotations

import json

from liepin_rpa.feishu_report import JobReportSummary, _build_job_progress_elements


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
