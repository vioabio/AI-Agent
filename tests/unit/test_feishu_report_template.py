from __future__ import annotations

import json

from liepin_rpa.feishu_report import (
    FeishuReportSummary,
    JobReportSummary,
    ReportCandidate,
    _build_management_summary_elements,
    _build_detail_documents_markdown,
    _build_job_summaries,
    _build_job_progress_elements,
    select_review_candidates,
)
from liepin_rpa.hr_review_reporting import RecruitmentProgressSnapshot


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
    assert "本批次" in rendered
    assert "864" in rendered
    assert "累计" in rendered
    assert "38,695" in rendered
    assert "197 份" in rendered
    assert "67 份" in rendered
    assert "61 份" in rendered
    assert "简历不匹配" not in rendered
    assert "本次暂无可行动候选人" in rendered


def test_management_card_hides_hr_recruitment_progress_section() -> None:
    summary = FeishuReportSummary(
        total_candidates_seen=0,
        cumulative_screened_candidate_views=0,
        cumulative_relevant_count=0,
        cumulative_needs_review_count=0,
        cumulative_not_relevant_count=0,
        cumulative_greeted_count=0,
        screened_candidate_views=0,
        total_keywords=0,
        hit_keywords=0,
        profiles_collected=0,
        unique_candidates=0,
        greeted_count=0,
        contacted_relevant_count=0,
        skipped_recent_count=0,
        relevant_count=0,
        needs_review_count=0,
        recollection_count=0,
        not_relevant_count=0,
    )
    rendered = json.dumps(
        _build_management_summary_elements(
            summary,
            [],
            job_summaries=[JobReportSummary(job_name="达人营销总监")],
            recruitment_progress=RecruitmentProgressSnapshot(
                ai_relevant=9,
                ai_needs_review=2,
                ai_not_relevant=7,
                actionable_pending=8,
                feedback_candidates_in_window=1,
            ),
        ),
        ensure_ascii=False,
    )

    assert "HR 候选人审核与招聘进度" not in rendered
    assert "AI 原始判断" not in rendered
    assert "AI 可行动待办" not in rendered
    assert "HR 实际招聘进度" not in rendered


def test_management_card_accepts_a_custom_multi_batch_period_label() -> None:
    summary = FeishuReportSummary(
        total_candidates_seen=0,
        cumulative_screened_candidate_views=0,
        cumulative_relevant_count=0,
        cumulative_needs_review_count=0,
        cumulative_not_relevant_count=0,
        cumulative_greeted_count=0,
        screened_candidate_views=0,
        total_keywords=0,
        hit_keywords=0,
        profiles_collected=0,
        unique_candidates=0,
        greeted_count=0,
        contacted_relevant_count=0,
        skipped_recent_count=0,
        relevant_count=0,
        needs_review_count=0,
        recollection_count=0,
        not_relevant_count=0,
    )
    rendered = json.dumps(
        _build_management_summary_elements(
            summary,
            [],
            job_summaries=[JobReportSummary(job_name="达人营销总监")],
            search_batch_id="最近3个完整批次",
            batch_period_label="周五、周六、周日",
        ),
        ensure_ascii=False,
    )

    assert "批次执行时间｜周五、周六、周日" in rendered
    assert "批次执行时间｜未提供" not in rendered


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


def test_finance_candidates_use_same_90_point_review_gate() -> None:
    below_threshold = ReportCandidate(
        candidate_fingerprint="finance-89",
        candidate_name="89分财务候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=89,
        role_keywords={"财务共享与管理经理"},
    )
    at_threshold = ReportCandidate(
        candidate_fingerprint="finance-90",
        candidate_name="90分财务候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=90,
        role_keywords={"财务共享与管理经理"},
    )

    assert select_review_candidates([below_threshold, at_threshold]) == [at_threshold]


def test_relevant_progress_wording_does_not_invent_a_score_gate() -> None:
    rendered = json.dumps(
        _build_job_progress_elements(
            JobReportSummary(job_name="财务共享与管理经理", relevant_count=1)
        ),
        ensure_ascii=False,
    )

    assert "1 位明确匹配候选人" in rendered


def test_fde_only_report_does_not_render_filled_positions() -> None:
    summaries = _build_job_summaries(
        [],
        executed_keywords_by_role={"AI前沿部署工程师（FDE）": 38},
        screened_candidate_views_by_role={"AI前沿部署工程师（FDE）": 1_240},
        cumulative_screened_candidate_views_by_role={"AI前沿部署工程师（FDE）": 1_240},
        cumulative_metrics_by_job={
            "AI前沿部署工程师（FDE）": {
                "relevant": 27,
                "needs_review": 0,
                "greeted": 0,
            }
        },
    )

    assert [summary.job_name for summary in summaries] == ["AI前沿部署工程师（FDE）"]
    assert summaries[0].executed_keywords == 38
    assert summaries[0].screened_candidate_views == 1_240

    detail = _build_detail_documents_markdown(
        {"AI前沿部署工程师（FDE）": "https://example.test/fde.pdf"}
    )
    assert "AI前沿部署工程师（FDE）" in detail
    assert "达人营销总监" not in detail
    assert "财务共享与管理经理" not in detail
