from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from apps.hr_review_api.api.dependencies import get_current_user, get_services
from apps.hr_review_api.domain.models import AuthenticatedUser
from apps.hr_review_api.schemas.common import ErrorResponse
from apps.hr_review_api.schemas.dashboard import (
    BatchListResponse,
    BatchResponse,
    DashboardResponse,
    LiveReportResponse,
)

router = APIRouter(prefix="/api/v1", tags=["dashboard"])


@router.get(
    "/batches",
    response_model=BatchListResponse,
    responses={401: {"model": ErrorResponse}},
)
def list_batches(
    request: Request,
    _user: Annotated[AuthenticatedUser, Depends(get_current_user)],
) -> BatchListResponse:
    batches = get_services(request).dashboard.batch_repository.list_completed()
    return BatchListResponse(items=[BatchResponse.model_validate(item) for item in batches])


@router.get(
    "/dashboard/summary",
    response_model=DashboardResponse,
    responses={401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}},
)
def dashboard_summary(
    request: Request,
    user: Annotated[AuthenticatedUser, Depends(get_current_user)],
    batch_id: str | None = None,
    job_profile_id: str | None = None,
    reviewer_id: int | None = None,
    reviewer_name: Annotated[str | None, Query(max_length=100)] = None,
) -> DashboardResponse:
    result = get_services(request).dashboard.dashboard_service.get_summary(
        user=user,
        batch_id=batch_id,
        job_profile_id=job_profile_id,
        reviewer_id=reviewer_id,
        reviewer_name=reviewer_name.strip() if reviewer_name else None,
    )
    return DashboardResponse.model_validate(result)


@router.get(
    "/reports/live-preview",
    response_model=LiveReportResponse,
    responses={401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}},
)
def live_report_preview(
    request: Request,
    user: Annotated[AuthenticatedUser, Depends(get_current_user)],
    batch_id: str | None = None,
    job_profile_id: str | None = None,
) -> LiveReportResponse:
    result = get_services(request).dashboard.dashboard_service.get_live_preview(
        user=user,
        batch_id=batch_id,
        job_profile_id=job_profile_id,
    )
    return LiveReportResponse.model_validate(result)
