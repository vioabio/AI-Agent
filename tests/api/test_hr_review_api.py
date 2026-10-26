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
                    },
                    {
                        "batch_id": "another-completed-batch",
                        "started_at": "2019-01-01T00:00:00+08:00",
                        "ended_at": "2019-01-02T00:00:00+08:00",
                    },
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
    assert "relevant-under-threshold" not in fingerprints
    assert "temporary-failure" not in fingerprints
    assert "finance-hidden" not in fingerprints


def test_analysis_led_queue_and_detail_keep_missing_experience_timestamp_null_without_writes(
    client: TestClient,
    engine: Engine,
) -> None:
    seed_analysis_only_candidate(
        engine,
        identifier=200,
        fingerprint="orphan-review-92",
        score=92,
        with_locator=True,
    )

    login(client)
    response = client.get("/api/v1/review-queue", params={"scope": "pending"})
    assert response.status_code == 200
    item = next(
        value
        for value in response.json()["items"]
        if value["candidate_fingerprint"] == "orphan-review-92"
    )
    assert item["match_result"] == "needs_review"
    assert item["match_score"] == 92
    assert item["analysis_status"] == "待复核"
    assert item["updated_at"] is None

    with engine.connect() as connection:
        before_master_count = connection.scalar(
            select(func.count())
            .select_from(resume_experience_records)
            .where(resume_experience_records.c.candidate_fingerprint == "orphan-review-92")
        )
        before_analysis_updated_at = connection.scalar(
            select(resume_analysis_cache.c.updated_at).where(
                resume_analysis_cache.c.candidate_fingerprint == "orphan-review-92"
            )
        )
    assert before_master_count == 0

    detail = client.get("/api/v1/candidates/orphan-review-92")
    assert detail.status_code == 200
    assert detail.json()["experience"] == "候选人信息：年龄：36岁 工作内容：历史累计候选人"
    assert detail.json()["summary"]["updated_at"] is None
    with engine.connect() as connection:
        master_count = connection.scalar(
            select(func.count())
            .select_from(resume_experience_records)
            .where(resume_experience_records.c.candidate_fingerprint == "orphan-review-92")
        )
        access_count = connection.scalar(
            select(func.count())
            .select_from(sensitive_access_events)
            .where(sensitive_access_events.c.candidate_fingerprint == "orphan-review-92")
        )
        analysis_updated_at = connection.scalar(
            select(resume_analysis_cache.c.updated_at).where(
                resume_analysis_cache.c.candidate_fingerprint == "orphan-review-92"
            )
        )
    assert master_count == 0
    assert access_count == 0
    assert analysis_updated_at == before_analysis_updated_at


def test_updated_at_uses_only_experience_record_for_list_detail_and_sorting(
    client: TestClient,
    engine: Engine,
) -> None:
    relevant_updated_at = datetime(2026, 8, 10, 8, 0)
    review_updated_at = datetime(2026, 8, 11, 8, 0)
    with engine.begin() as connection:
        connection.execute(
            resume_experience_records.update()
            .where(resume_experience_records.c.candidate_fingerprint == "relevant-low")
            .values(updated_at=relevant_updated_at)
        )
        connection.execute(
            resume_experience_records.update()
            .where(resume_experience_records.c.candidate_fingerprint == "review-90")
            .values(updated_at=review_updated_at)
        )
        connection.execute(
            resume_analysis_cache.update()
            .where(resume_analysis_cache.c.candidate_fingerprint == "relevant-low")
            .values(updated_at=datetime(2026, 8, 29, 8, 0))
        )
        connection.execute(
            resume_analysis_cache.update()
            .where(resume_analysis_cache.c.candidate_fingerprint == "review-90")
            .values(updated_at=datetime(2026, 8, 20, 8, 0))
        )
        connection.execute(
            resume_search_hits.update()
            .where(resume_search_hits.c.candidate_fingerprint == "relevant-low")
            .values(created_at=datetime(2026, 8, 28, 8, 0))
        )
        connection.execute(
            resume_source_locators.update()
            .where(resume_source_locators.c.candidate_fingerprint == "relevant-low")
            .values(created_at=datetime(2026, 8, 27, 8, 0))
        )

    login(client)
    ascending = client.get(
        "/api/v1/review-queue",
        params={
            "scope": "all",
            "page_size": 100,
            "sort_by": "updated_at",
            "sort_order": "asc",
        },
    )
    assert ascending.status_code == 200
    items = ascending.json()["items"]
    by_fingerprint = {item["candidate_fingerprint"]: item for item in items}
    relevant_value = datetime.fromisoformat(by_fingerprint["relevant-low"]["updated_at"])
    assert relevant_value == relevant_updated_at
    assert datetime.fromisoformat(by_fingerprint["review-90"]["updated_at"]) == review_updated_at
    fingerprints = [item["candidate_fingerprint"] for item in items]
    assert fingerprints.index("relevant-low") < fingerprints.index("review-90")

    detail = client.get("/api/v1/candidates/relevant-low")
    assert detail.status_code == 200
    assert datetime.fromisoformat(detail.json()["summary"]["updated_at"]) == relevant_updated_at

    descending = client.get(
        "/api/v1/review-queue",
        params={
            "scope": "all",
            "page_size": 100,
            "sort_by": "updated_at",
            "sort_order": "desc",
        },
    )
    assert descending.status_code == 200
    fingerprints = [item["candidate_fingerprint"] for item in descending.json()["items"]]
    assert fingerprints.index("review-90") < fingerprints.index("relevant-low")


def test_list_and_detail_queries_do_not_write_or_refresh_source_timestamps(
    client: TestClient,
    engine: Engine,
) -> None:
    def timestamp_snapshot() -> tuple[object, ...]:
        with engine.connect() as connection:
            return (
                connection.scalar(
                    select(resume_experience_records.c.updated_at).where(
                        resume_experience_records.c.candidate_fingerprint == "relevant-low"
                    )
                ),
                connection.scalar(
                    select(resume_analysis_cache.c.updated_at).where(
                        resume_analysis_cache.c.candidate_fingerprint == "relevant-low"
                    )
                ),
                connection.scalar(
                    select(resume_search_hits.c.created_at).where(
                        resume_search_hits.c.candidate_fingerprint == "relevant-low"
                    )
                ),
                connection.scalar(
                    select(resume_source_locators.c.created_at).where(
                        resume_source_locators.c.candidate_fingerprint == "relevant-low"
                    )
                ),
                connection.scalar(select(func.count()).select_from(sensitive_access_events)),
            )

    before = timestamp_snapshot()
    login(client)
    assert client.get("/api/v1/review-queue", params={"scope": "all"}).status_code == 200
    assert client.get("/api/v1/candidates/relevant-low").status_code == 200
    assert timestamp_snapshot() == before


def test_direct_review_of_analysis_only_candidate_is_atomic(
    client: TestClient,
    engine: Engine,
) -> None:
    seed_analysis_only_candidate(
        engine,
        identifier=201,
        fingerprint="orphan-direct-review",
        score=93,
        with_locator=True,
    )
    csrf = login(client)

    response = client.patch(
        "/api/v1/candidates/orphan-direct-review/review",
        json={
            "review_status": "recommended_contact",
            "feedback": "日报累计候选人，建议沟通。",
            "revision": 0,
        },
        headers={"X-CSRF-Token": csrf},
    )

    assert response.status_code == 200
    assert response.json()["summary"]["revision"] == 1
    assert response.json()["summary"]["updated_at"] is not None
    with engine.connect() as connection:
        snapshot = (
            connection.execute(
                select(resume_experience_records).where(
                    resume_experience_records.c.candidate_fingerprint == "orphan-direct-review"
                )
            )
            .mappings()
            .one()
        )
        event_count = connection.scalar(
            select(func.count())
            .select_from(manual_review_events)
            .where(manual_review_events.c.candidate_fingerprint == "orphan-direct-review")
        )
    assert snapshot["manual_review_status"] == "recommended_contact"
    assert snapshot["manual_review_revision"] == 1
    response_updated_at = datetime.fromisoformat(response.json()["summary"]["updated_at"])
    assert response_updated_at == snapshot["updated_at"]
    assert event_count == 1


def test_locatorless_history_is_excluded_even_for_all_job_role(
    client: TestClient,
    engine: Engine,
) -> None:
    seed_analysis_only_candidate(
        engine,
        identifier=202,
        fingerprint="orphan-without-locator",
        score=94,
        with_locator=False,
    )

    login(client)
    restricted = client.get("/api/v1/review-queue", params={"scope": "pending"})
    assert restricted.status_code == 200
    assert "orphan-without-locator" not in {
        value["candidate_fingerprint"] for value in restricted.json()["items"]
    }

    client.post(
        "/api/v1/auth/logout",
        headers={"X-CSRF-Token": client.cookies["hr_review_csrf"]},
    )
    login(client, "lead")
    all_jobs = client.get("/api/v1/review-queue", params={"scope": "pending"})
    assert all_jobs.status_code == 200
    assert "orphan-without-locator" not in {
        value["candidate_fingerprint"] for value in all_jobs.json()["items"]
    }


def test_review_queue_spans_batches_unless_api_explicitly_requests_one(
    client: TestClient,
    engine: Engine,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            resume_source_locators.update()
            .where(resume_source_locators.c.candidate_fingerprint == "relevant-low")
            .values(
                search_batch_id="another-completed-batch",
                # 故意保留在所选批次的时间范围内，证明不会再按时间误归批次。
                created_at=datetime(2026, 8, 18, 12, 0),
            )
        )
        connection.execute(
            resume_search_hits.update()
            .where(resume_search_hits.c.candidate_fingerprint == "relevant-low")
            .values(
                search_batch_id="another-completed-batch",
                created_at=datetime(2026, 8, 18, 12, 0),
            )
        )

    login(client)
    all_batches = client.get("/api/v1/review-queue", params={"scope": "pending"})
    assert all_batches.status_code == 200
    assert "relevant-low" in {
        value["candidate_fingerprint"] for value in all_batches.json()["items"]
    }

    explicit_batch = client.get(
        "/api/v1/review-queue",
        params={"scope": "pending", "batch_id": "test-completed-batch"},
    )
    assert explicit_batch.status_code == 200
    assert "relevant-low" not in {
        value["candidate_fingerprint"] for value in explicit_batch.json()["items"]
    }


def test_reviewer_is_information_not_a_queue_permission(
    client: TestClient,
    engine: Engine,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            resume_experience_records.update()
            .where(resume_experience_records.c.candidate_fingerprint == "review-90")
            .values(manual_reviewed_by=1, manual_review_assignee_id=2)
        )
        connection.execute(
            resume_experience_records.update()
            .where(resume_experience_records.c.candidate_fingerprint == "relevant-low")
            .values(manual_reviewed_by=1, manual_review_assignee_id=None)
        )

    login(client)
    legacy_mine = client.get("/api/v1/review-queue", params={"scope": "mine"})
    assert legacy_mine.status_code == 200
    item = next(
        value
        for value in legacy_mine.json()["items"]
        if value["candidate_fingerprint"] == "review-90"
    )
    assert item["reviewer_id"] == 2
    assert item["reviewer_name"] == "只读用户"

    reviewer_filter = client.get(
        "/api/v1/review-queue",
        params={"scope": "pending", "reviewer_id": 2},
    )
    assert reviewer_filter.status_code == 200
    assert {value["candidate_fingerprint"] for value in reviewer_filter.json()["items"]} == {
        "review-90"
    }

    reviewer_name_filter = client.get(
        "/api/v1/review-queue",
        params={"scope": "pending", "reviewer_name": "只读"},
    )
    assert reviewer_name_filter.status_code == 200
    assert {value["candidate_fingerprint"] for value in reviewer_name_filter.json()["items"]} == {
        "review-90"
    }
    reviewer_metrics = client.get(
        "/api/v1/dashboard/summary",
        params={"reviewer_name": "只读"},
    )
    assert reviewer_metrics.status_code == 200
    assert reviewer_metrics.json()["pending_review"] == 1

    legacy_reviewer_filter = client.get(
        "/api/v1/review-queue",
        params={"scope": "pending", "reviewer_id": 1},
    )
    assert legacy_reviewer_filter.status_code == 200
    assert {value["candidate_fingerprint"] for value in legacy_reviewer_filter.json()["items"]} == {
        "relevant-low"
    }


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


def test_communication_status_uses_normalized_history_in_list_detail_and_filters(
    client: TestClient,
    engine: Engine,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            resume_analysis_cache.update()
            .where(resume_analysis_cache.c.candidate_fingerprint == "review-89")
            .values(match_score=91)
        )
        connection.execute(
            resume_analysis_cache.update()
            .where(resume_analysis_cache.c.candidate_fingerprint == "temporary-failure")
            .values(analysis_reason="测试理由")
        )
        connection.execute(
            resume_search_hits.update()
            .where(resume_search_hits.c.candidate_fingerprint == "review-90")
            .values(hit_status="immediate_job_confirmed")
        )
        connection.execute(
            resume_search_hits.update()
            .where(resume_search_hits.c.candidate_fingerprint == "review-89")
            .values(hit_status="skipped_recent_continue")
        )
        connection.execute(
            resume_search_hits.update()
            .where(resume_search_hits.c.candidate_fingerprint == "temporary-failure")
            .values(hit_status="send_failed:post_job_selection_chat_not_detected")
        )
        connection.execute(
            insert(resume_search_hits).values(
                id=900,
                candidate_fingerprint="relevant-low",
                candidate_name="候选人1",
                search_keyword="达人营销负责人",
                search_batch_id="test-completed-batch",
                hit_status="reviewed",
                created_at=datetime(2026, 8, 19, 9, 0),
            )
        )

    login(client)
    all_candidates = client.get(
        "/api/v1/review-queue",
        params={"scope": "all", "page_size": 100},
    )
    assert all_candidates.status_code == 200
    by_fingerprint = {
        item["candidate_fingerprint"]: item for item in all_candidates.json()["items"]
    }
    assert by_fingerprint["relevant-low"]["communication_status"] == "sent"
    assert by_fingerprint["review-90"]["communication_status"] == "sent"
    assert by_fingerprint["review-89"]["communication_status"] == "previously_contacted"
    assert by_fingerprint["temporary-failure"]["communication_status"] == "failed"
    assert (
        len(
            [
                item
                for item in all_candidates.json()["items"]
                if item["candidate_fingerprint"] == "relevant-low"
            ]
        )
        == 1
    )

    detail = client.get("/api/v1/candidates/relevant-low")
    assert detail.status_code == 200
    assert detail.json()["summary"]["communication_status"] == "sent"

    expected_by_filter = {
        "sent": {"relevant-low", "review-90"},
        "previously_contacted": {"review-89"},
        "failed": {"temporary-failure"},
    }
    for status, expected in expected_by_filter.items():
        response = client.get(
            "/api/v1/review-queue",
            params={
                "scope": "all",
                "communication_status": status,
                "page_size": 100,
            },
        )
        assert response.status_code == 200
        assert {item["candidate_fingerprint"] for item in response.json()["items"]} == expected

    not_sent = client.get(
        "/api/v1/review-queue",
        params={"scope": "all", "communication_status": "not_sent", "page_size": 100},
    )
    assert not_sent.status_code == 200
    assert {"relevant-low", "review-90"}.isdisjoint(
        item["candidate_fingerprint"] for item in not_sent.json()["items"]
    )


def test_candidate_detail_collapses_repeated_batch_source_observations(
    client: TestClient,
    engine: Engine,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            insert(resume_source_locators),
            [
                {
                    "id": 101,
                    "candidate_fingerprint": "relevant-low",
                    "candidate_name": "候选人1",
                    "job_profile_id": "daren_live_bd_v1",
                    "search_keyword": "达人营销负责人",
                    "search_batch_id": "another-completed-batch",
                    "locator_quality": "snapshot",
                },
                {
                    "id": 102,
                    "candidate_fingerprint": "relevant-low",
                    "candidate_name": "候选人1",
                    "job_profile_id": "daren_live_bd_v1",
                    "search_keyword": "达人营销负责人",
                    "search_batch_id": "test-completed-batch",
                    "locator_quality": "share_link",
                    "detail_deep_link": "https://example.invalid/relevant-low",
                },
            ],
        )

    login(client)
    response = client.get("/api/v1/candidates/relevant-low")

    assert response.status_code == 200
    sources = response.json()["sources"]
    assert len(sources) == 1
    assert sources[0]["locator_quality"] == "share_link"


def test_review_is_atomic_preserves_ai_and_rejects_stale_revision(
    client: TestClient,
    engine: Engine,
) -> None:
    csrf = login(client)
    with engine.connect() as connection:
        previous_updated_at = connection.scalar(
            select(resume_experience_records.c.updated_at).where(
                resume_experience_records.c.candidate_fingerprint == "relevant-low"
            )
        )
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
    assert snapshot["match_score"] == 95
    assert snapshot["manual_suppress_communication"] == 1
    assert snapshot["manual_review_revision"] == 1
    assert previous_updated_at is not None
    assert snapshot["updated_at"] > previous_updated_at
    response_updated_at = datetime.fromisoformat(response.json()["summary"]["updated_at"])
    assert response_updated_at == snapshot["updated_at"]
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


def test_detail_read_is_authorized_without_writing_database_events(
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
    assert events == []


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
    assert preview["ai_relevant"] == 2
    assert preview["actionable_pending"] == 1
    assert preview["hr_status_counts"]["hired"] == 1


def test_historical_masked_name_variants_are_deduplicated_in_queue_and_metrics(
    client: TestClient,
    engine: Engine,
) -> None:
    common_resume = "工作经历：某消费品公司；达人营销负责人；负责达人投放。" * 40
    with engine.begin() as connection:
        for identifier, fingerprint, name, score, experience in (
            (910, "legacy-mao-honorific", "毛先生", 90, common_resume),
            (
                911,
                "legacy-mao-masked",
                "毛**",
                92,
                f"{common_resume}补充项目经历：年度达人矩阵建设。",
            ),
        ):
            connection.execute(
                insert(resume_experience_records).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    experience_hash=f"identity-hash-{identifier}",
                    candidate_name=name,
                    experience=experience,
                    analysis_status="匹配",
                    match_score=score,
                    manual_review_status="interviewing",
                    updated_at=datetime(2026, 8, 19, 9, identifier - 900),
                )
            )
            connection.execute(
                insert(resume_analysis_cache).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    experience_hash=f"identity-hash-{identifier}",
                    experience=experience,
                    analysis_version="test-v1",
                    analysis_status="匹配",
                    match_result="relevant",
                    match_score=score,
                    analysis_reason="测试历史双指纹",
                )
            )
            connection.execute(
                insert(resume_source_locators).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=name,
                    source_account_alias="liepin_account_a",
                    job_profile_id="daren_live_bd_v1",
                    search_keyword="达人营销负责人",
                    search_batch_id="test-completed-batch",
                    locator_quality="share_link",
                    detail_deep_link=f"https://example.invalid/{identifier}",
                    candidate_card_text=(f"{name} 43岁 60-80K · 16薪 达人营销负责人"),
                )
            )
            connection.execute(
                insert(resume_search_hits).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=name,
                    search_keyword="达人营销负责人",
                    search_batch_id="test-completed-batch",
                    hit_status="sent",
                )
            )

    login(client)
    queue = client.get(
        "/api/v1/review-queue",
        params={"scope": "all", "page_size": 100},
    )
    summary = client.get("/api/v1/dashboard/summary")

    assert queue.status_code == 200
    identity_rows = [
        item
        for item in queue.json()["items"]
        if item["candidate_fingerprint"] in {"legacy-mao-honorific", "legacy-mao-masked"}
    ]
    assert len(identity_rows) == 1
    assert identity_rows[0]["candidate_fingerprint"] == "legacy-mao-masked"
    assert identity_rows[0]["match_score"] == 92
    assert summary.status_code == 200
    assert summary.json()["interviewing"] == 1


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


def test_database_url_is_required_and_only_report_needs_a_completed_batch(
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
        queue_response = isolated_client.get("/api/v1/review-queue")
        report_response = isolated_client.get("/api/v1/reports/live-preview")
    assert queue_response.status_code == 200
    assert queue_response.json()["total"] == 0
    assert report_response.status_code == 404
    assert report_response.json()["code"] == "batch_not_found"


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
                {
                    "id": 3,
                    "username": "lead",
                    "display_name": "招聘负责人",
                    "password_hash": password_hash,
                    "role": "recruiting_lead",
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
            (1, "relevant-low", "匹配", 95),
            (2, "review-90", "待复核", 80),
            (3, "review-89", "待复核", 79),
            (4, "temporary-failure", "待复核", 99),
            (5, "finance-hidden", "匹配", 95),
            (6, "relevant-under-threshold", "匹配", 70),
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
                    search_batch_id="test-completed-batch",
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
                    search_batch_id="test-completed-batch",
                    hit_status="sent" if fingerprint == "relevant-low" else "hit",
                )
            )


def seed_analysis_only_candidate(
    engine: Engine,
    *,
    identifier: int,
    fingerprint: str,
    score: int,
    with_locator: bool,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            insert(resume_analysis_cache).values(
                id=identifier,
                candidate_fingerprint=fingerprint,
                experience_hash=f"orphan-hash-{identifier}",
                experience="候选人信息：年龄：36岁 工作内容：历史累计候选人",
                analysis_version="daren-history-v3",
                analysis_status="待复核",
                match_result="needs_review",
                match_score=score,
                analysis_reason="历史累计待人工复核",
                score_breakdown_json=json.dumps(
                    {"industry_depth": 30, "beauty_continuity": 30, "role_evidence": 32}
                ),
            )
        )
        connection.execute(
            insert(resume_search_hits).values(
                id=identifier,
                candidate_fingerprint=fingerprint,
                candidate_name="历史候选人",
                search_keyword="达人营销负责人",
                search_batch_id="test-completed-batch",
                hit_status="hit",
            )
        )
        if with_locator:
            connection.execute(
                insert(resume_source_locators).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name="历史候选人",
                    job_profile_id="daren_live_bd_v1",
                    search_keyword="达人营销负责人",
                    search_batch_id="test-completed-batch",
                    locator_quality="snapshot",
                )
            )
