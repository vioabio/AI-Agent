from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response

from apps.hr_review_api.api.dependencies import (
    get_auth_context,
    get_services,
    require_csrf,
)
from apps.hr_review_api.schemas.auth import LoginRequest, LoginResponse, UserResponse
from apps.hr_review_api.schemas.common import ErrorResponse, MessageResponse
from apps.hr_review_api.services.auth_service import AuthContext

router = APIRouter(prefix="/api/v1", tags=["auth"])


@router.post(
    "/auth/login",
    response_model=LoginResponse,
    responses={401: {"model": ErrorResponse}, 429: {"model": ErrorResponse}},
)
def login(payload: LoginRequest, request: Request, response: Response) -> LoginResponse:
    """校验本地账号并签发 HttpOnly 会话 Cookie。"""

    services = get_services(request)
    result = services.auth.login_service.login(
        username=payload.username.strip(),
        password=payload.password,
        client_ip=_client_ip(request),
        user_agent=request.headers.get("user-agent", "unknown"),
    )
    cookie_options = services.auth.cookie_options
    response.set_cookie(
        services.auth.session_cookie_name,
        result.session_token,
        httponly=True,
        max_age=services.auth.cookie_max_age,
        **cookie_options,
    )
    response.set_cookie(
        services.auth.csrf_cookie_name,
        result.csrf_token,
        httponly=False,
        max_age=services.auth.cookie_max_age,
        **cookie_options,
    )
    return LoginResponse(
        user=UserResponse.model_validate(result.user),
        expires_at=result.expires_at,
    )


@router.post(
    "/auth/logout",
    response_model=MessageResponse,
    responses={401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}},
)
def logout(
    request: Request,
    response: Response,
    context: Annotated[AuthContext, Depends(require_csrf)],
) -> MessageResponse:
    """吊销服务端会话并清除浏览器 Cookie。"""

    services = get_services(request)
    services.auth.login_service.logout(context)
    response.delete_cookie(
        services.auth.session_cookie_name, path="/", domain=services.auth.cookie_domain
    )
    response.delete_cookie(
        services.auth.csrf_cookie_name, path="/", domain=services.auth.cookie_domain
    )
    return MessageResponse(message="已退出登录")


@router.get(
    "/me",
    response_model=UserResponse,
    responses={401: {"model": ErrorResponse}},
)
def me(context: Annotated[AuthContext, Depends(get_auth_context)]) -> UserResponse:
    return UserResponse.model_validate(context.identity.user)


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"
