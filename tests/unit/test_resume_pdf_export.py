from __future__ import annotations

from types import SimpleNamespace

from pypdf import PdfReader

from liepin_rpa import db_daily_report
from liepin_rpa.db_daily_report import _version_pdf_public_url
from liepin_rpa.feishu_report import ReportCandidate, _build_detail_documents_markdown
from liepin_rpa.resume_pdf_export import _format_candidate_status, export_featured_resume_pdf


def test_resume_pdf_adds_clickable_saved_liepin_link(tmp_path) -> None:
    output_path = tmp_path / "daily_report.pdf"
    fingerprint = "candidate-1"
    resume_url = (
        "https://mr.liepin.com/resume/view-share-res?"
        "bsSource=res_forward&bsInfo=h5&acKey=abc123"
    )
    candidate = ReportCandidate(
        candidate_fingerprint=fingerprint,
        candidate_name="林先生",
        match_result="relevant",
        match_score=96,
        analysis_reason="达人营销和直播带货经验符合岗位要求。",
    )

    exported = export_featured_resume_pdf(
        output_path=output_path,
        candidates=[candidate],
        experience_by_fingerprint={fingerprint: "工作内容：负责达人营销项目。"},
        resume_link_by_fingerprint={fingerprint: resume_url},
        interaction_note_by_fingerprint={fingerprint: "你们之前HR找过我"},
    )

    assert exported == output_path
    reader = PdfReader(output_path)
    annotation_uris: list[str] = []
    for page in reader.pages:
        for annotation_ref in page.get("/Annots", []):
            annotation = annotation_ref.get_object()
            action = annotation.get("/A")
            if action and action.get("/URI"):
                annotation_uris.append(str(action["/URI"]))
    assert resume_url in annotation_uris
    assert "点击查看猎聘原始简历" in "".join(page.extract_text() or "" for page in reader.pages)
    assert "猎聘互动提示（非姓名）：你们之前HR找过我" in "".join(
        page.extract_text() or "" for page in reader.pages
    )


def test_pdf_public_url_uses_content_hash_to_avoid_stale_feishu_preview(tmp_path) -> None:
    pdf_path = tmp_path / "candidate.pdf"
    pdf_path.write_bytes(b"new-pdf-content")

    plain_url = _version_pdf_public_url("https://example.com/candidate.pdf", pdf_path)
    query_url = _version_pdf_public_url("https://example.com/candidate.pdf?source=test", pdf_path)

    assert plain_url is not None and plain_url.startswith(
        "https://example.com/candidate.pdf?v="
    )
    assert query_url is not None and "&v=" in query_url


def test_report_lists_one_pdf_link_per_job_and_omits_empty_pdf_button() -> None:
    markdown = _build_detail_documents_markdown(
        {
            "达人营销总监": "https://example.com/daren.pdf",
            "财务共享与管理经理": None,
        }
    )

    assert "达人营销总监：[查看候选人明细及判断依据](https://example.com/daren.pdf)" in markdown
    assert "财务共享与管理经理：暂无80分以上候选人" in markdown


def test_pdf_status_only_marks_needs_review_at_80_or_above() -> None:
    low_score = ReportCandidate(
        candidate_fingerprint="review-79",
        candidate_name="79分候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=79,
    )
    threshold_score = ReportCandidate(
        candidate_fingerprint="review-80",
        candidate_name="80分候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=80,
    )

    assert _format_candidate_status(low_score) == "不匹配（未达80分待复核线）"
    assert _format_candidate_status(threshold_score) == "待人工复核"


def test_pdf_relevant_status_preserves_contact_action() -> None:
    greeted = ReportCandidate(
        candidate_fingerprint="relevant-sent",
        candidate_name="已沟通候选人",
        match_result="relevant",
        match_score=70,
        hit_status="sent",
    )
    pending = ReportCandidate(
        candidate_fingerprint="relevant-reviewed",
        candidate_name="待沟通候选人",
        match_result="relevant",
        match_score=70,
        hit_status="reviewed",
    )

    assert _format_candidate_status(greeted) == "已打招呼"
    assert _format_candidate_status(pending) == "匹配未打招呼"


def test_pdf_explicit_not_relevant_wins_over_conflicting_review_status() -> None:
    candidate = ReportCandidate(
        candidate_fingerprint="conflicting-status",
        candidate_name="矛盾状态候选人",
        analysis_status="待复核",
        match_result="not_relevant",
        match_score=99,
    )

    assert _format_candidate_status(candidate) == "不匹配"


def test_pdf_coze_api_failure_never_becomes_below_threshold_mismatch() -> None:
    retry_candidate = ReportCandidate(
        candidate_fingerprint="coze-retry",
        candidate_name="重试候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=None,
        analysis_reason="需要人工复核：Coze API 临时异常，系统未进行自动匹配判断。",
        recent_experience_summary="候选人简历正文完整",
        evidence_summary=["已有完整简历证据"],
    )
    recollection_candidate = ReportCandidate(
        candidate_fingerprint="coze-recollection",
        candidate_name="重采候选人",
        analysis_status="待复核",
        match_result="needs_review",
        match_score=None,
        analysis_reason="需要人工复核：Coze API 临时异常，系统未进行自动匹配判断。",
    )

    assert _format_candidate_status(retry_candidate) == "AI分析重试"
    assert _format_candidate_status(recollection_candidate) == "需重新采集"


def test_daily_pdf_includes_only_80_point_relevant_and_review_candidates(
    monkeypatch,
    tmp_path,
) -> None:
    greeted_relevant_below_80 = ReportCandidate(
        candidate_fingerprint="relevant-70",
        candidate_name="已打招呼低分匹配候选人",
        match_result="relevant",
        match_score=70,
        hit_status="sent",
        role_keywords={"达人营销总监"},
    )
    ungreeted_relevant_below_80 = ReportCandidate(
        candidate_fingerprint="relevant-79",
        candidate_name="未打招呼低分匹配候选人",
        match_result="relevant",
        match_score=79,
        hit_status="reviewed",
        role_keywords={"达人营销总监"},
    )
    ungreeted_relevant_at_80 = ReportCandidate(
        candidate_fingerprint="relevant-80",
        candidate_name="80分匹配候选人",
        match_result="relevant",
        match_score=80,
        hit_status="reviewed",
        role_keywords={"达人营销总监"},
    )
    review_at_80 = ReportCandidate(
        candidate_fingerprint="review-80",
        candidate_name="待复核候选人",
        match_result="needs_review",
        match_score=80,
        role_keywords={"达人营销总监"},
    )
    review_below_80 = ReportCandidate(
        candidate_fingerprint="review-79",
        candidate_name="低分待复核候选人",
        match_result="needs_review",
        match_score=79,
        role_keywords={"达人营销总监"},
    )
    high_score_not_relevant = ReportCandidate(
        candidate_fingerprint="not-relevant-99",
        candidate_name="高分不匹配候选人",
        match_result="not_relevant",
        match_score=99,
        role_keywords={"达人营销总监"},
    )
    candidates = [
        greeted_relevant_below_80,
        ungreeted_relevant_below_80,
        ungreeted_relevant_at_80,
        review_at_80,
        review_below_80,
        high_score_not_relevant,
    ]
    captured: dict[str, dict[str, object]] = {}

    monkeypatch.setattr(db_daily_report, "build_report_candidates", lambda _outcomes: candidates)
    monkeypatch.setattr(
        db_daily_report,
        "_backfill_pdf_experience_from_database",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        db_daily_report,
        "_load_candidate_resume_links",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        db_daily_report,
        "_load_candidate_interaction_notes",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        db_daily_report,
        "_load_cumulative_review_candidates_by_job",
        lambda *_args, **_kwargs: (
            {"达人营销总监": [], "财务共享与管理经理": []},
            {},
        ),
    )

    def fake_export(**kwargs):
        captured[str(kwargs["job_name"])] = kwargs
        if not any(
            (
                kwargs["candidates"],
                kwargs["review_candidates"],
                kwargs["cumulative_review_candidates"],
            )
        ):
            return None
        return kwargs["output_path"]

    monkeypatch.setattr(db_daily_report, "export_featured_resume_pdf", fake_export)

    result = db_daily_report._export_candidate_pdfs_by_job(
        SimpleNamespace(artifacts_dir=tmp_path),  # type: ignore[arg-type]
        candidate_outcomes=[],
        contacted_records=[],
        report_key="test-report",
    )

    daren_pdf = captured["达人营销总监"]
    assert daren_pdf["candidates"] == [ungreeted_relevant_at_80]
    assert daren_pdf["review_candidates"] == [review_at_80]
    assert "other_candidates" not in daren_pdf
    assert "recollection_candidates" not in daren_pdf
    assert result["达人营销总监"] is not None
    assert result["财务共享与管理经理"] is None


def test_daily_pdf_adds_cumulative_review_candidates_without_current_duplicates(
    monkeypatch,
    tmp_path,
) -> None:
    current_match = ReportCandidate(
        candidate_fingerprint="current-match",
        candidate_name="本批匹配候选人",
        match_result="relevant",
        match_score=95,
        role_keywords={"达人营销总监"},
    )
    duplicate_review = ReportCandidate(
        candidate_fingerprint="current-match",
        candidate_name="重复待复核候选人",
        match_result="needs_review",
        match_score=96,
        role_keywords={"达人营销总监"},
    )
    historical_daren_review = ReportCandidate(
        candidate_fingerprint="historical-daren-review",
        candidate_name="达人历史待复核",
        match_result="needs_review",
        match_score=93,
        role_keywords={"达人营销总监"},
    )
    historical_finance_review = ReportCandidate(
        candidate_fingerprint="historical-finance-review",
        candidate_name="财务历史待复核",
        match_result="needs_review",
        match_score=92,
        role_keywords={"财务共享与管理经理"},
    )
    captured: dict[str, dict[str, object]] = {}

    monkeypatch.setattr(
        db_daily_report,
        "build_report_candidates",
        lambda _outcomes: [current_match],
    )
    monkeypatch.setattr(
        db_daily_report,
        "_load_cumulative_review_candidates_by_job",
        lambda *_args, **_kwargs: (
            {
                "达人营销总监": [duplicate_review, historical_daren_review],
                "财务共享与管理经理": [historical_finance_review],
            },
            {},
        ),
    )
    monkeypatch.setattr(
        db_daily_report,
        "_backfill_pdf_experience_from_database",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        db_daily_report,
        "_load_candidate_resume_links",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        db_daily_report,
        "_load_candidate_interaction_notes",
        lambda *_args, **_kwargs: {},
    )

    def fake_export(**kwargs):
        captured[str(kwargs["job_name"])] = kwargs
        return kwargs["output_path"]

    monkeypatch.setattr(db_daily_report, "export_featured_resume_pdf", fake_export)

    db_daily_report._export_candidate_pdfs_by_job(
        SimpleNamespace(artifacts_dir=tmp_path),  # type: ignore[arg-type]
        candidate_outcomes=[],
        contacted_records=[],
        report_key="test-cumulative-review",
    )

    assert captured["达人营销总监"]["candidates"] == [current_match]
    assert captured["达人营销总监"]["cumulative_review_candidates"] == [
        historical_daren_review
    ]
    assert captured["财务共享与管理经理"]["cumulative_review_candidates"] == [
        historical_finance_review
    ]
