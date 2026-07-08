from __future__ import annotations

from pathlib import Path

from liepin_rpa.task_generation.config_loader import load_brand_keywords, load_role_keywords
from liepin_rpa.task_generation.models import SearchTaskSeed


DEFAULT_EMPLOYMENT_SCOPE = "CURRENT_AND_FORMER"


def generate_search_task_seeds(
    brands_path: Path,
    roles_path: Path,
    employment_scope: str = DEFAULT_EMPLOYMENT_SCOPE,
) -> list[SearchTaskSeed]:
    brands = load_brand_keywords(brands_path)
    roles = load_role_keywords(roles_path)
    return [
        SearchTaskSeed(
            brand_keyword=brand.raw_keyword,
            role_keyword=role.raw_title,
            employment_scope=employment_scope,
        )
        for brand in brands
        for role in roles
    ]
