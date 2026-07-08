from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(slots=True)
class BrandKeyword:
    category: str
    raw_keyword: str


@dataclass(slots=True)
class RoleKeyword:
    raw_title: str
    match_type: str
    direction_terms: list[str] = field(default_factory=list)


@dataclass(slots=True)
class SearchTaskSeed:
    brand_keyword: str
    role_keyword: str
    employment_scope: str = "CURRENT_AND_FORMER"
