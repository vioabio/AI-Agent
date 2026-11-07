from __future__ import annotations

from dataclasses import replace

from sqlalchemy import Engine

from apps.hr_review_api.domain.access_policy import can_view_all_jobs
from apps.hr_review_api.domain.errors import (
    AuthorizationError,
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
from apps.hr_review_api.repositories.candidate_repository import CandidateRepository, JOB_TITLES
from liepin_rpa.reporting_rules import ACTIONABLE_MIN_SCORE
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.resume_evidence import build_snapshot
from liepin_rpa.retrieval.lexical import LexicalRetriever
from liepin_rpa.retrieval.plans import load_query_plan

JOB_PROFILE_IDS = frozenset(JOB_TITLES)


class CandidateQueryService:
    """编排岗位隔离、可选批次窗口和候选人查询口径。"""

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
    ) -> CandidateDetail:
        allowed_jobs = self.allowed_jobs(user)
        completed_batch_ids = self.completed_batch_ids()
        with self.engine.connect() as connection:
            detail = self.candidates.get_candidate_detail(
                connection,
                candidate_fingerprint=candidate_fingerprint,
                allowed_job_profile_ids=allowed_jobs,
                allowed_batch_ids=completed_batch_ids,
                include_sensitive_link=user.can_view_sensitive,
            )
            if detail is None:
                raise CandidateNotFoundError("候选人不存在或无岗位访问权限")
        return detail

    def get_history(
        self,
        *,
        user: AuthenticatedUser,
        candidate_fingerprint: str,
    ) -> tuple[ReviewEvent, ...]:
        completed_batch_ids = self.completed_batch_ids()
        with self.engine.connect() as connection:
            history = self.candidates.list_review_history(
                connection,
                candidate_fingerprint=candidate_fingerprint,
                allowed_job_profile_ids=self.allowed_jobs(user),
                allowed_batch_ids=completed_batch_ids,
            )
        if not history:
            with self.engine.connect() as connection:
                detail = self.candidates.get_candidate_detail(
                    connection,
                    candidate_fingerprint=candidate_fingerprint,
                    allowed_job_profile_ids=self.allowed_jobs(user),
                    allowed_batch_ids=completed_batch_ids,
                    include_sensitive_link=False,
                )
            if detail is None:
                raise CandidateNotFoundError("候选人不存在或无岗位访问权限")
        return history

    def search_evidence(self, *, user: AuthenticatedUser, candidate_fingerprint: str, dimension_id: str) -> dict:
        # No client-selected snapshot IDs, file paths or model calls. Reuse detail authorization.
        detail = self.get_detail(user=user, candidate_fingerprint=candidate_fingerprint)
        unavailable = {"status": "unavailable", "dimension_id": dimension_id,
                       "message": "当前分析没有可检索的原文快照，请重新分析或核对简历。"}
        if detail.evidence_status != "verified" or detail.analysis_snapshot is None:
            return unavailable
        try:
            plan = load_query_plan(detail.summary.job_profile_id or "")
            dimension = next((item for item in plan.dimensions if item.dimension_id == dimension_id), None)
            if dimension is None:
                return unavailable
            snapshot = build_snapshot(candidate_fingerprint, detail.analysis_snapshot["raw_text"])
            if snapshot.snapshot_id != detail.analysis_snapshot["snapshot_id"]:
                return unavailable
            retriever = LexicalRetriever(snapshot)
            hits = retriever.search(dimension.query, candidate_fingerprint=candidate_fingerprint,
                                    snapshot_id=snapshot.snapshot_id, limit=5)
        except (OSError, ValueError):
            return unavailable
        return {
            "status": "available", "analysis_run_id": detail.analysis_run_id,
            "snapshot_id": snapshot.snapshot_id, "dimension_id": dimension_id,
            "dimension_label": dimension.label,
            "retrieval_version": content_hash({"ranker": retriever.version, "plan": plan.version}),
            "hits": [{"chunk_id": hit.chunk.chunk_id, "snapshot_id": hit.chunk.snapshot_id,
                      "start": hit.chunk.start, "end": hit.chunk.end, "quote": hit.chunk.text,
                      "section": hit.chunk.section, "organization": hit.chunk.organization} for hit in hits],
            "message": None if hits else "没有检索到相关片段；这不表示候选人没有相关经历。",
        }

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
        if batch_id:
            self.batches.get(batch_id)
        batch_ids = frozenset() if batch_id else self.completed_batch_ids()
        candidate_scope = criteria.review_scope in {
            "mine",
            "pending",
            "progress",
            "closed",
            "all",
        }
        pending_scope = criteria.review_scope in {"mine", "pending"}
        return replace(
            criteria,
            batch_id=batch_id,
            batch_ids=batch_ids,
            # API 批次查询必须使用持久化批次 ID；时间仅是批次元数据。
            batch_start=None,
            batch_end=None,
            allowed_job_profile_ids=allowed_jobs,
            actionable_match_results=(
                frozenset({"relevant", "needs_review"}) if candidate_scope else frozenset()
            ),
            actionable_min_score=(ACTIONABLE_MIN_SCORE if candidate_scope else None),
            exclude_temporary_ai_failure=candidate_scope,
            # 已处置候选人仍需出现在推进中/已结束；抑制仅移出待办队列。
            exclude_suppressed_report=pending_scope,
            current_user_id=user.id,
        )

    def completed_batch_ids(self) -> frozenset[str]:
        """Return the cumulative completed-batch scope, failing closed when none exists."""

        batch_ids = frozenset(item.batch_id for item in self.batches.list_completed())
        return batch_ids or frozenset({"__no_completed_batch__"})

    @staticmethod
    def allowed_jobs(user: AuthenticatedUser) -> frozenset[str]:
        if can_view_all_jobs(user.role):
            return JOB_PROFILE_IDS
        # 空权限不能解释为“不筛选”，使用不可能的岗位值确保数据库返回空集。
        return user.allowed_job_profile_ids or frozenset({"__no_job_permission__"})
