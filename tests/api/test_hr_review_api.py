from __future__ import annotations

import json
from datetime import datetime

import pytest
from argon2 import PasswordHasher
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy import Engine, create_engine, func, insert, select
from sqlalchemy.pool import StaticPool

from apps.hr_review_api.config import HRReviewSettings
from apps.hr_review_api.main import create_app
from apps.hr_review_api.persistence.tables import (
    hr_user_job_permissions,
    hr_users,
    manual_review_events,
    sensitive_access_events,
)
from liepin_rpa.db import (
    metadata,
    resume_analysis_cache,
    resume_experience_records,
    resume_search_hits,
    resume_source_locators,
)


@pytest.fixture
def engine() -> Engine:
    value = create_engine(
        "sqlite+pysqlite://",
        future=True,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    metadata.create_all(value)
    seed_database(value)
    return value


@pytest.fixture
def client(engine: Engine, tmp_path) -> TestClient:
    batch_path = tmp_path / "batches.json"
    batch_path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "active_batch": None,
                "completed_batches": [
                    {
                        "batch_id": "test-completed-batch",
                        "started_at": "2020-01-01T00:00:00+08:00",
                        "ended_at": "2030-01-01T00:00:00+08:00",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    settings = HRReviewSettings(
        HR_REVIEW_DATABASE_URL="sqlite+pysqlite://",
        HR_BATCH_STATE_PATH=batch_path,
        HR_LOGIN_MAX_FAILURES=3,
    )
    return TestClient(create_app(settings=settings, engine=engine))


def login(client: TestClient, username: str = "reviewer") -> str:
    response = client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": "correct-password"},
    )
    assert response.status_code == 200
    csrf = client.cookies.get("hr_review_csrf")
    assert csrf
    return csrf


def test_default_queue_uses_shared_actionable_rules_and_job_permissions(client: TestClient) -> None:
    login(client)
    response = client.get("/api/v1/review-queue", params={"scope": "pending"})

    assert response.status_code == 200
    fingerprints = {item["candidate_fingerprint"] for item in response.json()["items"]}
    assert fingerprints == {"relevant-low", "review-90"}
    assert "temporary-failure" not in fingerprints
    assert "finance-hidden" not in fingerprints


def test_cross_job_latest_source_cannot_leak_unauthorized_locator(
    client: TestClient,
    engine: Engine,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            insert(resume_source_locators).values(
                id=100,
                candidate_fingerprint="relevant-low",
                candidate_name="候选人1",
                job_profile_id="finance_shared_management_v1",
                search_keyword="财务共享经理",
                locator_quality="share_link",
                detail_deep_link="https://example.invalid/private-finance-source",
                candidate_card_text="候选人1 52岁 未授权公司 · 财务经理",
            )
        )
        connection.execute(
            insert(resume_search_hits).values(
                id=100,
                candidate_fingerprint="relevant-low",
                candidate_name="候选人1",
                search_keyword="财务共享经理",
                hit_status="failed",
            )
        )

    login(client)
    response = client.get(
        "/api/v1/review-queue",
        params={"scope": "all", "job_profile_id": "daren_live_bd_v1"},
    )
    assert response.status_code == 200
    item = next(
        value
        for value in response.json()["items"]
        if value["candidate_fingerprint"] == "relevant-low"
    )
    assert item["job_profile_id"] == "daren_live_bd_v1"
    assert item["age"] == 31
    assert item["current_company_title"] == "珀莱雅 · 达播负责人"
    assert item["communication_status"] == "sent"
    assert item["has_share_link"] is False

    detail = client.get("/api/v1/candidates/relevant-low")
    assert detail.status_code == 200
    assert {source["job_profile_id"] for source in detail.json()["sources"]} == {"daren_live_bd_v1"}
    keyword_search = client.get(
        "/api/v1/review-queue",
        params={"scope": "all", "keyword": "财务共享经理"},
    )
    assert keyword_search.status_code == 200
    assert "relevant-low" not in {
        value["candidate_fingerprint"] for value in keyword_search.json()["items"]
    }


def test_review_is_atomic_preserves_ai_and_rejects_stale_revision(
    client: TestClient,
    engine: Engine,
) -> None:
    csrf = login(client)
    payload = {
        "review_status": "recommended_contact",
        "feedback": "经验匹配，建议尽快沟通。",
        "revision": 0,
        "assignee_id": None,
    }
    response = client.patch(
        "/api/v1/candidates/relevant-low/review",
        json=payload,
        headers={"X-CSRF-Token": csrf},
    )

    assert response.status_code == 200
    assert response.json()["summary"]["revision"] == 1
    with engine.connect() as connection:
        snapshot = (
            connection.execute(
                select(resume_experience_records).where(
                    resume_experience_records.c.candidate_fingerprint == "relevant-low"
                )
            )
            .mappings()
            .one()
        )
        events = (
            connection.execute(
                select(manual_review_events).where(
                    manual_review_events.c.candidate_fingerprint == "relevant-low"
                )
            )
            .mappings()
            .all()
        )
    assert snapshot["analysis_status"] == "匹配"
    assert snapshot["match_score"] == 70
    assert snapshot["manual_suppress_communication"] == 1
    assert snapshot["manual_review_revision"] == 1
    assert len(events) == 1

    conflict = client.patch(
        "/api/v1/candidates/relevant-low/review",
        json=payload,
        headers={"X-CSRF-Token": csrf},
    )
    assert conflict.status_code == 409
    assert conflict.json()["code"] == "review_conflict"


def test_review_snapshot_rolls_back_if_audit_event_insert_fails(
    client: TestClient,
    engine: Engine,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    csrf = login(client)
    repository = client.app.state.services.candidates.review_service.repository

    def fail_event_insert(*_args, **_kwargs) -> None:
        raise RuntimeError("simulated audit insert failure")

    monkeypatch.setattr(repository, "add_review_event", fail_event_insert)
    with pytest.raises(RuntimeError, match="simulated audit insert failure"):
        client.patch(
            "/api/v1/candidates/relevant-low/review",
            json={
                "review_status": "recommended_contact",
                "feedback": "事务必须整体回滚。",
                "revision": 0,
            },
            headers={"X-CSRF-Token": csrf},
        )

    with engine.connect() as connection:
        snapshot = (
            connection.execute(
                select(resume_experience_records).where(
                    resume_experience_records.c.candidate_fingerprint == "relevant-low"
                )
            )
            .mappings()
            .one()
        )
        event_count = connection.scalar(
            select(func.count())
            .select_from(manual_review_events)
            .where(manual_review_events.c.candidate_fingerprint == "relevant-low")
        )
    assert snapshot["manual_review_status"] is None
    assert snapshot["manual_review_revision"] == 0
    assert event_count == 0


def test_csrf_read_only_role_and_sensitive_link_are_protected(client: TestClient) -> None:
    login(client)
    missing_csrf = client.patch(
        "/api/v1/candidates/relevant-low/review",
        json={"review_status": "contacted", "feedback": "已联系候选人。", "revision": 0},
    )
    assert missing_csrf.status_code == 403
    detail = client.get("/api/v1/candidates/relevant-low")
    assert detail.status_code == 200
    assert all(source["share_link"] is None for source in detail.json()["sources"])

    client.post("/api/v1/auth/logout", headers={"X-CSRF-Token": client.cookies["hr_review_csrf"]})
    csrf = login(client, "viewer")
    forbidden = client.patch(
        "/api/v1/candidates/relevant-low/review",
        json={"review_status": "contacted", "feedback": "只读账号不应成功。", "revision": 0},
        headers={"X-CSRF-Token": csrf},
    )
    assert forbidden.status_code == 403


def test_review_feedback_rejects_whitespace_only_value(client: TestClient) -> None:
    csrf = login(client)
    response = client.patch(
        "/api/v1/candidates/relevant-low/review",
        json={"review_status": "contacted", "feedback": "   ", "revision": 0},
        headers={"X-CSRF-Token": csrf},
    )

    assert response.status_code == 422
    assert response.json()["code"] == "validation_error"


def test_sensitive_access_is_audited_only_after_authorized_detail_read(
    client: TestClient,
    engine: Engine,
) -> None:
    login(client)
    allowed = client.get("/api/v1/candidates/relevant-low")
    forbidden = client.get("/api/v1/candidates/finance-hidden")
    assert allowed.status_code == 200
    assert forbidden.status_code == 404

    with engine.connect() as connection:
        events = connection.execute(select(sensitive_access_events)).mappings().all()
    assert [event["candidate_fingerprint"] for event in events] == ["relevant-low"]


def test_dashboard_and_live_report_reflect_latest_review(client: TestClient) -> None:
    csrf = login(client)
    before = client.get("/api/v1/dashboard/summary").json()
    assert before["actionable"] == 2
    assert before["pending_review"] == 2
    client.patch(
        "/api/v1/candidates/relevant-low/review",
        json={"review_status": "hired", "feedback": "已确认录用。", "revision": 0},
        headers={"X-CSRF-Token": csrf},
    )

    summary = client.get("/api/v1/dashboard/summary").json()
    preview = client.get("/api/v1/reports/live-preview").json()
    assert summary["actionable"] == 1
    assert summary["pending_review"] == 1
    assert summary["hired"] == 1
    assert preview["ai_relevant"] == 1
    assert preview["actionable_pending"] == 1
    assert preview["hr_status_counts"]["hired"] == 1


def test_login_failures_are_rate_limited(client: TestClient) -> None:
    for _ in range(3):
        response = client.post(
            "/api/v1/auth/login",
            json={"username": "reviewer", "password": "wrong-password"},
        )
        assert response.status_code == 401
    limited = client.post(
        "/api/v1/auth/login",
        json={"username": "reviewer", "password": "correct-password"},
    )
    assert limited.status_code == 429


def test_database_url_is_required_and_missing_batch_fails_closed(
    engine: Engine,
    tmp_path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("HR_REVIEW_DATABASE_URL", raising=False)
    with pytest.raises(ValidationError, match="HR_REVIEW_DATABASE_URL"):
        HRReviewSettings(_env_file=None)

    settings = HRReviewSettings(
        _env_file=None,
        HR_REVIEW_DATABASE_URL="sqlite+pysqlite://",
        HR_BATCH_STATE_PATH=tmp_path / "missing-batches.json",
    )
    with TestClient(create_app(settings=settings, engine=engine)) as isolated_client:
        login(isolated_client)
        response = isolated_client.get("/api/v1/review-queue")
    assert response.status_code == 404
    assert response.json()["code"] == "batch_not_found"


def seed_database(engine: Engine) -> None:
    password_hash = PasswordHasher().hash("correct-password")
    with engine.begin() as connection:
        connection.execute(
            insert(hr_users),
            [
                {
                    "id": 1,
                    "username": "reviewer",
                    "display_name": "审核员",
                    "password_hash": password_hash,
                    "role": "hr",
                    "is_active": True,
                    "can_view_sensitive": False,
                },
                {
                    "id": 2,
                    "username": "viewer",
                    "display_name": "只读用户",
                    "password_hash": password_hash,
                    "role": "read_only",
                    "is_active": True,
                    "can_view_sensitive": False,
                },
            ],
        )
        connection.execute(
            insert(hr_user_job_permissions),
            [
                {"id": 1, "user_id": 1, "job_profile_id": "daren_live_bd_v1"},
                {"id": 2, "user_id": 2, "job_profile_id": "daren_live_bd_v1"},
            ],
        )
        candidates = [
            (1, "relevant-low", "匹配", 70),
            (2, "review-90", "待复核", 90),
            (3, "review-89", "待复核", 89),
            (4, "temporary-failure", "待复核", 99),
            (5, "finance-hidden", "匹配", 95),
        ]
        for identifier, fingerprint, status, score in candidates:
            connection.execute(
                insert(resume_experience_records).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    experience_hash=f"hash-{identifier}",
                    candidate_name=f"候选人{identifier}",
                    experience=(
                        "候选人信息：年龄：31岁 "
                        "工作内容：珀莱雅；达播负责人；2023.01-至今；负责达人业务"
                        if fingerprint == "relevant-low"
                        else "候选人完整简历正文"
                    ),
                    analysis_status=status,
                    match_score=score,
                    updated_at=datetime(2026, 8, 18, 9, identifier),
                )
            )
            result = "relevant" if status == "匹配" else "needs_review"
            connection.execute(
                insert(resume_analysis_cache).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    experience_hash=f"hash-{identifier}",
                    analysis_version="test-v1",
                    analysis_status=status,
                    match_result=result,
                    match_score=score,
                    analysis_reason=(
                        "Coze API 临时异常" if fingerprint == "temporary-failure" else "测试理由"
                    ),
                )
            )
            job = (
                "finance_shared_management_v1"
                if fingerprint == "finance-hidden"
                else "daren_live_bd_v1"
            )
            connection.execute(
                insert(resume_source_locators).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=f"候选人{identifier}",
                    job_profile_id=job,
                    search_keyword="达人营销负责人",
                    locator_quality=("snapshot" if fingerprint == "relevant-low" else "share_link"),
                    detail_deep_link=(
                        None
                        if fingerprint == "relevant-low"
                        else f"https://example.invalid/{identifier}"
                    ),
                    candidate_card_text=(
                        "候选人1 31岁 珀莱雅 · 达播负责人"
                        if fingerprint == "relevant-low"
                        else None
                    ),
                )
            )
            connection.execute(
                insert(resume_search_hits).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=f"候选人{identifier}",
                    search_keyword="达人营销负责人",
                    hit_status="sent" if fingerprint == "relevant-low" else "hit",
                )
            )
