from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from apps.hr_review_api.domain.review_status_policy import ReviewStatus


class CandidateSummaryResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    candidate_fingerprint: str
    candidate_name: str | None
    job_profile_id: str | None
    job_title: str | None
    age: int | None
    match_result: str | None
    analysis_status: str
    match_score: int | None
    current_company_title: str | None
    communication_status: str
    review_status: str
    reviewer_id: int | None
    reviewer_name: str | None
    revision: int
    has_share_link: bool
    updated_at: datetime | None


class CandidatePageResponse(BaseModel):
    items: list[CandidateSummaryResponse]
    page: int
    page_size: int
    total: int


class CandidateSourceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    job_profile_id: str | None
    search_keyword: str
    search_pack_id: str | None
    source_account_alias: str | None
    locator_quality: str
    share_link: str | None
    platform_interaction_note: str | None
    created_at: datetime


class ReviewEventResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    previous_status: str | None
    review_status: str
    feedback: str
    reviewer_id: int
    reviewer_name: str
    revision: int
    created_at: datetime


class ReviewHistoryResponse(BaseModel):
    items: list[ReviewEventResponse]


class CandidateDetailResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    summary: CandidateSummaryResponse
    experience: str | None
    experience_hash: str | None
    analysis_version: str | None
    score_breakdown: list[str]
    analysis_reason: str | None
    evidence_summary: list[str]
    manual_review_feedback: str | None
    sources: list[CandidateSourceResponse]
    history: list[ReviewEventResponse]


class SubmitReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    review_status: ReviewStatus
    feedback: str = Field(min_length=2, max_length=4000)
    revision: int = Field(ge=0)
    assignee_id: int | None = Field(default=None, ge=1)

    @field_validator("feedback", mode="before")
    @classmethod
    def strip_feedback(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value


ReviewScope = Literal["mine", "pending", "progress", "closed", "all"]
CandidateSort = Literal["candidate_name", "match_score", "updated_at", "review_status"]
