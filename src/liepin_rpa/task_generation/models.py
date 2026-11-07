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
    query_text: str | None = None
    strategy: str = "legacy"
    job_profile_id: str | None = None
    search_pack_id: str | None = None
    brand_tier: str | None = None

    @property
    def effective_query(self) -> str:
        if self.query_text and self.query_text.strip():
            return self.query_text.strip()
        return " ".join(
            part for part in (self.role_keyword.strip(), self.brand_keyword.strip()) if part
        )
