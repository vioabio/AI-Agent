from __future__ import annotations

from dataclasses import replace
from datetime import datetime

from apps.hr_review_api.domain.models import CandidateSummary
from liepin_rpa.candidate_identity import (
    candidate_identity_key,
    resume_identity_similarity,
)
from liepin_rpa.communication_status_policy import communication_status_priority

MIN_RESUME_IDENTITY_SIMILARITY = 0.9

REVIEW_STATUS_PRIORITY = {
    "pending_review": 0,
    "recommended_contact": 1,
    "contacted": 2,
    "interviewing": 3,
    "resume_rejected": 4,
    "interview_rejected": 4,
    "hired": 5,
}


def deduplicate_candidate_summaries(
    candidates: tuple[CandidateSummary, ...],
) -> tuple[CandidateSummary, ...]:
    """合并有双重强证据的历史双指纹，同时保留查询原有排序。"""

    result: list[CandidateSummary] = []
    grouped_indexes: dict[tuple[str, str, int, str, str], list[int]] = {}
    for candidate in candidates:
        identity_key = candidate_identity_key(
            candidate_name=candidate.candidate_name,
            candidate_card_text=candidate.identity_card_text,
            job_profile_id=candidate.job_profile_id,
            age=candidate.age,
            source_account_alias=candidate.identity_source_account_alias,
        )
        if identity_key is None:
            result.append(candidate)
            continue

        matched_index = next(
            (
                index
                for index in grouped_indexes.get(identity_key, [])
                if _same_resume_identity(result[index], candidate)
            ),
            None,
        )
        if matched_index is None:
            grouped_indexes.setdefault(identity_key, []).append(len(result))
            result.append(candidate)
            continue
        result[matched_index] = _merge_candidate_summaries(result[matched_index], candidate)
    return tuple(result)


def _same_resume_identity(first: CandidateSummary, second: CandidateSummary) -> bool:
    return (
        resume_identity_similarity(
            first.identity_experience,
            second.identity_experience,
            first_name=first.candidate_name,
            second_name=second.candidate_name,
        )
        >= MIN_RESUME_IDENTITY_SIMILARITY
    )


def _merge_candidate_summaries(
    first: CandidateSummary,
    second: CandidateSummary,
) -> CandidateSummary:
    preferred = max((first, second), key=_candidate_preference)
    communication = max(
        (first.communication_status, second.communication_status),
        key=communication_status_priority,
    )
    return replace(
        preferred,
        communication_status=communication,
        has_share_link=first.has_share_link or second.has_share_link,
        updated_at=max(
            (first.updated_at, second.updated_at),
            key=_sortable_updated_at,
        ),
    )


def _candidate_preference(candidate: CandidateSummary) -> tuple[int, datetime, int, int]:
    return (
        REVIEW_STATUS_PRIORITY.get(candidate.review_status, 0),
        _sortable_updated_at(candidate.updated_at),
        candidate.match_score if candidate.match_score is not None else -1,
        len(candidate.identity_experience or ""),
    )


def _sortable_updated_at(value: datetime | None) -> datetime:
    return value or datetime.min
