from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml

from liepin_rpa.analysis_versioning import content_hash

DIMENSIONS = ("industry_depth", "beauty_continuity", "role_evidence")
DEFAULT_PATH = Path(__file__).resolve().parents[3] / "config/retrieval_profiles.yaml"


@dataclass(frozen=True)
class DimensionQuery:
    dimension_id: str
    label: str
    query: str


@dataclass(frozen=True)
class QueryPlan:
    job_profile_id: str
    version: str
    dimensions: tuple[DimensionQuery, ...]


def load_query_plan(job_profile_id: str, *, path: Path = DEFAULT_PATH) -> QueryPlan:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict) or not isinstance(document.get("profiles"), dict):
        raise ValueError("Invalid retrieval profile configuration")
    entry = document["profiles"].get(job_profile_id)
    if not isinstance(entry, dict) or set(entry) != set(DIMENSIONS):
        raise ValueError("No complete query plan for this job")
    queries = []
    for dimension in DIMENSIONS:
        item = entry[dimension]
        if not isinstance(item, dict) or any(not isinstance(item.get(key), str) or not item[key].strip() for key in ("label", "query")):
            raise ValueError("Invalid dimension query")
        if len(item["query"]) > 256:
            raise ValueError("Dimension query is too long")
        queries.append(DimensionQuery(dimension, item["label"], item["query"]))
    version = content_hash({"version": document.get("version"), "job": job_profile_id, "queries": entry})
    return QueryPlan(job_profile_id, version, tuple(queries))
