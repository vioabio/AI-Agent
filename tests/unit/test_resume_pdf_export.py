from __future__ import annotations

from pypdf import PdfReader

from liepin_rpa.feishu_report import ReportCandidate, _build_detail_documents_markdown
from liepin_rpa.resume_pdf_export import export_featured_resume_pdf


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


def test_report_lists_one_pdf_link_per_job_and_omits_empty_pdf_button() -> None:
    markdown = _build_detail_documents_markdown(
        {
            "达人营销总监": "https://example.com/daren.pdf",
            "财务共享与管理经理": None,
        }
    )

    assert "达人营销总监：[查看候选人明细及判断依据](https://example.com/daren.pdf)" in markdown
    assert "财务共享与管理经理：暂无90分以上候选人" in markdown
