from __future__ import annotations

from typing import Literal

CommunicationStatus = Literal["sent", "previously_contacted", "failed", "not_sent"]

SENT_HIT_STATUSES = frozenset({"sent", "immediate_job_confirmed"})
PREVIOUSLY_CONTACTED_HIT_STATUSES = frozenset(
    {
        "skipped_recent_continue",
        "continue_without_db_record",
        "already_contacted",
        "previously_contacted",
        "historically_contacted",
    }
)
PREVIOUSLY_CONTACTED_PREFIXES = (
    "already_contacted:",
    "previously_contacted:",
    "historically_contacted:",
)
FAILED_HIT_STATUSES = frozenset(
    {
        "communication_button_not_found",
        "job_selection_not_handled",
        "post_job_selection_chat_not_detected",
    }
)
FAILED_HIT_STATUS_PREFIXES = ("send_failed:", "failed:", "error:")
FAILED_HIT_STATUS_SUFFIXES = ("_failed", "_error")
FAILED_HIT_STATUS_MARKERS = ("failed", "error")

_STATUS_PRIORITY: dict[CommunicationStatus, int] = {
    "not_sent": 0,
    "failed": 1,
    "previously_contacted": 2,
    "sent": 3,
}


def normalize_communication_status(value: object) -> CommunicationStatus:
    """将采集流水原始状态归一为 HR 页面稳定的四态。"""

    status = str(value or "").strip().lower()
    if status in SENT_HIT_STATUSES:
        return "sent"
    if status in PREVIOUSLY_CONTACTED_HIT_STATUSES or status.startswith(
        PREVIOUSLY_CONTACTED_PREFIXES
    ):
        return "previously_contacted"
    if (
        status in FAILED_HIT_STATUSES
        or status.startswith(FAILED_HIT_STATUS_PREFIXES)
        or status.endswith(FAILED_HIT_STATUS_SUFFIXES)
        or any(marker in status for marker in FAILED_HIT_STATUS_MARKERS)
    ):
        return "failed"
    return "not_sent"


def is_sent_hit_status(value: object) -> bool:
    """返回原始命中状态是否代表真实沟通已经成功。"""

    return normalize_communication_status(value) == "sent"


def communication_status_priority(value: object) -> int:
    """历史命中合并时，成功沟通不得被后续普通浏览或失败记录降级。"""

    return _STATUS_PRIORITY[normalize_communication_status(value)]


def prefer_communication_hit_status(current: object, candidate: object) -> str:
    """在两条原始命中状态中选择能保留更多沟通事实的一条。"""

    current_value = str(current or "")
    candidate_value = str(candidate or "")
    if communication_status_priority(candidate_value) >= communication_status_priority(
        current_value
    ):
        return candidate_value
    return current_value
