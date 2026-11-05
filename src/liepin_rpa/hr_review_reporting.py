from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum


class HRReviewStatus(StrEnum):
    PENDING_REVIEW = "pending_review"
    RECOMMENDED_CONTACT = "recommended_contact"
    CONTACTED = "contacted"
    INTERVIEWING = "interviewing"
    ON_HOLD = "on_hold"
    RESUME_REJECTED = "resume_rejected"
    INTERVIEW_REJECTED = "interview_rejected"
    REJECTED = "rejected"
    HIRED = "hired"


HR_REVIEW_STATUS_LABELS = {
    HRReviewStatus.PENDING_REVIEW.value: "待审核",
    HRReviewStatus.RECOMMENDED_CONTACT.value: "建议沟通",
    HRReviewStatus.CONTACTED.value: "已联系",
    HRReviewStatus.INTERVIEWING.value: "面试中",
    HRReviewStatus.ON_HOLD.value: "暂缓观察",
    HRReviewStatus.RESUME_REJECTED.value: "简历淘汰",
    HRReviewStatus.INTERVIEW_REJECTED.value: "面试淘汰",
    HRReviewStatus.REJECTED.value: "已淘汰",
    HRReviewStatus.HIRED.value: "已录用",
}


@dataclass(frozen=True, slots=True)
class RecruitmentProgressSnapshot:
    ai_relevant: int = 0
    ai_needs_review: int = 0
    ai_not_relevant: int = 0
    actionable_pending: int = 0
    hr_status_counts: dict[str, int] = field(default_factory=dict)
    feedback_candidates_in_window: int = 0
