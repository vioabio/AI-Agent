from __future__ import annotations

from collections.abc import Callable
from dataclasses import replace
from datetime import datetime, timedelta, timezone

from sqlalchemy import Engine

from apps.hr_review_api.domain.errors import BatchNotFoundError
from apps.hr_review_api.domain.models import (
    AuthenticatedUser,
    CandidateQueryCriteria,
    DashboardCounts,
    LiveReportCounts,
)
from apps.hr_review_api.domain.review_status_policy import ReviewStatus
from apps.hr_review_api.repositories.batch_repository import BatchRepository
from apps.hr_review_api.repositories.candidate_repository import CandidateRepository
from apps.hr_review_api.services.candidate_query_service import CandidateQueryService

SHANGHAI_TIMEZONE = timezone(timedelta(hours=8))


class DashboardService:
    """生成指标卡和三套口径分离的日报实时预览。"""

    def __init__(
        self,
        engine: Engine,
        candidates: CandidateRepository,
        query_service: CandidateQueryService,
        batches: BatchRepository,
        now_provider: Callable[[], datetime] | None = None,
    ) -> None:
        self.engine = engine
        self.candidates = candidates
        self.query_service = query_service
        self.batches = batches
        self._now_provider = now_provider or (lambda: datetime.now(SHANGHAI_TIMEZONE))

    def get_summary(
        self,
        *,
        user: AuthenticatedUser,
        batch_id: str | None,
        job_profile_id: str | None,
        reviewer_id: int | None,
    ) -> DashboardCounts:
        base = CandidateQueryCriteria(
            job_profile_id=job_profile_id,
            reviewer_id=reviewer_id,
            review_scope="all",
            page_size=1,
        )
        resolved = self.query_service.build_criteria(user=user, criteria=base, batch_id=batch_id)
        actionable = self.query_service.build_criteria(
            user=user,
            criteria=replace(base, review_scope="pending"),
            batch_id=batch_id,
        )
        with self.engine.connect() as connection:
            counts = {
                status.value: self.candidates.count_candidates(
                    connection,
                    replace(resolved, review_status=status.value),
                )
                for status in ReviewStatus
            }
            actionable_count = self.candidates.count_candidates(connection, actionable)
        return DashboardCounts(
            actionable=actionable_count,
            pending_review=actionable_count,
            recommended_contact=counts[ReviewStatus.RECOMMENDED_CONTACT.value],
            contacted=counts[ReviewStatus.CONTACTED.value],
            interviewing=counts[ReviewStatus.INTERVIEWING.value],
            rejected=(
                counts[ReviewStatus.RESUME_REJECTED.value]
                + counts[ReviewStatus.INTERVIEW_REJECTED.value]
            ),
            hired=counts[ReviewStatus.HIRED.value],
        )

    def get_live_preview(
        self,
        *,
        user: AuthenticatedUser,
        batch_id: str | None,
        job_profile_id: str | None,
    ) -> LiveReportCounts:
        report_batch_id = self._report_batch_id(batch_id)
        raw_base = self.query_service.build_criteria(
            user=user,
            criteria=CandidateQueryCriteria(
                job_profile_id=job_profile_id,
                # 原始三态用于观察 AI 分析分布，不套用候选人准入规则。
                review_scope="raw",
                page_size=1,
            ),
            batch_id=report_batch_id,
        )
        candidate_base = self.query_service.build_criteria(
            user=user,
            criteria=CandidateQueryCriteria(
                job_profile_id=job_profile_id,
                review_scope="all",
                page_size=1,
            ),
            batch_id=report_batch_id,
        )
        actionable = self.query_service.build_criteria(
            user=user,
            criteria=CandidateQueryCriteria(
                job_profile_id=job_profile_id,
                review_scope="pending",
                page_size=1,
            ),
            batch_id=report_batch_id,
        )
        with self.engine.connect() as connection:
            ai_counts = {
                result: self.candidates.count_candidates(
                    connection,
                    replace(raw_base, match_result=result),
                )
                for result in ("relevant", "needs_review", "not_relevant")
            }
            hr_counts = {
                status.value: self.candidates.count_candidates(
                    connection,
                    replace(candidate_base, review_status=status.value),
                )
                for status in ReviewStatus
            }
            actionable_count = self.candidates.count_candidates(connection, actionable)
            hr_counts[ReviewStatus.PENDING_REVIEW.value] = actionable_count
            feedback_count = self.candidates.count_feedback_candidates(
                connection,
                since=None,
                before=None,
                allowed_job_profile_ids=candidate_base.allowed_job_profile_ids,
                job_profile_id=candidate_base.job_profile_id,
                batch_id=report_batch_id,
            )
        return LiveReportCounts(
            ai_relevant=ai_counts["relevant"],
            ai_needs_review=ai_counts["needs_review"],
            ai_not_relevant=ai_counts["not_relevant"],
            actionable_pending=actionable_count,
            hr_status_counts=hr_counts,
            feedback_candidates_in_window=feedback_count,
            generated_at=self._now_provider(),
        )

    def _report_batch_id(self, requested: str | None) -> str:
        if requested:
            return requested
        batches = self.batches.list_completed()
        if not batches:
            raise BatchNotFoundError("没有可用于日报预览的已完成搜索批次")
        return batches[0].batch_id
