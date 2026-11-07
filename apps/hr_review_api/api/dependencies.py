from __future__ import annotations

from typing import Annotated, cast

from fastapi import Depends, Header, Request

from apps.hr_review_api.domain.models import AuthenticatedUser
from apps.hr_review_api.runtime import ApplicationServices
from apps.hr_review_api.services.auth_service import AuthContext

SESSION_COOKIE_NAME = "hr_review_session"
CSRF_COOKIE_NAME = "hr_review_csrf"


def get_services(request: Request) -> ApplicationServices:
    return cast(ApplicationServices, request.app.state.services)


def get_auth_context(
    request: Request,
) -> AuthContext:
    auth = get_services(request).auth
    return auth.login_service.authenticate(request.cookies.get(auth.session_cookie_name))


def get_current_user(
    context: Annotated[AuthContext, Depends(get_auth_context)],
) -> AuthenticatedUser:
    return context.identity.user


def require_csrf(
    request: Request,
    context: Annotated[AuthContext, Depends(get_auth_context)],
    csrf_header: Annotated[str | None, Header(alias="X-CSRF-Token")] = None,
) -> AuthContext:
    auth = get_services(request).auth
    auth.login_service.verify_csrf(
        context, csrf_header, request.cookies.get(auth.csrf_cookie_name)
    )
    return context
