from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import Engine

from apps.hr_review_api.api import auth, candidates, dashboard
from apps.hr_review_api.config import HRReviewSettings
from apps.hr_review_api.domain.errors import (
    AuthenticationError,
    AuthorizationError,
    BatchNotFoundError,
    CandidateNotFoundError,
    CsrfValidationError,
    InvalidReviewTransitionError,
    LoginRateLimitedError,
    ReviewConflictError,
)
from apps.hr_review_api.runtime import build_engine, build_services
from apps.hr_review_api.schemas.common import ErrorDetail, ErrorResponse

LOGGER = logging.getLogger(__name__)


def create_app(
    *,
    settings: HRReviewSettings | None = None,
    engine: Engine | None = None,
) -> FastAPI:
    """构建 API；应用启动不会建表、迁移或触碰手机采集状态。"""

    resolved_settings = settings or HRReviewSettings()
    resolved_engine = engine or build_engine(resolved_settings)
    docs_url = "/docs" if resolved_settings.enable_openapi else None
    openapi_url = "/openapi.json" if resolved_settings.enable_openapi else None
    application = FastAPI(
        title="HR 候选人审核与招聘进度 API",
        version="1.0.0",
        docs_url=docs_url,
        redoc_url=None,
        openapi_url=openapi_url,
    )
    application.state.services = build_services(resolved_engine, resolved_settings)
    application.add_middleware(
        CORSMiddleware,
        allow_origins=resolved_settings.origin_list,
        allow_credentials=True,
        allow_methods=["GET", "POST", "PATCH"],
        allow_headers=["Content-Type", "X-CSRF-Token"],
    )
    application.include_router(auth.router)
    application.include_router(dashboard.router)
    application.include_router(candidates.router)
    _install_exception_handlers(application)
    return application


def _install_exception_handlers(application: FastAPI) -> None:
    mappings: tuple[tuple[type[Exception], int, str], ...] = (
        (LoginRateLimitedError, 429, "login_rate_limited"),
        (AuthenticationError, 401, "authentication_required"),
        (CsrfValidationError, 403, "csrf_validation_failed"),
        (AuthorizationError, 403, "permission_denied"),
        (CandidateNotFoundError, 404, "candidate_not_found"),
        (BatchNotFoundError, 404, "batch_not_found"),
        (ReviewConflictError, 409, "review_conflict"),
        (InvalidReviewTransitionError, 422, "invalid_review_transition"),
    )
    for exception_type, status_code, code in mappings:
        application.add_exception_handler(
            exception_type,
            _domain_error_handler(status_code=status_code, code=code),
        )

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(
        _request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        details = [
            ErrorDetail(
                field=".".join(str(part) for part in error["loc"]),
                message=str(error["msg"]),
            )
            for error in exc.errors()
        ]
        return JSONResponse(
            status_code=422,
            content=ErrorResponse(
                code="validation_error",
                message="请求参数不正确",
                details=details,
            ).model_dump(),
        )

    @application.exception_handler(Exception)
    async def unexpected_error_handler(request: Request, exc: Exception) -> JSONResponse:
        # SQLAlchemy 异常堆栈可能含绑定参数；这里只记录异常类型，避免反馈或简历进入日志。
        LOGGER.error(
            "HR API request failed",
            extra={
                "event": "hr_api_unexpected_error",
                "path": request.url.path,
                "exception_type": type(exc).__name__,
            },
        )
        return JSONResponse(
            status_code=500,
            content=ErrorResponse(
                code="internal_error",
                message="服务暂时不可用，请稍后重试",
            ).model_dump(),
        )


def _domain_error_handler(
    *,
    status_code: int,
    code: str,
) -> Callable[[Request, Exception], Awaitable[JSONResponse]]:
    async def handler(_request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=status_code,
            content=ErrorResponse(code=code, message=str(exc)).model_dump(),
        )

    return handler
