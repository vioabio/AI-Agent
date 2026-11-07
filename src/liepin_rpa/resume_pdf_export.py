from __future__ import annotations

from liepin_rpa.analysis_execution import has_analysis_execution_failure

from datetime import datetime
from html import escape as html_escape
from pathlib import Path
from typing import Iterable

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    HRFlowable,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
)

from liepin_rpa.feishu_report import ReportCandidate
from liepin_rpa.reporting_rules import (
    ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
    has_needs_review_result,
    is_actionable_needs_review_result,
    is_not_relevant_result,
    is_relevant_result,
)
from liepin_rpa.communication_status_policy import is_sent_hit_status

PDF_FONT_NAME = "MicrosoftYaHei"
PDF_BOLD_FONT_NAME = "MicrosoftYaHeiBold"
PDF_FONT_PATH = Path(r"C:\Windows\Fonts\msyh.ttc")
PDF_BOLD_FONT_PATH = Path(r"C:\Windows\Fonts\msyhbd.ttc")


def build_daily_resume_pdf_path(output_dir: Path, report_date: str | None = None) -> Path:
    stamp = report_date or datetime.now().strftime("%Y-%m-%d_%H%M%S")
    return output_dir / f"featured_candidates_resumes_{stamp}.pdf"


def export_featured_resume_pdf(
    *,
    output_path: Path,
    candidates: Iterable[ReportCandidate],
    review_candidates: Iterable[ReportCandidate] = (),
    cumulative_review_candidates: Iterable[ReportCandidate] = (),
    other_candidates: Iterable[ReportCandidate] = (),
    recollection_candidates: Iterable[ReportCandidate] = (),
    experience_by_fingerprint: dict[str, str],
    resume_link_by_fingerprint: dict[str, str] | None = None,
    interaction_note_by_fingerprint: dict[str, str] | None = None,
    job_name: str | None = None,
) -> Path | None:
    candidate_list = list(candidates)
    review_candidate_list = list(review_candidates)
    cumulative_review_candidate_list = list(cumulative_review_candidates)
    other_candidate_list = list(other_candidates)
    recollection_candidate_list = list(recollection_candidates)
    resume_links = resume_link_by_fingerprint or {}
    interaction_notes = interaction_note_by_fingerprint or {}
    if not any(
        (
            candidate_list,
            review_candidate_list,
            cumulative_review_candidate_list,
            other_candidate_list,
            recollection_candidate_list,
        )
    ):
        return None

    output_path.parent.mkdir(parents=True, exist_ok=True)
    _register_pdf_fonts()
    styles = _build_styles()
    doc = SimpleDocTemplate(
        str(output_path),
        pagesize=A4,
        leftMargin=14 * mm,
        rightMargin=14 * mm,
        topMargin=14 * mm,
        bottomMargin=8 * mm,
        title=f"{job_name}候选人简历" if job_name else "匹配候选人简历",
        author="Liepin RPA",
    )

    story: list[object] = []
    sections = [
        ("明确匹配简历", candidate_list),
        ("本批次待人工复核简历", review_candidate_list),
        ("历史累计待人工复核简历", cumulative_review_candidate_list),
        ("其他已分析简历", other_candidate_list),
        ("需重新采集简历", recollection_candidate_list),
    ]
    nonempty_sections = [(title, items) for title, items in sections if items]
    for section_index, (section_title, section_candidates) in enumerate(nonempty_sections):
        displayed_section_title = (
            f"{job_name}｜{section_title}" if job_name else section_title
        )
        story.append(Paragraph(displayed_section_title, styles["section_title"]))
        story.append(Spacer(1, 5))
        total = len(section_candidates)
        for index, candidate in enumerate(section_candidates, start=1):
            experience = (
                experience_by_fingerprint.get(candidate.candidate_fingerprint) or ""
            ).strip()
            resume_link = resume_links.get(candidate.candidate_fingerprint)
            story.extend(
                _build_candidate_story(
                    candidate,
                    experience,
                    styles,
                    index=index,
                    total=total,
                    resume_link=resume_link,
                    interaction_note=interaction_notes.get(candidate.candidate_fingerprint),
                )
            )
            if index < total:
                story.append(PageBreak())
        if section_index < len(nonempty_sections) - 1:
            story.append(PageBreak())

    doc.build(story)
    return output_path


def _register_pdf_fonts() -> None:
    if PDF_FONT_NAME not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(PDF_FONT_NAME, str(PDF_FONT_PATH)))
    if PDF_BOLD_FONT_NAME not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(PDF_BOLD_FONT_NAME, str(PDF_BOLD_FONT_PATH)))


def _build_styles() -> dict[str, ParagraphStyle]:
    sample = getSampleStyleSheet()
    return {
        "section_title": ParagraphStyle(
            "ResumePdfSectionTitle",
            parent=sample["Title"],
            fontName=PDF_BOLD_FONT_NAME,
            fontSize=20,
            leading=28,
            alignment=0,
            textColor=colors.HexColor("#1D4ED8"),
            spaceAfter=10,
        ),
        "title": ParagraphStyle(
            "ResumePdfTitle",
            parent=sample["Title"],
            fontName=PDF_BOLD_FONT_NAME,
            fontSize=16,
            leading=22,
            alignment=0,
            textColor=colors.HexColor("#1F2937"),
            spaceAfter=6,
        ),
        "meta": ParagraphStyle(
            "ResumePdfMeta",
            parent=sample["Normal"],
            fontName=PDF_FONT_NAME,
            fontSize=9.5,
            leading=14,
            textColor=colors.HexColor("#374151"),
            spaceAfter=2,
        ),
        "resume_link": ParagraphStyle(
            "ResumePdfLink",
            parent=sample["Normal"],
            fontName=PDF_FONT_NAME,
            fontSize=10,
            leading=15,
            textColor=colors.HexColor("#2563EB"),
            spaceBefore=3,
            spaceAfter=5,
        ),
        "section": ParagraphStyle(
            "ResumePdfSection",
            parent=sample["Heading2"],
            fontName=PDF_BOLD_FONT_NAME,
            fontSize=12,
            leading=17,
            textColor=colors.HexColor("#111827"),
            spaceBefore=10,
            spaceAfter=5,
        ),
        "body": ParagraphStyle(
            "ResumePdfBody",
            parent=sample["Normal"],
            fontName=PDF_FONT_NAME,
            fontSize=10,
            leading=15,
            textColor=colors.HexColor("#111827"),
            firstLineIndent=0,
            spaceAfter=4,
        ),
        "bullet": ParagraphStyle(
            "ResumePdfBullet",
            parent=sample["Normal"],
            fontName=PDF_FONT_NAME,
            fontSize=9.6,
            leading=14,
            leftIndent=8,
            firstLineIndent=-8,
            textColor=colors.HexColor("#111827"),
            spaceAfter=3,
        ),
    }


def _build_candidate_story(
    candidate: ReportCandidate,
    experience: str,
    styles: dict[str, ParagraphStyle],
    *,
    index: int,
    total: int,
    resume_link: str | None = None,
    interaction_note: str | None = None,
) -> list[object]:
    score_text = str(candidate.match_score) if candidate.match_score is not None else "未评分"
    age_text = f"{candidate.candidate_age}岁" if candidate.candidate_age is not None else "无"
    keywords_text = " / ".join(sorted(filter(None, candidate.search_keywords))) or "无"
    role_text = " / ".join(sorted(filter(None, candidate.role_keywords))) or "无"
    recent_work = (
        candidate.recent_experience_summary
        or (candidate.evidence_summary[0] if candidate.evidence_summary else "无")
    )
    matched_work = (
        candidate.matched_experience_summary
        or "；".join(candidate.evidence_summary[:3])
        or "无"
    )
    reason_text = " ".join((candidate.analysis_reason or "无").split())

    story: list[object] = [
        Paragraph(f"{index}/{total}  {candidate.candidate_name}", styles["title"]),
        Paragraph(
            f"年龄：{age_text}　分数：{score_text}　状态：{_format_candidate_status(candidate)}",
            styles["meta"],
        ),
        Paragraph(f"关键词：{keywords_text}", styles["meta"]),
        Paragraph(f"岗位：{role_text}", styles["meta"]),
        Paragraph(f"最近一段经历：{recent_work}", styles["meta"]),
        Paragraph(f"匹配经历：{matched_work}", styles["meta"]),
    ]
    if interaction_note:
        story.append(
            Paragraph(
                f"猎聘互动提示（非姓名）：{_escape(interaction_note)}",
                styles["meta"],
            )
        )
    if resume_link:
        safe_link = html_escape(resume_link, quote=True)
        story.append(
            Paragraph(
                f'猎聘简历：<link href="{safe_link}" color="#2563EB"><u>点击查看猎聘原始简历</u></link>',
                styles["resume_link"],
            )
        )
    story.extend(
        [
            Spacer(1, 3),
            HRFlowable(
                width="100%",
                thickness=0.6,
                color=colors.HexColor("#D1D5DB"),
                spaceAfter=7,
            ),
            Paragraph("分析结论", styles["section"]),
            Paragraph(_escape(reason_text), styles["body"]),
            Paragraph("简历原文", styles["section"]),
        ]
    )
    if experience.strip():
        story.extend(_build_resume_body(experience, styles))
    else:
        story.append(
            Paragraph(
                "本次历史采集未留存完整简历正文；请依据上方分析结论和证据摘要进行人工复核。",
                styles["body"],
            )
        )
    return story


def _build_resume_body(experience: str, styles: dict[str, ParagraphStyle]) -> list[object]:
    if not experience.strip():
        return [Paragraph("无", styles["body"])]

    blocks: list[object] = []
    for section_title, section_text in _split_resume_sections(experience):
        if section_title:
            blocks.append(Paragraph(section_title, styles["section"]))
        section_items = _split_resume_items(section_text)
        if not section_items:
            continue
        section_story = [
            Paragraph(_escape(item), styles["bullet"] if _looks_like_item(item) else styles["body"])
            for item in section_items
        ]
        blocks.append(KeepTogether(section_story[:3]))
        blocks.extend(section_story[3:])
    return blocks


def _split_resume_sections(experience: str) -> list[tuple[str | None, str]]:
    normalized = _normalize_resume_text(experience)
    sections: list[tuple[str | None, str]] = []
    markers = ("工作内容：", "项目内容：")
    remaining = normalized
    while remaining:
        marker_positions = [(marker, remaining.find(marker)) for marker in markers if marker in remaining]
        marker_positions = [(marker, pos) for marker, pos in marker_positions if pos >= 0]
        if not marker_positions:
            if remaining.strip():
                sections.append((None, remaining.strip()))
            break
        marker, pos = min(marker_positions, key=lambda item: item[1])
        if pos > 0:
            prefix = remaining[:pos].strip()
            if prefix:
                sections.append((None, prefix))
        after_marker = remaining[pos + len(marker) :]
        next_positions = [after_marker.find(next_marker) for next_marker in markers if next_marker in after_marker]
        next_positions = [next_pos for next_pos in next_positions if next_pos >= 0]
        if next_positions:
            next_pos = min(next_positions)
            sections.append((marker.rstrip("："), after_marker[:next_pos].strip()))
            remaining = after_marker[next_pos:]
        else:
            sections.append((marker.rstrip("："), after_marker.strip()))
            break
    return sections


def _split_resume_items(text: str) -> list[str]:
    normalized = _normalize_resume_text(text)
    rough_items = []
    for line in normalized.splitlines():
        line = line.strip()
        if not line:
            continue
        rough_items.extend(item.strip() for item in line.split("；") if item.strip())
    return rough_items


def _normalize_resume_text(text: str) -> str:
    return (
        text.replace("\\n", "\n")
        .replace("；", "；\n")
        .replace("。 ", "。\n")
        .replace("  ", " ")
        .strip()
    )


def _looks_like_item(text: str) -> bool:
    return len(text) < 180 or text[:2].isdigit() or text.startswith(("·", "-", "•", "1）", "2）", "3）"))


def _format_hit_status(hit_status: str) -> str:
    if hit_status in {"sent", "message_sent"}:
        return "已打招呼"
    if hit_status == "immediate_job_confirmed":
        return "已确认打招呼岗位"
    if hit_status == "skipped_recent_continue":
        return "7天内已打过招呼"
    if hit_status == "send_failed:message_sending_disabled":
        return "待沟通"
    if hit_status.startswith("send_failed:"):
        return "未打招呼"
    return hit_status or "未知"


def _format_candidate_status(candidate: ReportCandidate) -> str:
    if has_analysis_execution_failure(candidate.analysis_reason, execution_status=candidate.execution_status):
        if candidate.execution_status == "blocked":
            return "AI请求被拒绝，待处理"
        if not _has_needs_review_result(candidate):
            return "AI分析失败，待复核"
    if _requires_recollection(candidate):
        return "需重新采集"
    if _requires_analysis_retry(candidate):
        return "AI分析重试"
    if is_actionable_needs_review_result(
        candidate.match_result,
        candidate.match_score,
        analysis_status=candidate.analysis_status,
        analysis_reason=candidate.analysis_reason,
        execution_status=candidate.execution_status,
    ):
        return "待人工复核"
    if _has_needs_review_result(candidate):
        return f"不匹配（未达{ACTIONABLE_NEEDS_REVIEW_MIN_SCORE}分待复核线）"
    if is_not_relevant_result(
        candidate.match_result,
        analysis_status=candidate.analysis_status,
    ):
        return "不匹配"
    if is_relevant_result(
        candidate.match_result,
        analysis_status=candidate.analysis_status,
    ):
        if (
            is_sent_hit_status(candidate.hit_status)
            or candidate.hit_status in {"immediate_job_confirmed", "skipped_recent_continue"}
            or candidate.hit_status.startswith("send_failed:")
        ):
            return _format_hit_status(candidate.hit_status)
        return "匹配未打招呼"
    return _format_hit_status(candidate.hit_status)


def _requires_recollection(candidate: ReportCandidate) -> bool:
    return (
        _is_coze_api_failure(candidate)
        and not candidate.recent_experience_summary
        and not candidate.evidence_summary
    )


def _requires_analysis_retry(candidate: ReportCandidate) -> bool:
    return _is_coze_api_failure(candidate) and not _requires_recollection(candidate)


def _is_coze_api_failure(candidate: ReportCandidate) -> bool:
    return bool(
        _has_needs_review_result(candidate)
        and has_analysis_execution_failure(candidate.analysis_reason, execution_status=candidate.execution_status)
    )


def _has_needs_review_result(candidate: ReportCandidate) -> bool:
    return has_needs_review_result(
        candidate.match_result,
        analysis_status=candidate.analysis_status,
    )


def _escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
