from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

from apps.hr_review_api.domain.access_policy import UserRole


@dataclass(frozen=True, slots=True)
class AuthenticatedUser:
    id: int
    username: str
    display_name: str
    role: UserRole
    allowed_job_profile_ids: frozenset[str]
    can_view_sensitive: bool


@dataclass(frozen=True, slots=True)
class SessionIdentity:
    user: AuthenticatedUser
    session_id: int
    expires_at: datetime


@dataclass(frozen=True, slots=True)
class CandidateQueryCriteria:
    batch_id: str | None = None
    batch_ids: frozenset[str] = field(default_factory=frozenset)
    batch_start: datetime | None = None
    batch_end: datetime | None = None
    job_profile_id: str | None = None
    match_result: str | None = None
    min_score: int | None = None
    max_score: int | None = None
    review_status: str | None = None
    reviewer_id: int | None = None
    communication_status: str | None = None
    has_share_link: bool | None = None
    keyword: str | None = None
    allowed_job_profile_ids: frozenset[str] = field(default_factory=frozenset)
    actionable_match_results: frozenset[str] = field(default_factory=frozenset)
    actionable_needs_review_min_score: int | None = None
    exclude_temporary_ai_failure: bool = False
    exclude_suppressed_report: bool = False
    review_scope: str = "all"
    current_user_id: int | None = None
    page: int = 1
    page_size: int = 20
    sort_by: str = "updated_at"
    sort_order: str = "desc"


@dataclass(frozen=True, slots=True)
class CandidateSummary:
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
    updated_at: datetime
    # 仅用于服务端保守身份归并，不进入公开 API schema。
    identity_card_text: str | None
    identity_experience: str | None
    identity_source_account_alias: str | None


@dataclass(frozen=True, slots=True)
class CandidatePage:
    items: tuple[CandidateSummary, ...]
    page: int
    page_size: int
    total: int


@dataclass(frozen=True, slots=True)
class CandidateSource:
    job_profile_id: str | None
    search_keyword: str
    search_pack_id: str | None
    source_account_alias: str | None
    locator_quality: str
    share_link: str | None
    platform_interaction_note: str | None
    created_at: datetime


@dataclass(frozen=True, slots=True)
class ReviewEvent:
    id: int
    previous_status: str | None
    review_status: str
    feedback: str
    reviewer_id: int
    reviewer_name: str
    revision: int
    created_at: datetime


@dataclass(frozen=True, slots=True)
class CandidateDetail:
    summary: CandidateSummary
    experience: str | None
    experience_hash: str | None
    analysis_version: str | None
    score_breakdown: tuple[str, ...]
    analysis_reason: str | None
    evidence_summary: tuple[str, ...]
    manual_review_feedback: str | None
    sources: tuple[CandidateSource, ...]
    history: tuple[ReviewEvent, ...]


@dataclass(frozen=True, slots=True)
class DashboardCounts:
    actionable: int
    pending_review: int
    recommended_contact: int
    contacted: int
    interviewing: int
    rejected: int
    hired: int


@dataclass(frozen=True, slots=True)
class LiveReportCounts:
    ai_relevant: int
    ai_needs_review: int
    ai_not_relevant: int
    actionable_pending: int
    hr_status_counts: dict[str, int]
    feedback_candidates_in_window: int
    generated_at: datetime
