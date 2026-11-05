"""Count explicit self-reported role intervals, never generic employer tenure."""
import re
import unicodedata

SKILLS = ("python_backend", "java_backend", "ai_deployment")
ROLES = {"python_backend": {"负责python后端开发", "独立负责python后端开发"},
         "java_backend": {"负责java后端开发", "独立负责java后端开发"},
         "ai_deployment": {"负责ai部署", "独立负责ai部署"}}
INTERVAL = re.compile(r"(?:项目)?职责区间:\s*(\d{4}[.-]\d{1,2})\s*-\s*(\d{4}[.-]\d{1,2}|至今)\s*;\s*职责:\s*(.+?)\s*[。.]?$")


def month_id(value):
    match = re.fullmatch(r"(\d{4})[.-](\d{1,2})", value)
    if not match:
        raise ValueError("A source month in YYYY-MM format is required")
    year, month = map(int, match.groups())
    if not 1900 <= year <= 2100 or not 1 <= month <= 12:
        raise ValueError("Invalid source month")
    return year * 12 + month - 1


def calculate_intervals(snapshot, skill, as_of_month):
    if skill not in SKILLS:
        raise ValueError("Unknown interval skill")
    as_of = month_id(as_of_month)
    known, unknown, offset = [], [], 0
    for original in snapshot.raw_text.splitlines(keepends=True):
        normalized = unicodedata.normalize("NFKC", original.strip())
        span = {"start": offset, "end": offset + len(original), "quote": original}
        offset += len(original)
        if not normalized.startswith(("职责区间:", "项目职责区间:")):
            continue  # A company date range does not prove a role interval.
        match = INTERVAL.fullmatch(normalized)
        if not match:
            unknown.append({**span, "reason": "unparsed_explicit_interval"})
            continue
        start, end, role = match.groups()
        role = re.sub(r"\s+", "", role.lower()).rstrip("。.")
        role_skill = next((key for key, values in ROLES.items() if role in values), None)
        if role_skill is not None and role_skill != skill:
            continue
        if role_skill is None:
            unknown.append({**span, "reason": "role_not_explicit"})
            continue
        try:
            first, last = month_id(start), as_of if end == "至今" else month_id(end)
            if first > last or last > as_of:
                raise ValueError("Invalid chronology")
        except ValueError:
            unknown.append({**span, "reason": "invalid_or_future_dates"})
            continue
        known.append({**span, "first": first, "stop": last + 1})
    merged = []
    for first, stop in sorted((item["first"], item["stop"]) for item in known):
        if merged and first <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], stop)
        else:
            merged.append([first, stop])
    count = sum(stop - first for first, stop in merged)
    return {"snapshot_id": snapshot.snapshot_id, "skill": skill, "as_of_month": as_of_month,
            "status": "partial" if known and unknown else "known_declared_intervals" if known else "unknown",
            "covered_calendar_months": count if known else None,
            "known_full_calendar_months_bounds": [max(0, count - 2 * len(known)), count] if known else None,
            "merged_month_ranges": merged, "source_spans": known, "unknown_source_spans": unknown,
            "scope": "self_reported_explicit_role_intervals_only", "verified_employment_years": None,
            "note": "仅汇总明确自述的职责区间；月份边界不等于完整月，不证明真实任职或能力。"}
