from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import Connection, Engine

from apps.hr_review_api.domain.access_policy import (
    can_assign_other_reviewer,
    can_submit_review,
)
from apps.hr_review_api.domain.errors import (
    AuthorizationError,
    CandidateNotFoundError,
    ReviewConflictError,
)
from apps.hr_review_api.domain.models import AuthenticatedUser, CandidateDetail
from apps.hr_review_api.domain.review_status_policy import (
    ReviewStatus,
    suppresses_automation,
    validate_review_transition,
)
from apps.hr_review_api.repositories.candidate_repository import CandidateRepository
from apps.hr_review_api.services.candidate_query_service import CandidateQueryService


@dataclass(frozen=True, slots=True)
class SubmitReviewCommand:
    candidate_fingerprint: str
    review_status: ReviewStatus
    feedback: str
    revision: int
    assignee_id: int | None
    client_ip: str
    user_agent: str
    submitted_at: datetime


class ReviewService:
    """原子提交 HR 状态快照、乐观锁和不可删除的审核事件。"""

    def __init__(
        self,
        engine: Engine,
        repository: CandidateRepository,
        query_service: CandidateQueryService,
    ) -> None:
        self.engine = engine
        self.repository = repository
        self.query_service = query_service

    def submit(self, *, user: AuthenticatedUser, command: SubmitReviewCommand) -> CandidateDetail:
        if not can_submit_review(user.role):
            raise AuthorizationError("当前角色仅可查看，不能提交审核")
        allowed_jobs = self.query_service.allowed_jobs(user)
        with self.engine.begin() as connection:
            snapshot = self.repository.get_review_snapshot_for_update(
                connection,
                candidate_fingerprint=command.candidate_fingerprint,
                allowed_job_profile_ids=allowed_jobs,
            )
            if snapshot is None:
                raise CandidateNotFoundError("候选人不存在或无岗位访问权限")
            current_revision = int(snapshot["manual_review_revision"] or 0)
            if current_revision != command.revision:
                raise ReviewConflictError("数据已被其他人修改，请重新加载")
            previous_status = (
                str(snapshot["manual_review_status"])
                if snapshot["manual_review_status"] is not None
                else None
            )
            validate_review_transition(previous_status, command.review_status)
            suppress_automation = suppresses_automation(command.review_status)
            assignee_id = self._resolve_assignee(
                connection=connection,
                user=user,
                requested=command.assignee_id,
                current=(
                    int(snapshot["manual_review_assignee_id"])
                    if snapshot["manual_review_assignee_id"] is not None
                    else None
                ),
            )
            updated = self.repository.update_review_snapshot(
                connection,
                candidate_fingerprint=command.candidate_fingerprint,
                expected_revision=command.revision,
                review_status=command.review_status.value,
                feedback=command.feedback,
                reviewer_id=user.id,
                assignee_id=assignee_id,
                suppress_automation=suppress_automation,
                reviewed_at=command.submitted_at,
            )
            if not updated:
                raise ReviewConflictError("数据已被其他人修改，请重新加载")
            self.repository.add_review_event(
                connection,
                candidate_fingerprint=command.candidate_fingerprint,
                previous_status=previous_status,
                review_status=command.review_status.value,
                feedback=command.feedback,
                reviewer_id=user.id,
                reviewer_name=user.display_name,
                revision=command.revision + 1,
                suppress_automation=suppress_automation,
                client_ip=command.client_ip,
                user_agent=command.user_agent,
                created_at=command.submitted_at,
            )
            detail = self.repository.get_candidate_detail(
                connection,
                candidate_fingerprint=command.candidate_fingerprint,
                allowed_job_profile_ids=allowed_jobs,
                include_sensitive_link=user.can_view_sensitive,
            )
            if detail is None:
                raise CandidateNotFoundError("候选人更新后无法读取")
            # PATCH 响应包含完整简历及可能的正式链接，读取本身也必须留下敏感访问审计。
            self.repository.add_sensitive_access_event(
                connection,
                candidate_fingerprint=command.candidate_fingerprint,
                user_id=user.id,
                access_kind="review_response_with_resume",
                client_ip=command.client_ip,
                user_agent=command.user_agent,
                created_at=command.submitted_at,
            )
        return detail

    def _resolve_assignee(
        self,
        *,
        connection: Connection,
        user: AuthenticatedUser,
        requested: int | None,
        current: int | None,
    ) -> int:
        assignee_id = requested or current or user.id
        if assignee_id != user.id and not can_assign_other_reviewer(user.role):
            raise AuthorizationError("当前角色不能把候选人分配给其他审核人")
        if not self.repository.reviewer_exists(connection, assignee_id):
            raise AuthorizationError("指定负责人不存在或已停用")
        return assignee_id
