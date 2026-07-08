from __future__ import annotations

from pathlib import Path

import yaml

from liepin_rpa.task_generation.models import BrandKeyword, RoleKeyword


class FrozenKeywordConfigError(RuntimeError):
    """Raised when frozen keyword config is malformed."""


def load_brand_keywords(path: Path) -> list[BrandKeyword]:
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    brands_raw = payload.get("brands", [])
    brands = [
        BrandKeyword(
            category=str(item["category"]),
            raw_keyword=str(item["raw_keyword"]),
        )
        for item in brands_raw
    ]
    _validate_unique_values([brand.raw_keyword for brand in brands], "brand")
    return brands


def load_role_keywords(path: Path) -> list[RoleKeyword]:
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    roles_raw = payload.get("roles", [])
    roles = [
        RoleKeyword(
            raw_title=str(item["raw_title"]),
            match_type=str(item["match_type"]),
            direction_terms=[str(term) for term in item.get("direction_terms", [])],
        )
        for item in roles_raw
    ]
    _validate_unique_values([role.raw_title for role in roles], "role")
    return roles


def _validate_unique_values(values: list[str], label: str) -> None:
    duplicates = sorted({value for value in values if values.count(value) > 1})
    if duplicates:
        raise FrozenKeywordConfigError(
            f"Duplicate {label} keyword(s) found in frozen config: {duplicates}"
        )
