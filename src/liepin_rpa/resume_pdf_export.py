from __future__ import annotations

from datetime import datetime
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
    experience_by_fingerprint: dict[str, str],
) -> Path | None:
    candidate_list = list(candidates)
    if not candidate_list:
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
        bottomMargin=14 * mm,
        title="匹配候选人简历",
        author="Liepin RPA",
    )

    story: list[object] = []
    total = len(candidate_list)
    for index, candidate in enumerate(candidate_list, start=1):
        experience = (experience_by_fingerprint.get(candidate.candidate_fingerprint) or "").strip()
        story.extend(_build_candidate_story(candidate, experience, styles, index=index, total=total))
        if index < total:
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
            leading=16,
            textColor=colors.HexColor("#111827"),
            firstLineIndent=0,
            spaceAfter=4,
        ),
        "bullet": ParagraphStyle(
            "ResumePdfBullet",
            parent=sample["Normal"],
            fontName=PDF_FONT_NAME,
            fontSize=9.6,
            leading=15,
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
) -> list[object]:
    score_text = str(candidate.match_score) if candidate.match_score is not None else "未评分"
    age_text = f"{candidate.candidate_age}岁" if candidate.candidate_age is not None else "无"
    keywords_text = " / ".join(sorted(filter(None, candidate.search_keywords))) or "无"
    role_text = " / ".join(sorted(filter(None, candidate.role_keywords))) or "无"
    recent_work = candidate.recent_experience_summary or "无"
    matched_work = candidate.matched_experience_summary or "无"
    reason_text = " ".join((candidate.analysis_reason or "无").split())

    story: list[object] = [
        Paragraph(f"{index}/{total}  {candidate.candidate_name}", styles["title"]),
        Paragraph(
            f"年龄：{age_text}　分数：{score_text}　状态：{_format_hit_status(candidate.hit_status)}",
            styles["meta"],
        ),
        Paragraph(f"关键词：{keywords_text}", styles["meta"]),
        Paragraph(f"岗位：{role_text}", styles["meta"]),
        Paragraph(f"最近一段经历：{recent_work}", styles["meta"]),
        Paragraph(f"匹配经历：{matched_work}", styles["meta"]),
        Spacer(1, 3),
        HRFlowable(width="100%", thickness=0.6, color=colors.HexColor("#D1D5DB"), spaceAfter=7),
        Paragraph("分析结论", styles["section"]),
        Paragraph(_escape(reason_text), styles["body"]),
        Paragraph("简历原文", styles["section"]),
    ]
    story.extend(_build_resume_body(experience, styles))
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
    if hit_status == "sent":
        return "已打招呼"
    if hit_status == "immediate_job_confirmed":
        return "已确认打招呼岗位"
    return hit_status or "未知"


def _escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
