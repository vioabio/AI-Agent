from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, TypedDict

from sqlalchemy import Engine, create_engine

from apps.hr_review_api.config import HRReviewSettings
from apps.hr_review_api.repositories.auth_repository import AuthRepository
from apps.hr_review_api.repositories.batch_repository import BatchRepository
from apps.hr_review_api.repositories.candidate_repository import CandidateRepository
from apps.hr_review_api.services.auth_service import AuthService
from apps.hr_review_api.services.candidate_query_service import CandidateQueryService
from apps.hr_review_api.services.dashboard_service import DashboardService
from apps.hr_review_api.services.review_service import ReviewService


class CookieOptions(TypedDict):
    secure: bool
    samesite: Literal["strict"]
    path: str
    domain: str | None


@dataclass(frozen=True, slots=True)
class AuthRuntime:
    login_service: AuthService
    cookie_options: CookieOptions
    cookie_max_age: int
    cookie_domain: str | None


@dataclass(frozen=True, slots=True)
class CandidateRuntime:
    query_service: CandidateQueryService
    review_service: ReviewService


@dataclass(frozen=True, slots=True)
class DashboardRuntime:
    dashboard_service: DashboardService
    batch_repository: BatchRepository


@dataclass(frozen=True, slots=True)
class ApplicationServices:
    auth: AuthRuntime
    candidates: CandidateRuntime
    dashboard: DashboardRuntime


def build_engine(settings: HRReviewSettings) -> Engine:
    """只接受 HR Web 专用连接，禁止隐式回退到采集主库配置。"""

    return create_engine(settings.database_url, future=True, pool_pre_ping=True)


def build_services(engine: Engine, settings: HRReviewSettings) -> ApplicationServices:
    auth_service = AuthService(engine, settings, AuthRepository())
    candidate_repository = CandidateRepository()
    batch_repository = BatchRepository(settings.batch_state_path)
    query_service = CandidateQueryService(engine, candidate_repository, batch_repository)
    review_service = ReviewService(engine, candidate_repository, query_service)
    dashboard_service = DashboardService(
        engine,
        candidate_repository,
        query_service,
        batch_repository,
    )
    cookie_options = CookieOptions(
        secure=settings.cookie_secure,
        samesite="strict",
        path="/",
        domain=settings.cookie_domain,
    )
    return ApplicationServices(
        auth=AuthRuntime(
            login_service=auth_service,
            cookie_options=cookie_options,
            cookie_max_age=settings.session_hours * 3600,
            cookie_domain=settings.cookie_domain,
        ),
        candidates=CandidateRuntime(
            query_service=query_service,
            review_service=review_service,
        ),
        dashboard=DashboardRuntime(
            dashboard_service=dashboard_service,
            batch_repository=batch_repository,
        ),
    )
