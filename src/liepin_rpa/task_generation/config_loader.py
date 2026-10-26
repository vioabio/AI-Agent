from __future__ import annotations

from pathlib import Path

import yaml

from liepin_rpa.task_generation.models import BrandKeyword, RoleKeyword


class FrozenKeywordConfigError(RuntimeError):
    """Raised when frozen keyword config is malformed."""


def load_job_profile(path: Path) -> dict[str, object]:
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(payload, dict):
        raise FrozenKeywordConfigError("Job profile config must be a YAML object")
    required = ("id", "title", "search_packs", "card_screening")
    missing = [name for name in required if not payload.get(name)]
    if missing:
        raise FrozenKeywordConfigError(f"Job profile config is missing: {missing}")
    search_packs = payload.get("search_packs")
    if not isinstance(search_packs, list) or not search_packs:
        raise FrozenKeywordConfigError("Job profile search_packs must be a non-empty list")
    pack_ids = [str(item.get("id") or "") for item in search_packs if isinstance(item, dict)]
    if len(pack_ids) != len(search_packs) or any(not item for item in pack_ids):
        raise FrozenKeywordConfigError("Every job profile search pack must have an id")
    _validate_unique_values(pack_ids, "search pack")
    return dict(payload)


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
