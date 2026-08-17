from __future__ import annotations

import json
from datetime import datetime
from types import SimpleNamespace

from liepin_rpa.search_results_export import (
    format_hit_status,
    format_skip_reason,
    load_executed_keywords_from_logs,
    load_checkpoint_keywords,
    split_search_keyword,
)
from liepin_rpa.feishu_report import (
    JobPipelineMetrics,
    ReportCandidate,
    _build_role_overview_markdown,
    _build_role_summaries,
    _build_summary,
    _build_feishu_payload,
    _build_pipeline_metrics_markdown,
    _format_candidate_block,
    _format_review_candidate_block,
    _normalize_candidate_name,
    select_featured_candidates,
    select_review_candidates,
    select_recollection_candidates,
)
from liepin_rpa.search_execution.workflow import SearchWorkflowReport


def test_masked_candidate_name_keeps_platform_asterisks() -> None:
    assert _normalize_candidate_name("黄**") == "黄**"


def test_daily_report_card_uses_multi_job_recruiting_title() -> None:
    report = SearchWorkflowReport(
        total_keywords=0,
        matched_keywords=0,
        profiles_collected=0,
        keyword_runs=[],
        candidate_outcomes=[],
        report_path="",
    )

    payload = _build_feishu_payload(report, [])

    assert payload["card"]["header"]["title"]["content"].startswith("重点岗位招聘日报｜")


def test_pipeline_metrics_show_links_city_ai_and_greeting_by_job() -> None:
    markdown = _build_pipeline_metrics_markdown(
        [
            JobPipelineMetrics(
                job_profile_id="daren_live_bd_v1",
                job_title="达人营销与达播负责人",
                executed_keywords=96,
                detail_entries=13,
                official_share_links=12,
                identity_linked=8,
                shanghai_passed=6,
                shanghai_rejected=2,
                ai_analyzed=6,
                relevant_count=2,
                needs_review_count=1,
                not_relevant_count=3,
                greeting_success=2,
                greeting_failed=0,
            )
        ]
    )

    assert "达人营销与达播负责人" in markdown
    assert "搜索词完成：96" in markdown
    assert "进入详情：13｜正式链接：12｜已关联最终身份：8" in markdown
    assert "上海意向：通过 6｜拒绝 2" in markdown
    assert "AI分析：6｜relevant 2｜needs_review 1｜not_relevant 3" in markdown
    assert "打招呼：成功 2｜失败 0" in markdown


def test_load_checkpoint_keywords_includes_completed_and_failed(tmp_path) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    checkpoint.write_text(
        json.dumps(
            {
                "completed_keyword_keys": ["珀莱雅|||达播总监"],
                "failed_keyword_attempts": {
                    "上美|||运营总监": {"attempts": 1},
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    keywords = load_checkpoint_keywords(checkpoint)

    assert keywords == [
        {"品牌": "上美", "岗位": "运营总监", "search_keyword": "上美，运营总监"},
        {"品牌": "珀莱雅", "岗位": "达播总监", "search_keyword": "珀莱雅，达播总监"},
    ]


def test_load_executed_keywords_from_logs_uses_window(tmp_path) -> None:
    logs_dir = tmp_path / "logs"
    logs_dir.mkdir()
    (logs_dir / "app.log").write_text(
        "\n".join(
            [
                json.dumps(
                    {
                        "timestamp": "2026-07-29T04:59:59+00:00",
                        "event": "keyword_run_start",
                        "brand_keyword": "旧品牌",
                        "role_keyword": "旧岗位",
                    },
                    ensure_ascii=False,
                ),
                json.dumps(
                    {
                        "timestamp": "2026-07-29T05:00:00+00:00",
                        "event": "keyword_run_start",
                        "brand_keyword": "珀莱雅",
                        "role_keyword": "达播总监",
                    },
                    ensure_ascii=False,
                ),
            ]
        ),
        encoding="utf-8",
    )
    settings = SimpleNamespace(logs_dir=logs_dir)

    keywords = load_executed_keywords_from_logs(
        settings,  # type: ignore[arg-type]
        window_start_at=datetime(2026, 7, 29, 13, 0, 0),
        window_end_at=datetime(2026, 7, 29, 13, 10, 0),
    )

    assert keywords == [
        {"品牌": "珀莱雅", "岗位": "达播总监", "search_keyword": "珀莱雅，达播总监"}
    ]


def test_split_search_keyword_supports_cn_and_ascii_comma() -> None:
    assert split_search_keyword("珀莱雅，达播总监") == ("珀莱雅", "达播总监")
    assert split_search_keyword("PROYA, 达播总监") == ("PROYA", "达播总监")


def test_format_status_and_skip_reason_for_needs_review_continue() -> None:
    assert (
        format_hit_status("continue_without_db_record", "不匹配", "needs_review")
        == "继续沟通但无历史记录，未发送"
    )
    assert (
        format_skip_reason("continue_without_db_record", "不匹配", "needs_review")
        == "页面显示继续沟通，但库里没有历史沟通记录，按保护逻辑不发送"
    )


def test_format_status_and_skip_reason_for_sent() -> None:
    assert format_hit_status("sent", "匹配", "relevant") == "已发送消息"
    assert format_skip_reason("sent", "匹配", "relevant") == ""


def test_collect_only_high_score_relevant_candidate_is_featured_and_marked_pending_contact() -> None:
    candidate = ReportCandidate(
        candidate_fingerprint="fp-1",
        candidate_name="测试候选人",
        analysis_status="匹配",
        match_result="relevant",
        match_score=96,
        hit_status="send_failed:message_sending_disabled",
        role_keywords={"媒介总监"},
        search_keywords={"倩碧，媒介总监"},
        evidence_summary=["负责达人种草与KOL策略。"],
        candidate_age=36,
    )

    assert select_featured_candidates([candidate]) == [candidate]
    block = _format_candidate_block(candidate)
    assert "状态：待沟通" in block
    assert "最近一段经历：负责达人种草与KOL策略。" in block


def test_needs_review_is_separated_from_not_relevant_and_displayed() -> None:
    review = ReportCandidate(
        candidate_fingerprint="fp-review",
        candidate_name="复核候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=92,
        role_keywords={"达人营销总监"},
        search_keywords={"欧莱雅，达人营销总监"},
        analysis_reason="有达人投放经验，但管理跨度不明确。",
        evidence_summary=["负责达人投放与复盘。"],
    )
    irrelevant = ReportCandidate(
        candidate_fingerprint="fp-no",
        candidate_name="不匹配候选人",
        analysis_status="不匹配",
        match_result="not_relevant",
        match_score=42,
    )
    low_score_review = ReportCandidate(
        candidate_fingerprint="fp-review-low",
        candidate_name="低分复核候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=89,
    )
    report = SearchWorkflowReport(
        total_keywords=1,
        matched_keywords=0,
        profiles_collected=3,
        keyword_runs=[],
        candidate_outcomes=[],
        report_path="",
    )

    summary = _build_summary(report, [review, irrelevant, low_score_review])
    assert summary.needs_review_count == 1
    assert summary.not_relevant_count == 2
    assert select_review_candidates([review, irrelevant, low_score_review]) == [review]
    assert "待复核原因：有达人投放经验，但管理跨度不明确。" in _format_review_candidate_block(review)


def test_export_labels_low_score_needs_review_as_below_review_threshold() -> None:
    assert format_hit_status("reviewed", "待复核", "needs_review", 89) == (
        "Coze未达90分待复核线，未发送"
    )
    assert format_skip_reason("reviewed", "待复核", "needs_review", 89) == (
        "Coze分数未达到90分待复核线"
    )
    assert format_hit_status("reviewed", "待复核", "needs_review", 90) == (
        "需人工复核，未发送"
    )


def test_transient_api_review_without_resume_is_classified_for_recollection() -> None:
    candidate = ReportCandidate(
        candidate_fingerprint="fp-recollect",
        candidate_name="周**",
        analysis_status="待复核",
        match_result="needs_review",
        analysis_reason="需要人工复核：Coze API 临时异常，code=4000, message=invalid",
        role_keywords={"达人营销总监"},
    )

    assert select_review_candidates([candidate]) == []
    assert select_recollection_candidates([candidate]) == [candidate]


def test_role_summary_tracks_execution_and_candidate_results() -> None:
    matched = ReportCandidate(
        candidate_fingerprint="fp-match",
        candidate_name="匹配候选人",
        analysis_status="匹配",
        match_result="relevant",
        match_score=95,
        role_keywords={"媒介总监"},
    )
    review = ReportCandidate(
        candidate_fingerprint="fp-review",
        candidate_name="复核候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=92,
        role_keywords={"达人营销总监"},
    )

    summaries = _build_role_summaries(
        [matched, review],
        executed_keywords_by_role={"媒介总监": 40, "达人营销总监": 80},
    )
    report = SearchWorkflowReport(
        total_keywords=2,
        matched_keywords=1,
        profiles_collected=2,
        keyword_runs=[],
        candidate_outcomes=[],
        report_path="",
    )
    summary = _build_summary(
        report,
        [matched, review],
        executed_keywords=120,
        screened_candidate_views=800,
    )
    markdown = _build_role_overview_markdown(summary, summaries)

    assert "达人营销总监招聘成果" in markdown
    assert "搜索覆盖" in markdown
    assert "达人营销总监、媒介总监" in markdown
    assert "搜索执行｜120 种组合" in markdown
    assert "筛选结果｜匹配 1 份 · 待复核 1 份" in markdown
    assert "沟通进展｜已打招呼 0 份" in markdown
    assert "获取资料" not in markdown


def test_job_intent_city_rejection_is_clear_in_export() -> None:
    hit_status = "job_intent_city_rejected"

    assert format_hit_status(hit_status) == "求职意向未明确上海，跳过"
    assert format_skip_reason(hit_status) == (
        "求职意向未明确包含上海；当前所在地和搜索城市不作为替代证据"
    )
