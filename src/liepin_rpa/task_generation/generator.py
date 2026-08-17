from __future__ import annotations

from pathlib import Path

from liepin_rpa.task_generation.config_loader import (
    load_brand_keywords,
    load_job_profile,
    load_role_keywords,
)
from liepin_rpa.task_generation.models import SearchTaskSeed


DEFAULT_EMPLOYMENT_SCOPE = "CURRENT_AND_FORMER"
PRIORITY_ROLE_KEYWORDS = (
    "达人营销总监",
    "媒介总监",
    "直播电商负责人",
    "达人运营总监",
    "达人运营负责人",
    "KOL商务BD",
    "内容营销总监",
    "新媒体营销总监",
    "电商总经理",
    "整合营销总监",
    "直播负责人",
    "抖音负责人",
    "直播推广负责人",
    "BD负责人",
    "BD总监",
)


def generate_search_task_seeds(
    brands_path: Path,
    roles_path: Path,
    employment_scope: str = DEFAULT_EMPLOYMENT_SCOPE,
) -> list[SearchTaskSeed]:
    brands = load_brand_keywords(brands_path)
    roles = sorted(
        enumerate(load_role_keywords(roles_path)),
        key=lambda item: (_role_priority(item[1].raw_title), item[0]),
    )
    return [
        SearchTaskSeed(
            brand_keyword=brand.raw_keyword,
            role_keyword=role.raw_title,
            employment_scope=employment_scope,
        )
        for _, role in roles
        for brand in brands
    ]


def generate_jd_smart_search_task_seeds(
    job_profile_path: Path,
    employment_scope: str = DEFAULT_EMPLOYMENT_SCOPE,
) -> list[SearchTaskSeed]:
    profile = load_job_profile(job_profile_path)
    profile_id = str(profile["id"])
    job_title = str(profile["title"])
    pack_seed_groups: list[list[SearchTaskSeed]] = []
    seen_queries: set[str] = set()
    for raw_pack in profile["search_packs"]:  # type: ignore[index]
        pack = dict(raw_pack)
        pack_id = str(pack["id"])
        role_label = str(pack.get("label") or job_title)
        terms = _string_list(pack.get("terms"))
        brands = _string_list(pack.get("brands"))
        if bool(pack.get("include_unbranded", True)):
            brands = [""] + brands
        pack_seeds: list[SearchTaskSeed] = []
        for brand in brands:
            for term in terms:
                query_text = " ".join(part for part in (term, brand) if part).strip()
                normalized_query = " ".join(query_text.casefold().split())
                if not normalized_query or normalized_query in seen_queries:
                    continue
                seen_queries.add(normalized_query)
                pack_seeds.append(
                    SearchTaskSeed(
                        brand_keyword=brand,
                        role_keyword=role_label,
                        employment_scope=employment_scope,
                        query_text=query_text,
                        strategy="jd_smart",
                        job_profile_id=profile_id,
                        search_pack_id=pack_id,
                        brand_tier=(
                            "unbranded" if not brand else str(pack.get("brand_tier") or "A")
                        ),
                    )
                )
        if pack_seeds:
            pack_seed_groups.append(pack_seeds)
    if str(profile.get("search_pack_scheduling") or "").strip().lower() == "interleaved":
        seeds = _round_robin(pack_seed_groups)
    else:
        seeds = [seed for group in pack_seed_groups for seed in group]
    if not seeds:
        raise ValueError(f"Job profile generated no search tasks: {job_profile_path}")
    return seeds


def _round_robin(groups: list[list[SearchTaskSeed]]) -> list[SearchTaskSeed]:
    """Interleave search packs while preserving each pack's internal priority."""

    if not groups:
        return []
    return [
        group[index]
        for index in range(max(len(group) for group in groups))
        for group in groups
        if index < len(group)
    ]


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def _role_priority(role_keyword: str) -> int:
    try:
        return PRIORITY_ROLE_KEYWORDS.index(role_keyword)
    except ValueError:
        return len(PRIORITY_ROLE_KEYWORDS)
