from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from apps.hr_review_api.api.dependencies import get_current_user, get_services, require_csrf
from apps.hr_review_api.domain.models import AuthenticatedUser
from apps.hr_review_api.schemas.analysis_tasks import AnalysisTaskDetailResponse, AnalysisTaskPageResponse, RetryAnalysisTaskRequest
from apps.hr_review_api.schemas.common import ErrorResponse
from apps.hr_review_api.services.auth_service import AuthContext

router = APIRouter(prefix="/api/v1/analysis-tasks", tags=["shadow-analysis"])
ERRORS = {401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}, 404: {"model": ErrorResponse},
          409: {"model": ErrorResponse}}


@router.get("", response_model=AnalysisTaskPageResponse, responses=ERRORS)
def list_analysis_tasks(request: Request, user: Annotated[AuthenticatedUser, Depends(get_current_user)],
                        page: Annotated[int, Query(ge=1)] = 1,
                        page_size: Annotated[int, Query(ge=1, le=100)] = 20):
    return get_services(request).candidates.task_service.list_tasks(user=user, page=page, page_size=page_size)


@router.get("/{job_id}", response_model=AnalysisTaskDetailResponse, responses=ERRORS)
def get_analysis_task(job_id: str, request: Request, user: Annotated[AuthenticatedUser, Depends(get_current_user)]):
    return get_services(request).candidates.task_service.get_task(user=user, job_id=job_id)


@router.post("/{job_id}/retry", response_model=AnalysisTaskDetailResponse, responses=ERRORS)
def retry_analysis_task(job_id: str, payload: RetryAnalysisTaskRequest, request: Request,
                        context: Annotated[AuthContext, Depends(require_csrf)]):
    return get_services(request).candidates.task_service.retry(user=context.identity.user, job_id=job_id,
                                                            expected_token=payload.lease_token)
