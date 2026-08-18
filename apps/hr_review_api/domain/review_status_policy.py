from __future__ import annotations

from apps.hr_review_api.domain.errors import InvalidReviewTransitionError
from liepin_rpa.hr_review_reporting import HRReviewStatus

ReviewStatus = HRReviewStatus


TERMINAL_REVIEW_STATUSES = frozenset(
    {
        ReviewStatus.RESUME_REJECTED,
        ReviewStatus.INTERVIEW_REJECTED,
        ReviewStatus.HIRED,
    }
)

PROGRESS_REVIEW_STATUSES = frozenset(
    {
        ReviewStatus.RECOMMENDED_CONTACT,
        ReviewStatus.CONTACTED,
        ReviewStatus.INTERVIEWING,
        ReviewStatus.ON_HOLD,
    }
)


def parse_review_status(value: str | None) -> ReviewStatus:
    """把边界字符串转换为唯一 HR 状态定义，未知值快速失败。"""

    if value is None:
        return ReviewStatus.PENDING_REVIEW
    try:
        return ReviewStatus(value)
    except ValueError as exc:
        raise InvalidReviewTransitionError(f"未知 HR 状态：{value}") from exc


def validate_review_transition(
    previous_status: str | None,
    new_status: ReviewStatus,
) -> None:
    """校验人工状态修订。

    PRD 明确允许负责人纠正误操作，因此所有已知状态之间都可修订；历史只能
    通过追加事件保留。这里仍解析旧状态，避免损坏的历史值被静默覆盖。
    """

    parse_review_status(previous_status)
    if not isinstance(new_status, ReviewStatus):
        raise InvalidReviewTransitionError("审核状态不合法")


def suppresses_automation(status: ReviewStatus) -> bool:
    """任何正式人工处置都抑制后续自动沟通和 AI 待办重复出现。"""

    return status in ReviewStatus


def is_terminal_status(status: str | None) -> bool:
    return parse_review_status(status) in TERMINAL_REVIEW_STATUSES
