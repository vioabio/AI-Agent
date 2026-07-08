from __future__ import annotations

from pathlib import Path

from liepin_rpa.task_generation.config_loader import load_brand_keywords, load_role_keywords
from liepin_rpa.task_generation.models import SearchTaskSeed


class FrozenKeywordCatalog:
    def __init__(self, brands_path: Path, roles_path: Path) -> None:
        self._brand_keywords = {item.raw_keyword for item in load_brand_keywords(brands_path)}
        self._role_keywords = {item.raw_title for item in load_role_keywords(roles_path)}

    def validate_seed(self, seed: SearchTaskSeed) -> None:
        if seed.brand_keyword not in self._brand_keywords:
            raise ValueError(f"Brand keyword is not in the frozen catalog: {seed.brand_keyword}")
        if seed.role_keyword not in self._role_keywords:
            raise ValueError(f"Role keyword is not in the frozen catalog: {seed.role_keyword}")

    @staticmethod
    def format_exact_query(seed: SearchTaskSeed) -> str:
        return f"{seed.role_keyword} {seed.brand_keyword}"
