from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query, Request

from apps.hr_review_api.api.dependencies import get_current_user, get_services, require_csrf
from apps.hr_review_api.domain.models import AuthenticatedUser, CandidateQueryCriteria
from apps.hr_review_api.schemas.candidates import (
    CandidateDetailResponse,
    CandidatePageResponse,
    CandidateSort,
    CandidateSummaryResponse,
    ReviewEventResponse,
    ReviewHistoryResponse,
    ReviewScope,
    SubmitReviewRequest,
)
from apps.hr_review_api.schemas.common import ErrorResponse, SortOrder
from apps.hr_review_api.services.auth_service import AuthContext
from apps.hr_review_api.services.review_service import SubmitReviewCommand

router = APIRouter(prefix="/api/v1", tags=["candidates"])
SHANGHAI_TIMEZONE = timezone(timedelta(hours=8))


@router.get(
    "/review-queue",
    response_model=CandidatePageResponse,
    responses={401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}},
)
def list_review_queue(
    request: Request,
    user: Annotated[AuthenticatedUser, Depends(get_current_user)],
    batch_id: str | None = None,
    job_profile_id: str | None = None,
    match_result: Literal["relevant", "needs_review", "not_relevant"] | None = None,
    min_score: Annotated[int | None, Query(ge=0, le=100)] = None,
    max_score: Annotated[int | None, Query(ge=0, le=100)] = None,
    review_status: str | None = None,
    reviewer_id: Annotated[int | None, Query(ge=1)] = None,
    communication_status: str | None = None,
    has_share_link: bool | None = None,
    keyword: Annotated[str | None, Query(max_length=100)] = None,
    scope: ReviewScope = "mine",
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 20,
    sort_by: CandidateSort = "updated_at",
    sort_order: SortOrder = "desc",
) -> CandidatePageResponse:
    """按岗位权限执行服务端筛选、分页和白名单排序。"""

    page_result = get_services(request).candidates.query_service.list_candidates(
        user=user,
        batch_id=batch_id,
        criteria=CandidateQueryCriteria(
            job_profile_id=job_profile_id,
            match_result=match_result,
            min_score=min_score,
            max_score=max_score,
            review_status=review_status,
            reviewer_id=reviewer_id,
            communication_status=communication_status,
            has_share_link=has_share_link,
            keyword=keyword.strip() if keyword else None,
            review_scope=scope,
            page=page,
            page_size=page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        ),
    )
    return CandidatePageResponse(
        items=[CandidateSummaryResponse.model_validate(item) for item in page_result.items],
        page=page_result.page,
        page_size=page_result.page_size,
        total=page_result.total,
    )


@router.get(
    "/candidates/{candidate_fingerprint}",
    response_model=CandidateDetailResponse,
    responses={401: {"model": ErrorResponse}, 404: {"model": ErrorResponse}},
)
def get_candidate(
    candidate_fingerprint: str,
    request: Request,
    user: Annotated[AuthenticatedUser, Depends(get_current_user)],
) -> CandidateDetailResponse:
    detail = get_services(request).candidates.query_service.get_detail(
        user=user,
        candidate_fingerprint=candidate_fingerprint,
        client_ip=_client_ip(request),
        user_agent=request.headers.get("user-agent", "unknown"),
        now=datetime.now(SHANGHAI_TIMEZONE),
    )
    return CandidateDetailResponse.model_validate(detail)


@router.patch(
    "/candidates/{candidate_fingerprint}/review",
    response_model=CandidateDetailResponse,
    responses={
        401: {"model": ErrorResponse},
        403: {"model": ErrorResponse},
        404: {"model": ErrorResponse},
        409: {"model": ErrorResponse},
    },
)
def submit_review(
    candidate_fingerprint: str,
    payload: SubmitReviewRequest,
    request: Request,
    context: Annotated[AuthContext, Depends(require_csrf)],
) -> CandidateDetailResponse:
    detail = get_services(request).candidates.review_service.submit(
        user=context.identity.user,
        command=SubmitReviewCommand(
            candidate_fingerprint=candidate_fingerprint,
            review_status=payload.review_status,
            feedback=payload.feedback,
            revision=payload.revision,
            assignee_id=payload.assignee_id,
            client_ip=_client_ip(request),
            user_agent=request.headers.get("user-agent", "unknown"),
            submitted_at=datetime.now(SHANGHAI_TIMEZONE),
        ),
    )
    return CandidateDetailResponse.model_validate(detail)


@router.get(
    "/candidates/{candidate_fingerprint}/review-history",
    response_model=ReviewHistoryResponse,
    responses={401: {"model": ErrorResponse}, 404: {"model": ErrorResponse}},
)
def get_review_history(
    candidate_fingerprint: str,
    request: Request,
    user: Annotated[AuthenticatedUser, Depends(get_current_user)],
) -> ReviewHistoryResponse:
    history = get_services(request).candidates.query_service.get_history(
        user=user,
        candidate_fingerprint=candidate_fingerprint,
    )
    return ReviewHistoryResponse(
        items=[ReviewEventResponse.model_validate(item) for item in history]
    )


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"
