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
from liepin_rpa.analysis_evidence_store import persist_analysis_evidence, result_document, resume_snapshots
from liepin_rpa.resume_evidence import resume_text_hash


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


@pytest.fixture
def shadow_client(engine, tmp_path):
    from liepin_rpa.analysis_jobs import AnalysisJobQueue
    queue = AnalysisJobQueue(engine)
    queue.configure()
    queue.create_batch("shadow-batch")
    path = tmp_path / "shadow-batches.json"
    path.write_text(json.dumps({"completed_batches": [{"batch_id": "test-completed-batch",
        "started_at": "2020-01-01T00:00:00+08:00", "ended_at": "2030-01-01T00:00:00+08:00"}]}), encoding="utf-8")
    settings = HRReviewSettings(_env_file=None, APP_ENV="development", HR_REVIEW_DATABASE_URL="sqlite://",
        HR_ENABLE_SHADOW_TASKS=True, HR_BATCH_STATE_PATH=path)
    return TestClient(create_app(settings=settings, engine=engine))


def _shadow_job(engine, *, person="shadow-person", job="daren_live_bd_v1", raw_suffix="", request_plan=None):
    from liepin_rpa.analysis_intake import SyntheticCollectionIntake
    from liepin_rpa.analysis_jobs import AnalysisJobQueue, JobBudget
    from liepin_rpa.analysis_versioning import content_hash
    queue = AnalysisJobQueue(engine)
    text = "【纯合成影子履历】\n求职意向：上海。\n教育经历：统招本科。\n2020年独立开发 RAG 服务。\n" + raw_suffix
    decision = SyntheticCollectionIntake(queue).submit(batch_id="shadow-batch", candidate_fingerprint=person,
        candidate_name="虚构-" + person, job_profile_id=job, pipeline_fingerprint=content_hash("shadow-test-v1"),
        resume_record={"experience": text, "sections": {"求职意向": ["上海"]},
                       "education_experiences": [{"degree": "本科", "admission_type": "统招"}]},
        request_plan=request_plan, budget=JobBudget(**request_plan.budget_fields()) if request_plan else JobBudget())
    assert decision.state == "queued"
    return queue, decision.job_id


def _complete_shadow(queue, lease):
    item = queue.read_input(lease)
    scores = {"industry_depth": 20, "beauty_continuity": 15, "role_evidence": 55} if item.job_profile_id == "daren_live_bd_v1" else {
        "industry_depth": 30, "beauty_continuity": 20, "role_evidence": 40}
    queue.complete(lease, analyzed_text_hash=item.input_hash, payload={"beauty_relevant": True,
        "match_result": "relevant", "match_score": 90, "score_breakdown": scores,
        "analysis_reason": "预计算合成输出", "evidence_summary": ["2020年独立开发 RAG 服务。"]})


def test_shadow_execution_pending_has_empty_history_and_no_batch_totals(shadow_client, engine):
    _, job_id = _shadow_job(engine)
    login(shadow_client)
    response = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}")
    assert response.status_code == 200
    runtime = response.json()["execution"]
    assert runtime["attempts"] == [] and runtime["history_truncated"] is False
    assert runtime["charged_tokens"] == 0 and runtime["charged_cost_micros"] == 0
    assert runtime["mode"] == "synthetic_only"
    assert not any("batch" in key for key in runtime)


def test_shadow_execution_shows_attempts_without_inventing_request_receipts(shadow_client, engine):
    from sqlalchemy import update
    from liepin_rpa.analysis_job_schema import job_attempts
    queue, job_id = _shadow_job(engine)
    lease = queue.claim("private-worker-name")
    queue.fail(lease, "timeout")
    with engine.begin() as conn:
        conn.execute(update(job_attempts).where(job_attempts.c.job_id == job_id).values(error_code="PRIVATE-UPSTREAM"))
    login(shadow_client)
    before = queue.status("shadow-batch")
    runtime = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}").json()["execution"]
    attempt = runtime["attempts"][0]
    assert attempt["status"] == "failed" and attempt["error_code"] == "unknown_error"
    assert attempt["request"] is None and attempt["reported_tokens"] is None
    assert runtime["charged_tokens"] == attempt["reserved_tokens"] == 4000
    assert attempt["started_at"].endswith("Z") and attempt["finished_at"].endswith("Z")
    assert "private-worker" not in json.dumps(runtime) and "PRIVATE-UPSTREAM" not in json.dumps(runtime)
    assert queue.status("shadow-batch") == before  # The projection is read-only.


@pytest.mark.parametrize("usage", [(None, None), (0, 0), (23, None), (23, 7)])
def test_shadow_execution_preserves_partial_zero_and_unknown_usage(shadow_client, engine, usage):
    from liepin_rpa.queued_request_plan import QueuedRequestPlan, RequestOutcome
    from liepin_rpa.analysis_versioning import content_hash
    plan = QueuedRequestPlan.synthetic(system_prompt="PRIVATE-PROMPT")
    queue, job_id = _shadow_job(engine, request_plan=plan)
    lease = queue.claim("private-worker")
    queue.start_request(lease, plan_hash=plan.fingerprint, request_hash=content_hash("private-request"))
    login(shadow_client)
    running = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}").json()["execution"]
    assert running["attempts"][0]["request"]["state"] == "started"
    assert running["attempts"][0]["request"]["completed_at"] is None
    queue.record_request_outcome(lease, RequestOutcome(state="received", latency_ms=12.5,
        http_status=200, input_tokens=usage[0], output_tokens=usage[1], response_id_hash=content_hash("private-response-id")))
    queue.fail(lease, "invalid_output")
    detail = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}").json()
    runtime = detail["execution"]
    attempt = runtime["attempts"][0]
    receipt = attempt["request"]
    assert receipt["state"] == "received" and attempt["status"] == "failed" and detail["result"] is None
    assert (receipt["input_tokens"], receipt["output_tokens"]) == usage
    assert receipt["latency_ms"] == 12.5 and receipt["http_status"] == 200
    assert attempt["reported_tokens"] == (sum(usage) if None not in usage else None)
    assert attempt["reported_cost_micros"] == (plan.estimate_cost(*usage) if None not in usage else None)
    assert runtime["charged_tokens"] >= attempt["reserved_tokens"]
    assert all(secret not in json.dumps(runtime) for secret in ("PRIVATE-PROMPT", "private-worker", "request_hash",
                                                              "response_id_hash", "plan_json", "worker_id"))


def test_shadow_execution_does_not_join_receipts_from_another_job(shadow_client, engine):
    from sqlalchemy import update
    from liepin_rpa.analysis_job_schema import request_receipts
    from liepin_rpa.queued_request_plan import QueuedRequestPlan
    from liepin_rpa.analysis_versioning import content_hash
    plan = QueuedRequestPlan.synthetic()
    queue, job_id = _shadow_job(engine, request_plan=plan)
    lease = queue.claim("worker")
    queue.start_request(lease, plan_hash=plan.fingerprint, request_hash=content_hash("fixture"))
    _, other_id = _shadow_job(engine, person="secret-other", job="fde_ai_deployment_engineer_v1")
    # Deliberately inconsistent relation must not expose another job's receipt.
    with engine.begin() as conn:
        conn.execute(update(request_receipts).where(request_receipts.c.job_id == job_id).values(job_id=other_id))
    login(shadow_client)
    runtime = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}").json()["execution"]
    assert runtime["attempts"][0]["request"] is None
    assert shadow_client.get(f"/api/v1/analysis-tasks/{other_id}").status_code == 404


def test_shadow_execution_keeps_ordered_retry_history(shadow_client, engine):
    from datetime import timedelta
    queue, job_id = _shadow_job(engine)
    clock = [datetime(2026, 11, 5, 1)]
    queue.clock = lambda: clock[0]
    # Enqueue was at actual DB time; move beyond its availability within deadline.
    with engine.connect() as conn:
        from liepin_rpa.analysis_job_schema import analysis_jobs
        clock[0] = conn.scalar(select(analysis_jobs.c.available_at).where(analysis_jobs.c.job_id == job_id))
    first = queue.claim("worker")
    queue.fail(first, "timeout")
    clock[0] += timedelta(seconds=60)
    second = queue.claim("worker")
    _complete_shadow(queue, second)
    login(shadow_client)
    runtime = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}").json()["execution"]
    assert [(a["sequence"], a["status"]) for a in runtime["attempts"]] == [(1, "failed"), (2, "succeeded")]
    assert runtime["charged_tokens"] == 8000 and runtime["history_truncated"] is False


def test_shadow_execution_projects_real_sdk_loopback_receipt(shadow_client, engine):
    from eval.fixtures.sdk_server import SDKFixtureServer
    from liepin_rpa.queued_openai import QueuedOpenAIFixtureAdapter
    from liepin_rpa.queued_request_plan import QueuedRequestPlan
    from liepin_rpa.analysis_worker import AnalysisWorker
    plan = QueuedRequestPlan.synthetic(model="fixture-ok")
    queue, job_id = _shadow_job(engine, job="fde_ai_deployment_engineer_v1", request_plan=plan)
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        AnalysisWorker(queue, adapter).run_ready()
        assert len(server.requests) == 1
    login(shadow_client, "lead")
    response = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}")
    assert response.status_code == 200
    runtime = response.json()["execution"]
    assert len(runtime["attempts"]) == 1
    attempt = runtime["attempts"][0]
    assert attempt["request"]["state"] == "received" and attempt["request"]["http_status"] == 200
    assert attempt["reported_tokens"] == 385 and attempt["reported_cost_micros"] == 449
    assert runtime["charged_tokens"] == plan.input_reserve + plan.max_output_tokens


def test_shadow_tasks_are_disabled_by_default_and_require_login(client):
    assert client.get("/api/v1/analysis-tasks").status_code == 401
    login(client)
    assert client.get("/api/v1/analysis-tasks").json()["enabled"] is False
    assert client.get("/api/v1/analysis-tasks/unknown").status_code == 404


@pytest.mark.parametrize("username,visible", [("reviewer", 1), ("viewer", 1), ("lead", 2)])
def test_shadow_tasks_enforce_job_scope_and_allow_collection_pending(shadow_client, engine, username, visible):
    _, own = _shadow_job(engine)
    _, other = _shadow_job(engine, person="secret-fde", job="fde_ai_deployment_engineer_v1", raw_suffix="private-other-job-text")
    login(shadow_client, username)
    result = shadow_client.get("/api/v1/analysis-tasks").json()
    assert result["enabled"] and result["total"] == visible
    assert all(not row["collection_completed"] for row in result["items"])
    assert all("match_score" not in row for row in result["items"])
    own_detail = shadow_client.get(f"/api/v1/analysis-tasks/{own}")
    assert own_detail.status_code == 200
    assert own_detail.json()["result"] is None and own_detail.json()["result_status"] == "pending"
    assert shadow_client.get(f"/api/v1/analysis-tasks/{other}").status_code == (200 if username == "lead" else 404)
    if username != "lead":
        assert "secret-fde" not in json.dumps(result)
    assert shadow_client.get("/api/v1/analysis-tasks", params={"page_size": 101}).status_code == 422
    page = shadow_client.get("/api/v1/analysis-tasks", params={"page_size": 1, "page": 2}).json()
    assert len(page["items"]) == max(0, visible - 1)


def test_shadow_result_projection_preserves_all_legacy_and_manual_fields(shadow_client, engine):
    from liepin_rpa.analysis_job_schema import job_events
    with engine.connect() as conn:
        before = [dict(row) for row in conn.execute(select(resume_experience_records)).mappings()]
        caches = [dict(row) for row in conn.execute(select(resume_analysis_cache)).mappings()]
        manual_count = conn.scalar(select(func.count()).select_from(manual_review_events))
    queue, job_id = _shadow_job(engine, person="relevant-low")
    _complete_shadow(queue, queue.claim("test-worker"))
    queue.close_collection("shadow-batch")
    login(shadow_client)
    with engine.connect() as conn:
        event_count = conn.scalar(select(func.count()).select_from(job_events))
    for _ in range(2):
        data = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}").json()
        assert data["task"]["collection_completed"]
        assert data["result_status"] == "verified" and data["result"]["match_score"] == 90
        ref = data["evidence_refs"][0]
        assert data["snapshot"]["raw_text"][ref["start"]:ref["end"]] == ref["quote"]
    with engine.connect() as conn:
        assert before == [dict(row) for row in conn.execute(select(resume_experience_records)).mappings()]
        assert caches == [dict(row) for row in conn.execute(select(resume_analysis_cache)).mappings()]
        assert manual_count == conn.scalar(select(func.count()).select_from(manual_review_events))
        assert event_count == conn.scalar(select(func.count()).select_from(job_events))


def test_only_current_collected_tasks_are_projected(shadow_client, engine):
    from liepin_rpa.analysis_contract import DEFAULT_ANALYSIS_CONTRACT
    from liepin_rpa.analysis_versioning import content_hash
    queue, old = _shadow_job(engine)
    old_lease = queue.claim("old-worker")
    _, current = _shadow_job(engine, raw_suffix="新版正文")
    _complete_shadow(queue, old_lease)
    queue.enqueue(batch_id="shadow-batch", candidate_fingerprint="internal-tool-job", job_profile_id="daren_live_bd_v1",
        raw_text="无采集收据的内部任务", pipeline_fingerprint=content_hash("internal"), contract=DEFAULT_ANALYSIS_CONTRACT)
    login(shadow_client)
    response = shadow_client.get("/api/v1/analysis-tasks").json()
    assert [row["job_id"] for row in response["items"]] == [current]
    assert shadow_client.get(f"/api/v1/analysis-tasks/{old}").status_code == 404


@pytest.mark.parametrize("username", ["reviewer", "viewer"])
def test_shadow_retry_requires_lead_role(shadow_client, engine, username):
    queue, job_id = _shadow_job(engine)
    queue.fail(queue.claim("worker"), "timeout")
    csrf = login(shadow_client, username)
    task = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}").json()["task"]
    assert not task["can_retry"]
    assert shadow_client.post(f"/api/v1/analysis-tasks/{job_id}/retry", json={"lease_token": 1},
                             headers={"X-CSRF-Token": csrf}).status_code == 403


def test_shadow_retry_checks_csrf_version_and_never_resets_budget(shadow_client, engine):
    from liepin_rpa.analysis_job_schema import job_events
    queue, job_id = _shadow_job(engine)
    queue.fail(queue.claim("worker"), "timeout")
    csrf = login(shadow_client, "lead")
    url = f"/api/v1/analysis-tasks/{job_id}/retry"
    assert shadow_client.post(url, json={"lease_token": 1}).status_code == 403
    assert shadow_client.post(url, json={"lease_token": True}, headers={"X-CSRF-Token": csrf}).status_code == 422
    assert shadow_client.post(url, json={"lease_token": 0}, headers={"X-CSRF-Token": csrf}).status_code == 409
    before = queue.status("shadow-batch")
    assert shadow_client.post(url, json={"lease_token": 1}, headers={"X-CSRF-Token": csrf}).status_code == 200
    assert queue.status("shadow-batch") == before
    with engine.connect() as conn:
        actor = conn.execute(select(job_events.c.actor).where(job_events.c.event_type == "operator_retry")).scalar_one()
        assert actor == "hr-user:3"
    assert shadow_client.post(url, json={"lease_token": 1, "budget": 999}, headers={"X-CSRF-Token": csrf}).status_code == 422


def test_shadow_retry_cannot_bypass_429_or_other_job_scope(shadow_client, engine):
    queue, own = _shadow_job(engine)
    queue.fail(queue.claim("worker"), "rate_limited", retry_after_seconds=120)
    _, other = _shadow_job(engine, person="secret-fde", job="fde_ai_deployment_engineer_v1")
    csrf = login(shadow_client, "lead")
    assert not shadow_client.get(f"/api/v1/analysis-tasks/{own}").json()["task"]["can_retry"]
    assert shadow_client.post(f"/api/v1/analysis-tasks/{own}/retry", json={"lease_token": 1}, headers={"X-CSRF-Token": csrf}).status_code == 409
    shadow_client.post("/api/v1/auth/logout", headers={"X-CSRF-Token": csrf})
    csrf = login(shadow_client)
    assert shadow_client.post(f"/api/v1/analysis-tasks/{other}/retry", json={"lease_token": 0}, headers={"X-CSRF-Token": csrf}).status_code == 404


def test_shadow_corrupt_result_is_not_exposed(shadow_client, engine):
    from liepin_rpa.analysis_evidence_store import analysis_evidence_runs
    queue, job_id = _shadow_job(engine)
    _complete_shadow(queue, queue.claim("worker"))
    with engine.begin() as conn:
        conn.execute(analysis_evidence_runs.update().values(result_json='{"private":"UPSTREAM SECRET BODY"}'))
    login(shadow_client)
    response = shadow_client.get(f"/api/v1/analysis-tasks/{job_id}")
    assert response.status_code == 200 and response.json()["result_status"] == "invalid"
    assert response.json()["result"] is None and response.json()["evidence_refs"] == []
    assert "UPSTREAM SECRET" not in response.text


def test_shadow_feature_rejects_production_and_missing_migration(engine):
    with pytest.raises(ValidationError, match="合成影子任务"):
        HRReviewSettings(_env_file=None, APP_ENV="production", HR_REVIEW_DATABASE_URL="sqlite://",
            HR_ENABLE_SHADOW_TASKS=True, HR_COOKIE_SECURE=True, HR_ENABLE_OPENAPI=False)
    from liepin_rpa.analysis_job_schema import collection_receipts
    collection_receipts.drop(engine)
    with pytest.raises(ValueError, match="20261105_08"):
        create_app(engine=engine, settings=HRReviewSettings(_env_file=None, APP_ENV="development",
            HR_REVIEW_DATABASE_URL="sqlite://", HR_ENABLE_SHADOW_TASKS=True))


def test_demo_and_real_cookies_are_isolated_on_the_same_host(engine: Engine) -> None:
    real = TestClient(create_app(
        settings=HRReviewSettings(HR_REVIEW_DATABASE_URL="sqlite://"), engine=engine
    ))
    demo = TestClient(create_app(
        settings=HRReviewSettings(
            HR_REVIEW_DATABASE_URL="sqlite://", HR_COOKIE_PREFIX="hr_demo"
        ), engine=engine
    ))
    real_csrf = login(real)
    demo.cookies.update(real.cookies)
    assert demo.get("/api/v1/me").status_code == 401
    response = demo.post(
        "/api/v1/auth/login", json={"username": "reviewer", "password": "correct-password"}
    )
    assert response.status_code == 200
    demo_csrf = demo.cookies["hr_demo_csrf"]
    assert demo_csrf != real_csrf
    real.cookies.update(demo.cookies)
    assert real.get("/api/v1/me").status_code == 200
    assert demo.get("/api/v1/me").status_code == 200
    assert demo.post(
        "/api/v1/auth/logout", headers={"X-CSRF-Token": real_csrf}
    ).status_code == 403
    assert real.post(
        "/api/v1/auth/logout", headers={"X-CSRF-Token": real_csrf}
    ).status_code == 200
    assert "hr_review_session" not in real.cookies
    assert "hr_demo_session" in real.cookies
    assert demo.get("/api/v1/me").status_code == 200
    assert demo.post(
        "/api/v1/auth/logout", headers={"X-CSRF-Token": demo_csrf}
    ).status_code == 200
    assert "hr_demo_csrf" not in demo.cookies
    real.close()
    demo.close()


def test_cookie_prefix_rejects_invalid_cookie_names() -> None:
    with pytest.raises(ValidationError):
        HRReviewSettings(HR_REVIEW_DATABASE_URL="sqlite://", HR_COOKIE_PREFIX="bad; name")


@pytest.mark.parametrize("state,result", [("transient_error", "needs_review"), ("blocked", "relevant")])
def test_structured_failure_is_excluded_even_with_high_score_and_changed_reason(
    client: TestClient, engine: Engine, state: str, result: str
) -> None:
    with engine.begin() as conn:
        conn.execute(resume_analysis_cache.update().where(
            resume_analysis_cache.c.candidate_fingerprint == "temporary-failure"
        ).values(
            execution_status=state, match_result=result, match_score=99,
            analysis_reason="展示文案已经修改，不包含供应商名或异常关键词",
        ))
    login(client)
    queue = client.get("/api/v1/review-queue", params={"scope": "all"})
    assert queue.status_code == 200
    assert "temporary-failure" not in {
        item["candidate_fingerprint"] for item in queue.json()["items"]
    }


def _bind_test_evidence(engine, *, fingerprint="relevant-low", run_id="test-evidence-run", job="daren_live_bd_v1"):
    with engine.begin() as conn:
        source = conn.execute(select(resume_experience_records).where(
            resume_experience_records.c.candidate_fingerprint == fingerprint
        )).mappings().one()
        cache = conn.execute(select(resume_analysis_cache).where(
            resume_analysis_cache.c.candidate_fingerprint == fingerprint
        )).mappings().one()
        text = source["experience"]
        quote = "负责达人业务" if fingerprint == "relevant-low" else text
        summaries = [quote, "只是摘要，不应猜测原文"]
        breakdown = {"industry_depth": 24, "beauty_continuity": 14, "role_evidence": 57}
        persist_analysis_evidence(
            conn, analysis_run_id=run_id, candidate_fingerprint=fingerprint, job_profile_id=job,
            analysis_version=cache["analysis_version"], raw_text=text, analyzed_text_hash=resume_text_hash(text),
            binding_source="synthetic_fixture",
            result=result_document(match_result=cache["match_result"], match_score=cache["match_score"],
                                   score_breakdown=breakdown, analysis_reason=cache["analysis_reason"],
                                   evidence_summary=summaries, execution_status="succeeded"),
        )
        conn.execute(resume_analysis_cache.update().where(resume_analysis_cache.c.id == cache["id"]).values(
            experience=text, score_breakdown_json=json.dumps(breakdown), evidence_summary_json=json.dumps(summaries),
            execution_status="succeeded", analysis_run_id=run_id, analyzed_text_hash=resume_text_hash(text),
        ))
    return text, quote


def test_bound_detail_returns_exact_snapshot_without_creating_data_on_read(client, engine):
    text, quote = _bind_test_evidence(engine)
    login(client)
    with engine.connect() as conn:
        before = conn.scalar(select(func.count()).select_from(resume_snapshots))
    response = client.get("/api/v1/candidates/relevant-low")
    assert response.status_code == 200
    body = response.json()
    assert body["evidence_status"] == "verified"
    assert body["analysis_run_id"] == "test-evidence-run"
    assert body["analysis_snapshot"]["raw_text"] == text
    assert body["analysis_snapshot"]["is_current"]
    assert body["evidence_summary"] == [quote, "只是摘要，不应猜测原文"]
    assert len(body["evidence_refs"]) == 1
    ref = body["evidence_refs"][0]
    assert text[ref["start"]:ref["end"]] == quote
    with engine.connect() as conn:
        assert conn.scalar(select(func.count()).select_from(resume_snapshots)) == before


def test_snapshot_remains_historical_after_master_resume_changes(client, engine):
    original, quote = _bind_test_evidence(engine)
    with engine.begin() as conn:
        conn.execute(resume_experience_records.update().where(
            resume_experience_records.c.candidate_fingerprint == "relevant-low"
        ).values(experience="新采集的简历，原项目已修改"))
    login(client)
    body = client.get("/api/v1/candidates/relevant-low").json()
    assert not body["analysis_snapshot"]["is_current"]
    assert body["analysis_snapshot"]["raw_text"] == original
    ref = body["evidence_refs"][0]
    assert original[ref["start"]:ref["end"]] == quote


def test_foreign_run_pointer_cannot_leak_another_job_resume(client, engine):
    _bind_test_evidence(engine, fingerprint="finance-hidden", run_id="finance-only-run", job="finance_shared_management_v1")
    with engine.begin() as conn:
        conn.execute(resume_analysis_cache.update().where(
            resume_analysis_cache.c.candidate_fingerprint == "relevant-low"
        ).values(analysis_run_id="finance-only-run"))
    login(client)
    response = client.get("/api/v1/candidates/relevant-low")
    assert response.status_code == 200
    body = response.json()
    assert body["evidence_status"] == "invalid"
    assert body["analysis_snapshot"] is None and body["evidence_refs"] == []
    assert body["analysis_run_id"] is None
    assert client.get("/api/v1/candidates/finance-hidden").status_code == 404


def test_legacy_detail_does_not_fabricate_snapshot(client):
    login(client)
    body = client.get("/api/v1/candidates/relevant-low").json()
    assert body["evidence_status"] == "legacy"
    assert body["analysis_snapshot"] is None and body["evidence_refs"] == []


def test_evidence_search_is_read_only_snapshot_scoped_and_uses_server_job_plan(client, engine):
    text, _ = _bind_test_evidence(engine)
    login(client)
    with engine.connect() as conn:
        before = conn.scalar(select(func.count()).select_from(resume_snapshots))
    response = client.get("/api/v1/candidates/relevant-low/evidence-search", params={"dimension_id": "role_evidence"})
    assert response.status_code == 200
    result = response.json()
    assert result["status"] == "available" and result["analysis_run_id"] == "test-evidence-run"
    assert result["dimension_label"] == "岗位职责"
    assert result["hits"]
    detail = client.get("/api/v1/candidates/relevant-low").json()
    assert result["snapshot_id"] == detail["analysis_snapshot"]["snapshot_id"]
    for hit in result["hits"]:
        assert text[hit["start"]:hit["end"]] == hit["quote"]
        assert hit["snapshot_id"] == result["snapshot_id"]
    with engine.connect() as conn:
        assert conn.scalar(select(func.count()).select_from(resume_snapshots)) == before


def test_evidence_search_rejects_unauthorized_jobs_and_invalid_dimensions(client, engine):
    _bind_test_evidence(engine)
    login(client)
    assert client.get("/api/v1/candidates/finance-hidden/evidence-search").status_code == 404
    assert client.get("/api/v1/candidates/relevant-low/evidence-search", params={"dimension_id": "../../.env"}).status_code == 422


def test_evidence_search_does_not_fabricate_legacy_source(client):
    login(client)
    result = client.get("/api/v1/candidates/relevant-low/evidence-search").json()
    assert result["status"] == "unavailable"
    assert result["snapshot_id"] is None and result["hits"] == []


def test_evidence_search_uses_frozen_text_after_current_resume_changes(client, engine):
    original, _ = _bind_test_evidence(engine)
    with engine.begin() as conn:
        conn.execute(resume_experience_records.update().where(
            resume_experience_records.c.candidate_fingerprint == "relevant-low"
        ).values(experience="更新后的不同正文"))
    login(client)
    result = client.get("/api/v1/candidates/relevant-low/evidence-search").json()
    assert result["status"] == "available"
    assert all(original[h["start"]:h["end"]] == h["quote"] for h in result["hits"])


def test_bound_analysis_job_scope_is_applied_before_picking_latest(client, engine):
    original, _ = _bind_test_evidence(engine)
    foreign_text = "仅财务岗位授权的分析输入"
    foreign_result = result_document(
        match_result="relevant", match_score=96,
        score_breakdown={"industry_depth": 35, "beauty_continuity": 25, "role_evidence": 36},
        analysis_reason="财务岗位私有结论", evidence_summary=[foreign_text], execution_status="succeeded",
    )
    with engine.begin() as conn:
        persist_analysis_evidence(
            conn, analysis_run_id="later-finance-run", candidate_fingerprint="relevant-low",
            job_profile_id="finance_shared_management_v1", analysis_version="private-finance-v2",
            raw_text=foreign_text, analyzed_text_hash=resume_text_hash(foreign_text),
            result=foreign_result, binding_source="synthetic_fixture",
        )
        conn.execute(insert(resume_analysis_cache).values(
            id=4000, candidate_fingerprint="relevant-low", experience_hash="private-finance-hash",
            experience=foreign_text, analysis_version="private-finance-v2", analysis_status="匹配",
            match_result="relevant", match_score=96, score_breakdown_json=json.dumps(foreign_result["score_breakdown"]),
            analysis_reason=foreign_result["analysis_reason"], evidence_summary_json=json.dumps([foreign_text]),
            execution_status="succeeded", analysis_run_id="later-finance-run",
            analyzed_text_hash=resume_text_hash(foreign_text),
        ))
    login(client)
    response = client.get("/api/v1/candidates/relevant-low")
    assert response.status_code == 200
    body = response.json()
    assert body["analysis_run_id"] == "test-evidence-run"
    assert body["analysis_snapshot"]["raw_text"] == original
    assert foreign_text not in response.text
    assert "财务岗位私有结论" not in response.text


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
    # Selecting a job is not evidence that a greeting was sent.
    assert by_fingerprint["review-90"]["communication_status"] == "not_sent"
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
        "sent": {"relevant-low"},
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
    not_sent_fingerprints = {item["candidate_fingerprint"] for item in not_sent.json()["items"]}
    assert "relevant-low" not in not_sent_fingerprints
    assert "review-90" in not_sent_fingerprints


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


def test_production_http_requires_explicit_opt_in() -> None:
    settings = {
        "_env_file": None,
        "APP_ENV": "production",
        "HR_REVIEW_DATABASE_URL": "sqlite+pysqlite://",
        "HR_COOKIE_SECURE": False,
        "HR_ENABLE_OPENAPI": False,
    }
    with pytest.raises(ValidationError, match="HR_COOKIE_SECURE"):
        HRReviewSettings(**settings, HR_ALLOW_HTTP=False)
    assert HRReviewSettings(**settings, HR_ALLOW_HTTP=True).allow_http
    settings["HR_ENABLE_OPENAPI"] = True
    with pytest.raises(ValidationError, match="HR_ENABLE_OPENAPI"):
        HRReviewSettings(**settings, HR_ALLOW_HTTP=True)


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
            (2, "review-90", "待复核", 90),
            (3, "review-89", "待复核", 89),
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
