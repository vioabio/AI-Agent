from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum


class HRReviewStatus(StrEnum):
    """工作台、正式日报与测试共同使用的唯一 HR 状态定义。"""

    PENDING_REVIEW = "pending_review"
    RECOMMENDED_CONTACT = "recommended_contact"
    CONTACTED = "contacted"
    INTERVIEWING = "interviewing"
    ON_HOLD = "on_hold"
    RESUME_REJECTED = "resume_rejected"
    INTERVIEW_REJECTED = "interview_rejected"
    HIRED = "hired"


HR_REVIEW_STATUS_LABELS = {
    HRReviewStatus.PENDING_REVIEW: "待审核",
    HRReviewStatus.RECOMMENDED_CONTACT: "建议沟通",
    HRReviewStatus.CONTACTED: "已联系",
    HRReviewStatus.INTERVIEWING: "面试中",
    HRReviewStatus.ON_HOLD: "暂缓观察",
    HRReviewStatus.RESUME_REJECTED: "简历淘汰",
    HRReviewStatus.INTERVIEW_REJECTED: "面试淘汰",
    HRReviewStatus.HIRED: "已录用",
}


@dataclass(frozen=True, slots=True)
class RecruitmentProgressSnapshot:
    """日报读取的三套独立口径，禁止把 AI 事实改写为 HR 状态。"""

    ai_relevant: int = 0
    ai_needs_review: int = 0
    ai_not_relevant: int = 0
    actionable_pending: int = 0
    hr_status_counts: dict[str, int] = field(default_factory=dict)
    feedback_candidates_in_window: int = 0
