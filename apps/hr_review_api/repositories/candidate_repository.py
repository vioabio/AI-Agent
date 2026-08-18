from __future__ import annotations

import json
import re
from datetime import datetime

from sqlalchemy import (
    Connection,
    Select,
    and_,
    exists,
    func,
    insert,
    or_,
    select,
    update,
)
from sqlalchemy.engine import RowMapping
from sqlalchemy.sql.elements import ColumnElement

from apps.hr_review_api.domain.models import (
    CandidateDetail,
    CandidatePage,
    CandidateQueryCriteria,
    CandidateSource,
    CandidateSummary,
    ReviewEvent,
)
from apps.hr_review_api.persistence.tables import (
    hr_users,
    manual_review_events,
    sensitive_access_events,
)
from liepin_rpa.candidate_decision_rules import (
    ANALYSIS_STATUS_MATCHED,
    ANALYSIS_STATUS_NEEDS_REVIEW,
)
from liepin_rpa.db import (
    resume_analysis_cache,
    resume_experience_records,
    resume_search_hits,
    resume_source_locators,
)

JOB_TITLES = {
    "daren_live_bd_v1": "达人营销与达播负责人",
    "finance_shared_management_v1": "财务共享与管理经理",
}
SORT_COLUMNS = {
    "candidate_name": resume_experience_records.c.candidate_name,
    "match_score": resume_analysis_cache.c.match_score,
    "updated_at": resume_experience_records.c.updated_at,
    "review_status": resume_experience_records.c.manual_review_status,
}
AGE_PATTERN = re.compile(r"(?:年龄|岁数)[:：]?\s*(\d{2})|(?<!\d)(\d{2})岁")
CARD_COMPANY_TITLE_PATTERN = re.compile(
    r"(?P<company>[^\s；;，,·•]{2,40})\s*[·•]\s*(?P<title>[^\s；;，,·•]{2,40})"
)
WORK_BLOCK_PATTERN = re.compile(r"工作内容：(.*?)(?:\s+项目内容：|$)")
CURRENT_PERIOD_PATTERN = re.compile(r"(?:至今|现在|目前|present)", re.IGNORECASE)


class CandidateRepository:
    """候选人查询与人工审核持久化；产品口径由 Service 通过 criteria 提供。"""

    def list_candidates(
        self,
        connection: Connection,
        criteria: CandidateQueryCriteria,
    ) -> CandidatePage:
        statement = self._base_select(criteria)
        total = int(connection.scalar(select(func.count()).select_from(statement.subquery())) or 0)
        sort_column = SORT_COLUMNS[criteria.sort_by]
        ordering = sort_column.asc() if criteria.sort_order == "asc" else sort_column.desc()
        rows = connection.execute(
            statement.order_by(ordering, resume_experience_records.c.id.desc())
            .limit(criteria.page_size)
            .offset((criteria.page - 1) * criteria.page_size)
        ).mappings()
        return CandidatePage(
            items=tuple(self._summary_from_row(row) for row in rows),
            page=criteria.page,
            page_size=criteria.page_size,
            total=total,
        )

    def count_candidates(
        self,
        connection: Connection,
        criteria: CandidateQueryCriteria,
    ) -> int:
        statement = self._base_select(criteria)
        return int(connection.scalar(select(func.count()).select_from(statement.subquery())) or 0)

    def count_feedback_candidates(
        self,
        connection: Connection,
        *,
        since: datetime | None,
        before: datetime | None,
        allowed_job_profile_ids: frozenset[str],
        job_profile_id: str | None = None,
    ) -> int:
        source_filters: list[ColumnElement[bool]] = [
            resume_source_locators.c.candidate_fingerprint
            == manual_review_events.c.candidate_fingerprint
        ]
        source_filters.append(resume_source_locators.c.job_profile_id.in_(allowed_job_profile_ids))
        if job_profile_id:
            source_filters.append(resume_source_locators.c.job_profile_id == job_profile_id)
        source_permission = exists(
            select(1).select_from(resume_source_locators).where(*source_filters)
        )
        filters: list[ColumnElement[bool]] = [source_permission]
        if since is not None:
            filters.append(manual_review_events.c.created_at >= _database_time(since))
        if before is not None:
            filters.append(manual_review_events.c.created_at < _database_time(before))
        value = connection.scalar(
            select(func.count(func.distinct(manual_review_events.c.candidate_fingerprint))).where(
                *filters
            )
        )
        return int(value or 0)

    def get_candidate_detail(
        self,
        connection: Connection,
        *,
        candidate_fingerprint: str,
        allowed_job_profile_ids: frozenset[str],
        include_sensitive_link: bool,
    ) -> CandidateDetail | None:
        criteria = CandidateQueryCriteria(
            allowed_job_profile_ids=allowed_job_profile_ids,
            page_size=1,
        )
        row = (
            connection.execute(
                self._base_select(criteria).where(
                    resume_experience_records.c.candidate_fingerprint == candidate_fingerprint
                )
            )
            .mappings()
            .first()
        )
        if row is None:
            return None
        source_filters: list[ColumnElement[bool]] = [
            resume_source_locators.c.candidate_fingerprint == candidate_fingerprint
        ]
        source_filters.append(resume_source_locators.c.job_profile_id.in_(allowed_job_profile_ids))
        source_rows = connection.execute(
            select(resume_source_locators)
            .where(*source_filters)
            .order_by(
                resume_source_locators.c.created_at.desc(), resume_source_locators.c.id.desc()
            )
        ).mappings()
        history = self.list_review_history(
            connection,
            candidate_fingerprint=candidate_fingerprint,
            allowed_job_profile_ids=allowed_job_profile_ids,
        )
        return CandidateDetail(
            summary=self._summary_from_row(row),
            experience=str(row["experience"]) if row["experience"] is not None else None,
            experience_hash=(
                str(row["experience_hash"]) if row["experience_hash"] is not None else None
            ),
            analysis_version=(
                str(row["analysis_version"]) if row["analysis_version"] is not None else None
            ),
            score_breakdown=_decode_score_breakdown(row["score_breakdown_json"]),
            analysis_reason=(
                str(row["analysis_reason"]) if row["analysis_reason"] is not None else None
            ),
            evidence_summary=_decode_evidence(row["evidence_summary_json"]),
            manual_review_feedback=(
                str(row["manual_review_feedback"])
                if row["manual_review_feedback"] is not None
                else None
            ),
            sources=tuple(
                self._source_from_row(source, include_sensitive_link=include_sensitive_link)
                for source in source_rows
            ),
            history=history,
        )

    def list_review_history(
        self,
        connection: Connection,
        *,
        candidate_fingerprint: str,
        allowed_job_profile_ids: frozenset[str],
    ) -> tuple[ReviewEvent, ...]:
        if not connection.scalar(
            select(
                self._source_exists(
                    candidate_fingerprint=candidate_fingerprint,
                    allowed_job_profile_ids=allowed_job_profile_ids,
                )
            )
        ):
            return ()
        rows = connection.execute(
            select(manual_review_events)
            .where(manual_review_events.c.candidate_fingerprint == candidate_fingerprint)
            .order_by(manual_review_events.c.revision.desc())
        ).mappings()
        return tuple(self._review_event_from_row(row) for row in rows)

    def get_review_snapshot_for_update(
        self,
        connection: Connection,
        *,
        candidate_fingerprint: str,
        allowed_job_profile_ids: frozenset[str],
    ) -> RowMapping | None:
        if not connection.scalar(
            select(
                self._source_exists(
                    candidate_fingerprint=candidate_fingerprint,
                    allowed_job_profile_ids=allowed_job_profile_ids,
                )
            )
        ):
            return None
        return (
            connection.execute(
                select(
                    resume_experience_records.c.candidate_fingerprint,
                    resume_experience_records.c.manual_review_status,
                    resume_experience_records.c.manual_review_revision,
                    resume_experience_records.c.manual_review_assignee_id,
                )
                .where(resume_experience_records.c.candidate_fingerprint == candidate_fingerprint)
                .with_for_update()
            )
            .mappings()
            .first()
        )

    def update_review_snapshot(
        self,
        connection: Connection,
        *,
        candidate_fingerprint: str,
        expected_revision: int,
        review_status: str,
        feedback: str,
        reviewer_id: int,
        assignee_id: int,
        suppress_automation: bool,
        reviewed_at: datetime,
    ) -> bool:
        # revision 条件必须与状态快照更新位于同一 SQL，避免两个请求先后覆盖。
        result = connection.execute(
            update(resume_experience_records)
            .where(
                resume_experience_records.c.candidate_fingerprint == candidate_fingerprint,
                resume_experience_records.c.manual_review_revision == expected_revision,
            )
            .values(
                manual_review_status=review_status,
                manual_review_feedback=feedback,
                manual_suppress_communication=int(suppress_automation),
                manual_suppress_report=int(suppress_automation),
                manual_reviewed_by=reviewer_id,
                manual_review_assignee_id=assignee_id,
                manual_review_revision=expected_revision + 1,
                manual_reviewed_at=_database_time(reviewed_at),
                updated_at=_database_time(reviewed_at),
            )
        )
        return int(result.rowcount or 0) == 1

    def add_review_event(
        self,
        connection: Connection,
        *,
        candidate_fingerprint: str,
        previous_status: str | None,
        review_status: str,
        feedback: str,
        reviewer_id: int,
        reviewer_name: str,
        revision: int,
        suppress_automation: bool,
        client_ip: str,
        user_agent: str,
        created_at: datetime,
    ) -> None:
        # 事件与主表快照由 Service 的同一 engine.begin() 包裹，任一失败整体回滚。
        connection.execute(
            insert(manual_review_events).values(
                candidate_fingerprint=candidate_fingerprint,
                previous_status=previous_status,
                review_status=review_status,
                feedback=feedback,
                reviewer_id=reviewer_id,
                reviewer_name_snapshot=reviewer_name,
                revision=revision,
                suppress_communication=suppress_automation,
                suppress_report=suppress_automation,
                client_ip=client_ip,
                user_agent=user_agent[:255],
                created_at=_database_time(created_at),
            )
        )

    def add_sensitive_access_event(
        self,
        connection: Connection,
        *,
        candidate_fingerprint: str,
        user_id: int,
        access_kind: str,
        client_ip: str,
        user_agent: str,
        created_at: datetime,
    ) -> None:
        connection.execute(
            insert(sensitive_access_events).values(
                candidate_fingerprint=candidate_fingerprint,
                user_id=user_id,
                access_kind=access_kind,
                client_ip=client_ip,
                user_agent=user_agent[:255],
                created_at=_database_time(created_at),
            )
        )

    def reviewer_exists(self, connection: Connection, reviewer_id: int) -> bool:
        return bool(
            connection.scalar(
                select(hr_users.c.id).where(
                    hr_users.c.id == reviewer_id,
                    hr_users.c.is_active.is_(True),
                )
            )
        )

    def _base_select(self, criteria: CandidateQueryCriteria) -> Select[tuple[object, ...]]:
        latest_analysis_ids = (
            select(
                resume_analysis_cache.c.candidate_fingerprint.label("candidate_fingerprint"),
                func.max(resume_analysis_cache.c.id).label("analysis_id"),
            )
            .group_by(resume_analysis_cache.c.candidate_fingerprint)
            .subquery("latest_analysis_ids")
        )
        latest_locator_ids = (
            select(
                resume_source_locators.c.candidate_fingerprint.label("candidate_fingerprint"),
                func.max(resume_source_locators.c.id).label("locator_id"),
            )
            # 必须先收紧到当前用户可见的岗位/批次，再选“最新”。否则同一候选人
            # 后来出现在未授权岗位时，会通过联表泄露该岗位和候选人卡片。
            .where(*self._locator_scope_filters(criteria))
            .group_by(resume_source_locators.c.candidate_fingerprint)
            .subquery("latest_locator_ids")
        )
        latest_hit_ids = (
            select(
                resume_search_hits.c.candidate_fingerprint.label("candidate_fingerprint"),
                func.max(resume_search_hits.c.id).label("hit_id"),
            )
            .where(self._hit_in_locator_scope(criteria))
            .group_by(resume_search_hits.c.candidate_fingerprint)
            .subquery("latest_hit_ids")
        )
        statement = select(
            resume_experience_records.c.candidate_fingerprint,
            resume_experience_records.c.experience_hash,
            resume_experience_records.c.candidate_name,
            resume_experience_records.c.experience,
            resume_experience_records.c.manual_review_status,
            resume_experience_records.c.manual_review_feedback,
            resume_experience_records.c.manual_reviewed_by,
            resume_experience_records.c.manual_review_assignee_id,
            resume_experience_records.c.manual_review_revision,
            resume_experience_records.c.updated_at,
            resume_analysis_cache.c.match_result,
            resume_analysis_cache.c.analysis_status,
            resume_analysis_cache.c.match_score,
            resume_analysis_cache.c.analysis_version,
            resume_analysis_cache.c.score_breakdown_json,
            resume_analysis_cache.c.analysis_reason,
            resume_analysis_cache.c.evidence_summary_json,
            resume_source_locators.c.job_profile_id,
            resume_source_locators.c.candidate_card_text,
            resume_search_hits.c.hit_status,
            hr_users.c.display_name.label("reviewer_name"),
            self._share_link_exists(criteria).label("has_share_link"),
        ).select_from(
            resume_experience_records.outerjoin(
                latest_analysis_ids,
                latest_analysis_ids.c.candidate_fingerprint
                == resume_experience_records.c.candidate_fingerprint,
            )
            .outerjoin(
                resume_analysis_cache,
                resume_analysis_cache.c.id == latest_analysis_ids.c.analysis_id,
            )
            .outerjoin(
                latest_locator_ids,
                latest_locator_ids.c.candidate_fingerprint
                == resume_experience_records.c.candidate_fingerprint,
            )
            .outerjoin(
                resume_source_locators,
                resume_source_locators.c.id == latest_locator_ids.c.locator_id,
            )
            .outerjoin(
                latest_hit_ids,
                latest_hit_ids.c.candidate_fingerprint
                == resume_experience_records.c.candidate_fingerprint,
            )
            .outerjoin(resume_search_hits, resume_search_hits.c.id == latest_hit_ids.c.hit_id)
            .outerjoin(hr_users, hr_users.c.id == resume_experience_records.c.manual_reviewed_by)
        )
        return self._apply_criteria(statement, criteria)

    def _apply_criteria(
        self,
        statement: Select[tuple[object, ...]],
        criteria: CandidateQueryCriteria,
    ) -> Select[tuple[object, ...]]:
        filters: list[ColumnElement[bool]] = [
            self._source_exists(
                batch_start=criteria.batch_start,
                batch_end=criteria.batch_end,
                job_profile_id=criteria.job_profile_id,
                allowed_job_profile_ids=criteria.allowed_job_profile_ids,
            )
        ]
        if criteria.match_result:
            filters.append(resume_analysis_cache.c.match_result == criteria.match_result)
        if criteria.min_score is not None:
            filters.append(resume_analysis_cache.c.match_score >= criteria.min_score)
        if criteria.max_score is not None:
            filters.append(resume_analysis_cache.c.match_score <= criteria.max_score)
        if criteria.review_status:
            filters.append(
                func.coalesce(
                    resume_experience_records.c.manual_review_status,
                    "pending_review",
                )
                == criteria.review_status
            )
        if criteria.reviewer_id is not None:
            filters.append(resume_experience_records.c.manual_reviewed_by == criteria.reviewer_id)
        if criteria.communication_status:
            filters.append(resume_search_hits.c.hit_status == criteria.communication_status)
        if criteria.has_share_link is not None:
            link_exists = self._share_link_exists(criteria)
            filters.append(link_exists if criteria.has_share_link else ~link_exists)
        if criteria.keyword:
            term = f"%{criteria.keyword}%"
            filters.append(
                or_(
                    resume_experience_records.c.candidate_name.like(term),
                    resume_experience_records.c.experience.like(term),
                    self._source_exists(
                        batch_start=criteria.batch_start,
                        batch_end=criteria.batch_end,
                        job_profile_id=criteria.job_profile_id,
                        allowed_job_profile_ids=criteria.allowed_job_profile_ids,
                        keyword=criteria.keyword,
                    ),
                )
            )
        if criteria.actionable_match_results:
            filters.append(self._actionable_expression(criteria))
        if criteria.exclude_suppressed_report:
            filters.append(resume_experience_records.c.manual_suppress_report == 0)
        filters.extend(self._review_scope_filters(criteria))
        return statement.where(*filters)

    @staticmethod
    def _actionable_expression(criteria: CandidateQueryCriteria) -> ColumnElement[bool]:
        result = resume_analysis_cache.c.match_result
        relevant = or_(
            result == "relevant",
            and_(
                result.is_(None), resume_analysis_cache.c.analysis_status == ANALYSIS_STATUS_MATCHED
            ),
        )
        needs_review = or_(
            result == "needs_review",
            and_(
                result.is_(None),
                resume_analysis_cache.c.analysis_status == ANALYSIS_STATUS_NEEDS_REVIEW,
            ),
        )
        if criteria.actionable_needs_review_min_score is None:
            return relevant
        review_filters: list[ColumnElement[bool]] = [
            needs_review,
            resume_analysis_cache.c.match_score >= criteria.actionable_needs_review_min_score,
        ]
        if criteria.exclude_temporary_ai_failure:
            review_filters.append(
                or_(
                    resume_analysis_cache.c.analysis_reason.is_(None),
                    ~resume_analysis_cache.c.analysis_reason.contains("Coze API"),
                )
            )
        return or_(relevant, and_(*review_filters))

    @staticmethod
    def _review_scope_filters(criteria: CandidateQueryCriteria) -> list[ColumnElement[bool]]:
        status = func.coalesce(
            resume_experience_records.c.manual_review_status,
            "pending_review",
        )
        if criteria.review_scope == "mine":
            return [
                status == "pending_review",
                or_(
                    resume_experience_records.c.manual_review_assignee_id.is_(None),
                    resume_experience_records.c.manual_review_assignee_id
                    == criteria.current_user_id,
                ),
            ]
        if criteria.review_scope == "pending":
            return [status == "pending_review"]
        if criteria.review_scope == "progress":
            return [status.in_(("recommended_contact", "contacted", "interviewing", "on_hold"))]
        if criteria.review_scope == "closed":
            return [status.in_(("resume_rejected", "interview_rejected", "hired"))]
        return []

    @staticmethod
    def _source_exists(
        *,
        candidate_fingerprint: str | None = None,
        batch_start: datetime | None = None,
        batch_end: datetime | None = None,
        job_profile_id: str | None = None,
        allowed_job_profile_ids: frozenset[str] = frozenset(),
        keyword: str | None = None,
    ) -> ColumnElement[bool]:
        filters: list[ColumnElement[bool]] = [
            resume_source_locators.c.candidate_fingerprint
            == (
                candidate_fingerprint
                if candidate_fingerprint is not None
                else resume_experience_records.c.candidate_fingerprint
            )
        ]
        if batch_start is not None:
            filters.append(resume_source_locators.c.created_at >= _database_time(batch_start))
        if batch_end is not None:
            filters.append(resume_source_locators.c.created_at < _database_time(batch_end))
        if job_profile_id:
            filters.append(resume_source_locators.c.job_profile_id == job_profile_id)
        # 空权限集合表示无权访问任何岗位，而不是“不筛选”。
        filters.append(resume_source_locators.c.job_profile_id.in_(allowed_job_profile_ids))
        if keyword:
            filters.append(resume_source_locators.c.search_keyword.like(f"%{keyword}%"))
        return exists(
            select(1)
            .select_from(resume_source_locators)
            .where(*filters)
            .correlate(resume_experience_records)
        )

    @staticmethod
    def _locator_scope_filters(
        criteria: CandidateQueryCriteria,
    ) -> list[ColumnElement[bool]]:
        filters: list[ColumnElement[bool]] = [
            resume_source_locators.c.job_profile_id.in_(criteria.allowed_job_profile_ids)
        ]
        if criteria.batch_start is not None:
            filters.append(
                resume_source_locators.c.created_at >= _database_time(criteria.batch_start)
            )
        if criteria.batch_end is not None:
            filters.append(resume_source_locators.c.created_at < _database_time(criteria.batch_end))
        if criteria.job_profile_id:
            filters.append(resume_source_locators.c.job_profile_id == criteria.job_profile_id)
        return filters

    @classmethod
    def _share_link_exists(cls, criteria: CandidateQueryCriteria) -> ColumnElement[bool]:
        return exists(
            select(1)
            .select_from(resume_source_locators)
            .where(
                resume_source_locators.c.candidate_fingerprint
                == resume_experience_records.c.candidate_fingerprint,
                resume_source_locators.c.locator_quality == "share_link",
                resume_source_locators.c.detail_deep_link.is_not(None),
                *cls._locator_scope_filters(criteria),
            )
            .correlate(resume_experience_records)
        )

    @classmethod
    def _hit_in_locator_scope(cls, criteria: CandidateQueryCriteria) -> ColumnElement[bool]:
        """搜索命中表无岗位列，以同候选人和关键词的授权定位作为最小权限边界。"""

        return exists(
            select(1)
            .select_from(resume_source_locators)
            .where(
                resume_source_locators.c.candidate_fingerprint
                == resume_search_hits.c.candidate_fingerprint,
                resume_source_locators.c.search_keyword == resume_search_hits.c.search_keyword,
                *cls._locator_scope_filters(criteria),
            )
            .correlate(resume_search_hits)
        )

    @staticmethod
    def _summary_from_row(row: RowMapping) -> CandidateSummary:
        job_profile_id = str(row["job_profile_id"]) if row["job_profile_id"] else None
        review_status = str(row["manual_review_status"] or "pending_review")
        return CandidateSummary(
            candidate_fingerprint=str(row["candidate_fingerprint"]),
            candidate_name=str(row["candidate_name"]) if row["candidate_name"] else None,
            job_profile_id=job_profile_id,
            job_title=JOB_TITLES.get(job_profile_id) if job_profile_id else None,
            age=_extract_age(row["candidate_card_text"]),
            match_result=str(row["match_result"]) if row["match_result"] else None,
            analysis_status=str(row["analysis_status"] or "未执行"),
            match_score=int(row["match_score"]) if row["match_score"] is not None else None,
            current_company_title=_extract_current_company_title(
                row["candidate_card_text"],
                row["experience"],
            ),
            communication_status=_communication_status(row["hit_status"]),
            review_status=review_status,
            reviewer_id=(int(row["manual_reviewed_by"]) if row["manual_reviewed_by"] else None),
            reviewer_name=str(row["reviewer_name"]) if row["reviewer_name"] else None,
            revision=int(row["manual_review_revision"] or 0),
            has_share_link=bool(row["has_share_link"]),
            updated_at=row["updated_at"],
        )

    @staticmethod
    def _source_from_row(
        row: RowMapping,
        *,
        include_sensitive_link: bool,
    ) -> CandidateSource:
        share_link = None
        if include_sensitive_link and row["locator_quality"] == "share_link":
            share_link = str(row["detail_deep_link"]) if row["detail_deep_link"] else None
        return CandidateSource(
            job_profile_id=str(row["job_profile_id"]) if row["job_profile_id"] else None,
            search_keyword=str(row["search_keyword"]),
            search_pack_id=str(row["search_pack_id"]) if row["search_pack_id"] else None,
            source_account_alias=(
                str(row["source_account_alias"]) if row["source_account_alias"] else None
            ),
            locator_quality=str(row["locator_quality"]),
            share_link=share_link,
            platform_interaction_note=(
                str(row["platform_interaction_note"]) if row["platform_interaction_note"] else None
            ),
            created_at=row["created_at"],
        )

    @staticmethod
    def _review_event_from_row(row: RowMapping) -> ReviewEvent:
        return ReviewEvent(
            id=int(row["id"]),
            previous_status=str(row["previous_status"]) if row["previous_status"] else None,
            review_status=str(row["review_status"]),
            feedback=str(row["feedback"]),
            reviewer_id=int(row["reviewer_id"]),
            reviewer_name=str(row["reviewer_name_snapshot"]),
            revision=int(row["revision"]),
            created_at=row["created_at"],
        )


def _database_time(value: datetime) -> datetime:
    return value.replace(tzinfo=None) if value.tzinfo is not None else value


def _extract_age(value: object) -> int | None:
    if not isinstance(value, str):
        return None
    match = AGE_PATTERN.search(value)
    if match is None:
        return None
    age = int(match.group(1) or match.group(2))
    return age if 16 <= age <= 80 else None


def _extract_current_company_title(card_text: object, experience: object) -> str | None:
    """从展示卡片或首段在职经历中做保守摘要，不猜测缺失字段。"""

    if isinstance(card_text, str):
        card_match = CARD_COMPANY_TITLE_PATTERN.search(card_text)
        if card_match is not None:
            return f"{card_match.group('company')} · {card_match.group('title')}"
    if not isinstance(experience, str):
        return None
    work_match = WORK_BLOCK_PATTERN.search(experience)
    if work_match is None:
        return None
    parts = [part.strip() for part in work_match.group(1).split("；") if part.strip()]
    if len(parts) < 3 or CURRENT_PERIOD_PATTERN.search(parts[2]) is None:
        return None
    company, title = parts[:2]
    if len(company) > 80 or len(title) > 80:
        return None
    return f"{company} · {title}"


def _communication_status(value: object) -> str:
    status = str(value or "")
    if status == "sent":
        return "sent"
    if "already" in status or "contacted" in status:
        return "previously_contacted"
    if "failed" in status or "error" in status:
        return "failed"
    return "not_sent"


def _decode_score_breakdown(value: object) -> tuple[str, ...]:
    payload = _decode_json(value)
    if isinstance(payload, dict):
        return tuple(f"{key}: {item}" for key, item in payload.items())
    if isinstance(payload, list):
        return tuple(str(item) for item in payload)
    return ()


def _decode_evidence(value: object) -> tuple[str, ...]:
    payload = _decode_json(value)
    if isinstance(payload, dict):
        return tuple(f"{key}: {item}" for key, item in payload.items())
    if isinstance(payload, list):
        return tuple(str(item) for item in payload)
    if isinstance(payload, str) and payload.strip():
        return (payload.strip(),)
    return ()


def _decode_json(value: object) -> object:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value
