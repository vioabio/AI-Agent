from __future__ import annotations

import hashlib
from dataclasses import dataclass

from liepin_rpa.task_generation.models import SearchTaskSeed


@dataclass(frozen=True, slots=True)
class BatchTrace:
    parent_batch_id: str
    batch_job_id: str
    search_task_id: str

    def __post_init__(self) -> None:
        if not all(
            value.strip()
            for value in (self.parent_batch_id, self.batch_job_id, self.search_task_id)
        ):
            raise ValueError("batch trace requires parent, job, and task ids")


def stable_batch_job_id(parent_batch_id: str, job_profile_id: str) -> str:
    return _stable_id("job", parent_batch_id, job_profile_id)


def stable_search_task_id(
    parent_batch_id: str,
    job_profile_id: str,
    seed: SearchTaskSeed,
) -> str:
    return _stable_id("task", parent_batch_id, job_profile_id, _seed_key(seed))


def trace_for_seed(
    *,
    parent_batch_id: str,
    batch_job_id: str,
    job_profile_id: str,
    seed: SearchTaskSeed,
) -> BatchTrace:
    expected_job_id = stable_batch_job_id(parent_batch_id, job_profile_id)
    if batch_job_id != expected_job_id:
        raise ValueError("batch job id does not belong to the parent batch and job profile")
    return BatchTrace(
        parent_batch_id=parent_batch_id,
        batch_job_id=batch_job_id,
        search_task_id=stable_search_task_id(parent_batch_id, job_profile_id, seed),
    )


def _stable_id(kind: str, *parts: str) -> str:
    normalized = tuple(part.strip() for part in parts)
    if not all(normalized):
        raise ValueError(f"{kind} id inputs must be non-empty")
    digest = hashlib.sha256("\0".join(normalized).encode("utf-8")).hexdigest()[:40]
    return f"{kind}_{digest}"


def _seed_key(seed: SearchTaskSeed) -> str:
    if seed.strategy == "legacy" and not seed.query_text:
        return f"{seed.brand_keyword}|||{seed.role_keyword}"
    return "|||".join(
        (seed.strategy, seed.job_profile_id or "", seed.search_pack_id or "", seed.effective_query)
    )
