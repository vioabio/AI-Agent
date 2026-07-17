from __future__ import annotations

from pathlib import Path

from liepin_rpa.task_generation.config_loader import load_brand_keywords, load_role_keywords
from liepin_rpa.task_generation.models import SearchTaskSeed


DEFAULT_EMPLOYMENT_SCOPE = "CURRENT_AND_FORMER"
PRIORITY_ROLE_KEYWORDS = (
    "达播总监",
    "达人营销总监",
    "全域营销总监",
    "全域增长负责人",
    "新媒体营销总监",
    "电商总监",
    "媒介总监",
    "整合营销总监",
    "直播业务总监",
    "电商运营总监（达人方向）",
    "直播业务负责人",
    "营销负责人",
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


def _role_priority(role_keyword: str) -> int:
    try:
        return PRIORITY_ROLE_KEYWORDS.index(role_keyword)
    except ValueError:
        return len(PRIORITY_ROLE_KEYWORDS)
