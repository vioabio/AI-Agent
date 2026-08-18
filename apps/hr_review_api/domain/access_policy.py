from __future__ import annotations

from enum import StrEnum


class UserRole(StrEnum):
    HR = "hr"
    RECRUITING_LEAD = "recruiting_lead"
    ADMIN = "admin"
    READ_ONLY = "read_only"


def can_submit_review(role: UserRole) -> bool:
    """只读角色不能产生候选人状态或审计事件。"""

    return role in {UserRole.HR, UserRole.RECRUITING_LEAD, UserRole.ADMIN}


def can_view_all_jobs(role: UserRole) -> bool:
    return role in {UserRole.RECRUITING_LEAD, UserRole.ADMIN}


def can_assign_other_reviewer(role: UserRole) -> bool:
    return role in {UserRole.RECRUITING_LEAD, UserRole.ADMIN}
