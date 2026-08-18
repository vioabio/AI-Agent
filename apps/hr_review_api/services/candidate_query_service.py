from __future__ import annotations

from dataclasses import replace
from datetime import datetime

from sqlalchemy import Engine

from apps.hr_review_api.domain.access_policy import can_view_all_jobs
from apps.hr_review_api.domain.errors import (
    AuthorizationError,
    BatchNotFoundError,
    CandidateNotFoundError,
)
from apps.hr_review_api.domain.models import (
    AuthenticatedUser,
    CandidateDetail,
    CandidatePage,
    CandidateQueryCriteria,
    ReviewEvent,
)
from apps.hr_review_api.repositories.batch_repository import BatchRepository
from apps.hr_review_api.repositories.candidate_repository import CandidateRepository
from liepin_rpa.reporting_rules import ACTIONABLE_NEEDS_REVIEW_MIN_SCORE

JOB_PROFILE_IDS = frozenset({"daren_live_bd_v1", "finance_shared_management_v1"})


class CandidateQueryService:
    """编排岗位隔离、批次窗口和候选人查询口径。"""

    def __init__(
        self,
        engine: Engine,
        candidate_repository: CandidateRepository,
        batch_repository: BatchRepository,
    ) -> None:
        self.engine = engine
        self.candidates = candidate_repository
        self.batches = batch_repository

    def list_candidates(
        self,
        *,
        user: AuthenticatedUser,
        criteria: CandidateQueryCriteria,
        batch_id: str | None,
    ) -> CandidatePage:
        resolved = self.build_criteria(user=user, criteria=criteria, batch_id=batch_id)
        with self.engine.connect() as connection:
            return self.candidates.list_candidates(connection, resolved)

    def get_detail(
        self,
        *,
        user: AuthenticatedUser,
        candidate_fingerprint: str,
        client_ip: str,
        user_agent: str,
        now: datetime,
    ) -> CandidateDetail:
        allowed_jobs = self.allowed_jobs(user)
        # 完整简历和正式链接均为敏感访问，审计事件与读取成功在同一事务提交。
        with self.engine.begin() as connection:
            detail = self.candidates.get_candidate_detail(
                connection,
                candidate_fingerprint=candidate_fingerprint,
                allowed_job_profile_ids=allowed_jobs,
                include_sensitive_link=user.can_view_sensitive,
            )
            if detail is None:
                raise CandidateNotFoundError("候选人不存在或无岗位访问权限")
            self.candidates.add_sensitive_access_event(
                connection,
                candidate_fingerprint=candidate_fingerprint,
                user_id=user.id,
                access_kind="detail_with_resume",
                client_ip=client_ip,
                user_agent=user_agent,
                created_at=now,
            )
        return detail

    def get_history(
        self,
        *,
        user: AuthenticatedUser,
        candidate_fingerprint: str,
    ) -> tuple[ReviewEvent, ...]:
        with self.engine.connect() as connection:
            history = self.candidates.list_review_history(
                connection,
                candidate_fingerprint=candidate_fingerprint,
                allowed_job_profile_ids=self.allowed_jobs(user),
            )
        if not history:
            with self.engine.connect() as connection:
                detail = self.candidates.get_candidate_detail(
                    connection,
                    candidate_fingerprint=candidate_fingerprint,
                    allowed_job_profile_ids=self.allowed_jobs(user),
                    include_sensitive_link=False,
                )
            if detail is None:
                raise CandidateNotFoundError("候选人不存在或无岗位访问权限")
        return history

    def build_criteria(
        self,
        *,
        user: AuthenticatedUser,
        criteria: CandidateQueryCriteria,
        batch_id: str | None,
    ) -> CandidateQueryCriteria:
        allowed_jobs = self.allowed_jobs(user)
        if criteria.job_profile_id and criteria.job_profile_id not in allowed_jobs:
            raise AuthorizationError("无权查看该岗位候选人")
        available_batches = self.batches.list_completed()
        if batch_id:
            batch = self.batches.get(batch_id)
        elif available_batches:
            batch = available_batches[0]
        else:
            raise BatchNotFoundError("没有可用的已完成搜索批次")
        actionable_scope = criteria.review_scope in {"mine", "pending"}
        return replace(
            criteria,
            batch_start=batch.started_at,
            batch_end=batch.ended_at,
            allowed_job_profile_ids=allowed_jobs,
            actionable_match_results=(
                frozenset({"relevant", "needs_review"}) if actionable_scope else frozenset()
            ),
            actionable_needs_review_min_score=(
                ACTIONABLE_NEEDS_REVIEW_MIN_SCORE if actionable_scope else None
            ),
            exclude_temporary_ai_failure=actionable_scope,
            exclude_suppressed_report=actionable_scope,
            current_user_id=user.id,
        )

    @staticmethod
    def allowed_jobs(user: AuthenticatedUser) -> frozenset[str]:
        if can_view_all_jobs(user.role):
            return JOB_PROFILE_IDS
        # 空权限不能解释为“不筛选”，使用不可能的岗位值确保数据库返回空集。
        return user.allowed_job_profile_ids or frozenset({"__no_job_permission__"})
