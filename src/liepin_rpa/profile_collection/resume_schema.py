from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any

DATE_RANGE_PATTERN = re.compile(
    r"(?P<start>\d{4}(?:\.\d{1,2})?)-(?P<end>至今|\d{4}(?:\.\d{1,2})?)(?:\((?P<duration>[^)]*)\))?"
)
EDUCATION_DATE_PATTERN = re.compile(r"\d{4}\.\d{1,2}-\d{4}\.\d{1,2}")
AGE_PATTERN = re.compile(r"(\d{1,2})岁")
WORK_YEARS_PATTERN = re.compile(r"工作(\d{1,2})年")
ACHIEVEMENT_HINT_PATTERN = re.compile(
    r"(GMV|TOP\d+|破\d+|亿|万|增长|\d+%|团队|销售额|年薪|单场|单链接)",
    re.IGNORECASE,
)
SPLIT_PATTERN = re.compile(r"[；;。！？!?]")
SECTION_MARKERS = {
    "求职意向",
    "简历亮点",
    "附件简历",
    "工作经历",
    "教育经历",
    "语言能力",
    "个人信息",
    "技能标签",
}
IGNORED_LINES = {
    "询问TA",
    "继续沟通",
    "立即沟通",
    "获取电话",
    "展开全部工作经历",
    "展开全部教育经历",
    "展开全部",
}
DEGREE_HINTS = ("本科", "大专", "硕士", "博士", "MBA", "EMBA", "统招")


def build_candidate_resume_schema(
    *,
    debug_payload: dict[str, Any],
    output_dir: Path,
    search_brand_keyword: str | None = None,
    search_role_keyword: str | None = None,
    search_task_id: str | None = None,
) -> dict[str, Any]:
    sections = {
        str(key): [normalize_text(item) for item in value if normalize_text(item)]
        for key, value in debug_payload.get("sections", {}).items()
    }
    collected_lines = [
        normalize_text(item) for item in debug_payload.get("collected_text_lines", []) if normalize_text(item)
    ]
    iterations = debug_payload.get("iterations", [])

    basic_info = parse_basic_info(sections)
    experiences, experience_warnings = parse_work_experiences(collected_lines, iterations)
    education_experiences, education_warnings = parse_education_experiences(collected_lines)
    skills = parse_skills(sections, basic_info.get("self_description"))
    candidate_id = build_candidate_id(basic_info)

    screenshots = [str(Path(item["screenshot_path"])) for item in iterations if item.get("screenshot_path")]
    xml_files = [str(Path(item["hierarchy_path"])) for item in iterations if item.get("hierarchy_path")]
    warnings = experience_warnings + education_warnings
    parse_status = "SUCCESS"
    if warnings:
        parse_status = "PARTIAL"
    if not experiences:
        parse_status = "FAILED"

    return {
        "schema_version": "1.0",
        "candidate_id": candidate_id,
        "source": {
            "platform": "liepin",
            "collected_at": datetime.now().astimezone().isoformat(timespec="seconds"),
            "search_task_id": search_task_id,
            "search_brand_keyword": search_brand_keyword,
            "search_role_keyword": search_role_keyword,
        },
        "basic_info": {
            "name": basic_info.get("name"),
            "gender": None,
            "age": basic_info.get("age"),
            "city": basic_info.get("city"),
            "work_years": basic_info.get("work_years"),
            "education": basic_info.get("education"),
            "current_company": basic_info.get("current_company"),
            "current_position": basic_info.get("current_position"),
            "expected_position": basic_info.get("expected_position"),
            "resume_updated_at": None,
        },
        "work_experiences": experiences,
        "education_experiences": education_experiences,
        "skills": skills,
        "self_description": basic_info.get("self_description"),
        "raw_resume": {
            "full_text": "\n".join(collected_lines),
            "screenshots": screenshots,
            "xml_files": xml_files,
        },
        "parse_metadata": {
            "parse_status": parse_status,
            "parser_version": "resume-parser-1.0.0",
            "warnings": warnings,
            "requires_manual_review": parse_status != "SUCCESS",
        },
    }


def parse_basic_info(sections: dict[str, list[str]]) -> dict[str, Any]:
    header = sections.get("_profile_header", [])
    intent = sections.get("求职意向", [])

    second_line = header[1] if len(header) > 1 else ""
    company, position = split_company_and_position(second_line)

    return {
        "name": value_or_none(header[0] if len(header) > 0 else None),
        "current_company": company,
        "current_position": position,
        "city": value_or_none(header[2] if len(header) > 2 else None),
        "education": value_or_none(header[3] if len(header) > 3 else None),
        "work_years": parse_first_int(WORK_YEARS_PATTERN, header[4] if len(header) > 4 else ""),
        "age": parse_first_int(AGE_PATTERN, header[5] if len(header) > 5 else ""),
        "expected_position": value_or_none(intent[0] if intent else None),
        "self_description": value_or_none(" ".join(header[7:]) if len(header) > 7 else None),
    }


def parse_work_experiences(
    lines: list[str],
    iterations: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[str]]:
    experiences: list[dict[str, Any]] = []
    warnings: list[str] = []
    used_period_indexes: set[int] = set()

    for index, line in enumerate(lines):
        if not DATE_RANGE_PATTERN.search(line):
            continue
        if looks_like_education_period(index, lines):
            continue
        previous_values = previous_meaningful_lines(lines, index, 2)
        if len(previous_values) < 2:
            warnings.append(f"工作经历时间 `{line}` 前缺少公司或职位。")
            continue
        company_line, position_line = previous_values[-2], previous_values[-1]
        if company_line in SECTION_MARKERS or position_line in SECTION_MARKERS:
            continue

        raw_description_lines: list[str] = []
        cursor = index + 1
        while cursor < len(lines):
            candidate = normalize_text(lines[cursor])
            if not candidate or candidate in SECTION_MARKERS:
                cursor += 1
                continue
            if DATE_RANGE_PATTERN.search(candidate) and not looks_like_education_period(cursor, lines):
                break
            if candidate in IGNORED_LINES:
                cursor += 1
                continue
            if looks_like_next_experience_preamble(cursor, lines):
                break
            raw_description_lines.append(candidate)
            cursor += 1

        if index in used_period_indexes:
            continue
        used_period_indexes.add(index)
        raw_description = " ".join(raw_description_lines).strip()
        period = parse_employment_period(line)
        evidence = locate_evidence(iterations, [company_line, position_line, line])

        experiences.append(
            {
                "experience_id": f"exp_{len(experiences) + 1:03d}",
                "sequence": len(experiences) + 1,
                "company": {
                    "raw_name": company_line,
                    "normalized_name": normalize_text(company_line),
                },
                "position": {
                    "raw_title": position_line,
                    "normalized_title": normalize_text(position_line),
                },
                "employment_period": period,
                "department": None,
                "industry": None,
                "responsibilities": split_into_points(raw_description, achievements=False),
                "achievements": split_into_points(raw_description, achievements=True),
                "raw_description": value_or_none(raw_description),
                "source_evidence": evidence,
            }
        )

    if not experiences:
        warnings.append("未识别到可结构化的工作经历。")
    return experiences, dedupe_preserve_order(warnings)


def parse_education_experiences(lines: list[str]) -> tuple[list[dict[str, Any]], list[str]]:
    items: list[dict[str, Any]] = []
    warnings: list[str] = []

    for index, line in enumerate(lines):
        if not EDUCATION_DATE_PATTERN.search(line):
            continue
        previous_values = previous_meaningful_lines(lines, index, 3)
        if not previous_values:
            continue
        school_name = previous_values[-3] if len(previous_values) >= 3 else previous_values[0]
        major_or_degree = previous_values[-1]
        if not any(hint in major_or_degree for hint in DEGREE_HINTS) and not any(
            hint in school_name for hint in DEGREE_HINTS
        ):
            continue
        start_date, end_date = parse_simple_date_range(line)
        degree = detect_degree(" ".join(previous_values[-2:]))
        major = extract_major(previous_values[-2:])
        items.append(
            {
                "education_id": f"edu_{len(items) + 1:03d}",
                "school_name": value_or_none(school_name),
                "major": value_or_none(major),
                "degree": value_or_none(degree),
                "start_date": start_date,
                "end_date": end_date,
                "raw_text": " ".join(previous_values[-3:] + [line]).strip(),
            }
        )

    if not items:
        warnings.append("未识别到可结构化的教育经历。")
    return items, warnings


def parse_skills(sections: dict[str, list[str]], self_description: str | None) -> list[str]:
    skills: list[str] = []
    for item in sections.get("语言能力", []):
        if item not in skills and item not in SECTION_MARKERS:
            skills.append(item)
    for item in sections.get("技能标签", []):
        if item not in skills and item not in SECTION_MARKERS:
            skills.append(item)
    if self_description:
        for keyword in ("达人直播", "团队管理", "战略规划", "业务拓展", "社群团购", "抖音电商"):
            if keyword in self_description and keyword not in skills:
                skills.append(keyword)
    return skills


def build_candidate_id(basic_info: dict[str, Any]) -> str:
    material = "|".join(
        [
            str(basic_info.get("name") or ""),
            str(basic_info.get("current_company") or ""),
            str(basic_info.get("current_position") or ""),
            str(basic_info.get("city") or ""),
            str(basic_info.get("work_years") or ""),
        ]
    )
    digest = hashlib.md5(material.encode("utf-8")).hexdigest()[:8]
    return f"candidate_{digest}"


def split_company_and_position(value: str) -> tuple[str | None, str | None]:
    parts = [normalize_text(part) for part in re.split(r"[·•]", value) if normalize_text(part)]
    if not parts:
        return None, None
    if len(parts) == 1:
        return parts[0], None
    return parts[0], parts[-1]


def parse_employment_period(value: str) -> dict[str, Any]:
    match = DATE_RANGE_PATTERN.search(value)
    if not match:
        return {
            "raw_text": value,
            "start_date": None,
            "end_date": None,
            "is_current": None,
            "duration_months": None,
        }
    start_date = normalize_date_token(match.group("start"))
    end_token = match.group("end")
    end_date = None if end_token == "至今" else normalize_date_token(end_token)
    duration_months = parse_duration_months(match.group("duration"))
    return {
        "raw_text": value,
        "start_date": start_date,
        "end_date": end_date,
        "is_current": end_token == "至今",
        "duration_months": duration_months,
    }


def parse_simple_date_range(value: str) -> tuple[str | None, str | None]:
    match = EDUCATION_DATE_PATTERN.search(value)
    if not match:
        return None, None
    start_raw, end_raw = match.group(0).split("-")
    return normalize_date_token(start_raw), normalize_date_token(end_raw)


def parse_duration_months(value: str | None) -> int | None:
    if not value:
        return None
    years = 0
    months = 0
    year_match = re.search(r"(\d+)年", value)
    month_match = re.search(r"(\d+)个?月", value)
    if year_match:
        years = int(year_match.group(1))
    if month_match:
        months = int(month_match.group(1))
    total = years * 12 + months
    return total or None


def normalize_date_token(value: str | None) -> str | None:
    if not value:
        return None
    value = value.strip()
    if not value:
        return None
    parts = value.split(".")
    if len(parts) == 1:
        return parts[0]
    return f"{parts[0]}-{int(parts[1]):02d}"


def split_into_points(raw_description: str, *, achievements: bool) -> list[str]:
    if not raw_description:
        return []
    points = [
        normalize_text(part)
        for part in SPLIT_PATTERN.split(raw_description.replace("·", "；"))
        if normalize_text(part)
    ]
    selected: list[str] = []
    for point in points:
        looks_like_achievement = bool(ACHIEVEMENT_HINT_PATTERN.search(point))
        if achievements and looks_like_achievement:
            selected.append(point)
        if not achievements and not looks_like_achievement:
            selected.append(point)
    return dedupe_preserve_order(selected)


def locate_evidence(iterations: list[dict[str, Any]], tokens: list[str]) -> dict[str, Any]:
    cleaned_tokens = [token for token in tokens if token]
    for iteration in iterations:
        visible_lines = [normalize_text(item) for item in iteration.get("visible_text_lines", [])]
        haystack = " ".join(visible_lines)
        if all(token in haystack for token in cleaned_tokens[:2]):
            return {
                "page_index": iteration.get("iteration"),
                "screenshot_path": iteration.get("screenshot_path"),
                "xml_path": iteration.get("hierarchy_path"),
            }
    first = iterations[0] if iterations else {}
    return {
        "page_index": first.get("iteration"),
        "screenshot_path": first.get("screenshot_path"),
        "xml_path": first.get("hierarchy_path"),
    }


def previous_meaningful_lines(lines: list[str], index: int, count: int) -> list[str]:
    values: list[str] = []
    cursor = index - 1
    while cursor >= 0 and len(values) < count:
        candidate = normalize_text(lines[cursor])
        if candidate and candidate not in IGNORED_LINES:
            values.append(candidate)
        cursor -= 1
    values.reverse()
    return values


def looks_like_education_period(index: int, lines: list[str]) -> bool:
    previous_values = previous_meaningful_lines(lines, index, 3)
    joined = " ".join(previous_values)
    return any(hint in joined for hint in DEGREE_HINTS)


def looks_like_next_experience_preamble(index: int, lines: list[str]) -> bool:
    current = normalize_text(lines[index])
    next_line = normalize_text(lines[index + 1]) if index + 1 < len(lines) else ""
    return (
        current not in SECTION_MARKERS
        and next_line not in SECTION_MARKERS
        and index + 2 < len(lines)
        and DATE_RANGE_PATTERN.search(normalize_text(lines[index + 2])) is not None
    )


def detect_degree(value: str) -> str | None:
    for degree in ("博士", "硕士", "MBA/EMBA", "MBA", "EMBA", "本科", "大专", "专科", "中专"):
        if degree in value:
            return degree
    return None


def extract_major(values: list[str]) -> str | None:
    for value in values:
        cleaned = normalize_text(value)
        if any(hint in cleaned for hint in DEGREE_HINTS):
            parts = [part.strip() for part in cleaned.split("·") if part.strip()]
            if len(parts) >= 2:
                return parts[-1] if detect_degree(parts[0]) else parts[0]
            if " " in cleaned:
                tokens = [item for item in cleaned.split() if item]
                if len(tokens) >= 2:
                    return tokens[-1]
    return None


def parse_first_int(pattern: re.Pattern[str], value: str) -> int | None:
    match = pattern.search(value)
    return int(match.group(1)) if match else None


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    return " ".join(str(value).split())


def value_or_none(value: str | None) -> str | None:
    normalized = normalize_text(value)
    return normalized or None


def dedupe_preserve_order(items: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


def build_candidate_resume_from_debug_path(
    debug_report_path: Path,
    *,
    search_brand_keyword: str | None = None,
    search_role_keyword: str | None = None,
    search_task_id: str | None = None,
) -> dict[str, Any]:
    payload = json.loads(debug_report_path.read_text(encoding="utf-8"))
    return build_candidate_resume_schema(
        debug_payload=payload,
        output_dir=debug_report_path.parent,
        search_brand_keyword=search_brand_keyword,
        search_role_keyword=search_role_keyword,
        search_task_id=search_task_id,
    )
