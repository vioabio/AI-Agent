from fastapi.testclient import TestClient
import pytest

from scripts import run_hr_demo


@pytest.mark.parametrize("role", ["hr", "read_only"])
def test_ai_demo_job_permissions_and_review_revision_are_preserved(tmp_path, role):
    from sqlalchemy import insert, select
    from apps.hr_review_api.persistence.tables import hr_users, hr_user_job_permissions
    app = run_hr_demo.build_demo_app(scenario="ai", demo_dir=tmp_path / role)
    engine = app.state.services.candidates.query_service.engine
    with engine.begin() as conn:
        password = conn.scalar(select(hr_users.c.password_hash).where(hr_users.c.username == "demo"))
        conn.execute(insert(hr_users).values(id=100, username="ai-only", display_name="AI岗位测试员",
            password_hash=password, role=role, is_active=True, can_view_sensitive=False))
        conn.execute(insert(hr_user_job_permissions).values(user_id=100, job_profile_id="ai_development_engineer_v1"))
    with TestClient(app) as client:
        assert client.post("/api/v1/auth/login", json={"username": "ai-only", "password": "DemoReview2026!"}).status_code == 200
        rows = client.get("/api/v1/review-queue", params={"scope": "all"}).json()["items"]
        assert rows and {row["job_profile_id"] for row in rows} == {"ai_development_engineer_v1"}
        assert client.get("/api/v1/review-queue", params={"job_profile_id": "fde_ai_deployment_engineer_v1"}).status_code == 403
        assert client.get("/api/v1/candidates/demo-ai-002").status_code == 404
        url = "/api/v1/candidates/demo-ai-001/review"
        payload = {"review_status": "recommended_contact", "feedback": "纯虚构测试，核验项目职责", "revision": 0, "assignee_id": None}
        headers = {"X-CSRF-Token": client.cookies["hr_ai_demo_csrf"]}
        reviewed = client.patch(url, json=payload, headers=headers)
        assert reviewed.status_code == (200 if role == "hr" else 403)
        if role == "hr":
            assert reviewed.json()["summary"]["revision"] == 1
            assert reviewed.json()["history"][-1]["feedback"] == payload["feedback"]
            assert client.patch(url, json=payload, headers=headers).status_code == 409
        assert client.get("/api/v1/candidates/demo-ai-001").json()["evidence_status"] == "verified"
    engine.dispose()


def test_ai_demo_has_two_jobs_three_states_valid_contracts_and_isolated_files(tmp_path):
    from liepin_rpa.analysis_contract import analysis_contract_from_job_profile, validate_analysis_payload
    from liepin_rpa.analysis_intake import PROFILE_FILES, ROOT
    from liepin_rpa.db import resume_analysis_cache, resume_source_locators
    from sqlalchemy import select
    import yaml
    import json
    directory = tmp_path / "ai"
    app = run_hr_demo.build_demo_app(scenario="ai", include_shadow=True, demo_dir=directory)
    with TestClient(app) as client:
        assert client.post("/api/v1/auth/login", json={"username": "demo", "password": "DemoReview2026!"}).status_code == 200
        assert "hr_ai_demo_session" in client.cookies and "hr_demo_session" not in client.cookies
        result = client.get("/api/v1/review-queue", params={"scope": "all", "page_size": 100}).json()
        assert result["total"] > 0
        assert all(row["match_score"] >= 90 and row["match_result"] != "not_relevant" for row in result["items"])
        for job in ("ai_development_engineer_v1", "fde_ai_deployment_engineer_v1"):
            scoped = client.get("/api/v1/review-queue", params={"scope": "all", "job_profile_id": job, "page_size": 100})
            assert scoped.status_code == 200
            assert {row["job_profile_id"] for row in scoped.json()["items"]} == {job}
            with app.state.services.candidates.query_service.engine.connect() as conn:
                rows = conn.execute(select(resume_analysis_cache).join(resume_source_locators,
                    resume_source_locators.c.candidate_fingerprint == resume_analysis_cache.c.candidate_fingerprint)
                    .where(resume_source_locators.c.job_profile_id == job)).mappings().all()
            assert len(rows) == 48
            assert {row["match_result"] for row in rows} == {"relevant", "needs_review", "not_relevant"}
            profile = yaml.safe_load((ROOT / "config/job_profiles" / PROFILE_FILES[job]).read_text(encoding="utf-8"))
            contract = analysis_contract_from_job_profile(profile)
            for row in rows:
                detail = client.get(f"/api/v1/candidates/{row['candidate_fingerprint']}").json()
                assert detail["evidence_status"] == "verified"
                payload = {key: row[key] for key in ("match_result", "match_score", "analysis_reason")}
                payload.update(score_breakdown=json.loads(row["score_breakdown_json"]),
                               evidence_summary=json.loads(row["evidence_summary_json"]))
                payload["beauty_relevant"] = payload["match_result"] == "relevant"
                assert not validate_analysis_payload(payload, contract=contract)
            assert client.get("/api/v1/dashboard/summary", params={"job_profile_id": job}).status_code == 200
            report = client.get("/api/v1/reports/live-preview", params={"batch_id": "synthetic-ai-1", "job_profile_id": job})
            assert report.status_code == 200
            assert all(report.json()[key] == 4 for key in ("ai_relevant", "ai_needs_review", "ai_not_relevant"))
        assert (directory / "demo-v6-ai.sqlite3").is_file()
        assert not (directory / "demo-v4.sqlite3").exists() and not (directory / "demo-v5-shadow.sqlite3").exists()
    app.state.services.candidates.query_service.engine.dispose()


def test_ai_demo_restart_preserves_operator_changes_and_other_demo_files(tmp_path):
    from sqlalchemy import select, update, func
    from liepin_rpa.db import resume_experience_records
    from apps.hr_review_api.persistence.tables import manual_review_events
    directory = tmp_path / "demo"
    directory.mkdir()
    for name in ("demo-v4.sqlite3", "demo-v5-shadow.sqlite3"):
        (directory / name).write_bytes(b"untouched-older-demo")
    app = run_hr_demo.build_demo_app(scenario="ai", demo_dir=directory)
    engine = app.state.services.candidates.query_service.engine
    with engine.begin() as conn:
        conn.execute(update(resume_experience_records).where(
            resume_experience_records.c.candidate_fingerprint == "demo-ai-001").values(manual_review_feedback="保留的操作员备注"))
        events = conn.scalar(select(func.count()).select_from(manual_review_events))
    engine.dispose()
    app = run_hr_demo.build_demo_app(scenario="ai", demo_dir=directory)
    engine = app.state.services.candidates.query_service.engine
    with engine.connect() as conn:
        assert conn.scalar(select(resume_experience_records.c.manual_review_feedback).where(
            resume_experience_records.c.candidate_fingerprint == "demo-ai-001")) == "保留的操作员备注"
        assert conn.scalar(select(func.count()).select_from(resume_experience_records)) == 96
        assert conn.scalar(select(func.count()).select_from(manual_review_events)) == events
    engine.dispose()
    assert all((directory / name).read_bytes() == b"untouched-older-demo" for name in ("demo-v4.sqlite3", "demo-v5-shadow.sqlite3"))


def test_demo_uses_only_fresh_local_sqlite_even_with_production_environment(tmp_path, monkeypatch):
    monkeypatch.setattr(run_hr_demo, "DEMO_DIR", tmp_path / "demo")
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("HR_REVIEW_DATABASE_URL", "mysql+pymysql://invalid.invalid/no-access")
    monkeypatch.setenv("HR_COOKIE_PREFIX", "real_cookie")
    # A local .env must not override explicit isolated demo settings either.
    monkeypatch.chdir(tmp_path)
    (tmp_path / ".env").write_text(
        "APP_ENV=production\nHR_REVIEW_DATABASE_URL=mysql+pymysql://invalid.invalid/no-access\n",
        encoding="utf-8",
    )
    app = run_hr_demo.build_demo_app()
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/auth/login", json={"username": "demo", "password": "DemoReview2026!"}
        )
        assert response.status_code == 200
        assert "hr_demo_session" in client.cookies
        assert "hr_review_session" not in client.cookies
        assert client.get("/api/v1/me").status_code == 200
        queue = client.get("/api/v1/review-queue", params={"scope": "all"})
        assert queue.status_code == 200
        assert queue.json()["total"] > 0
    assert (tmp_path / "demo" / "demo-v4.sqlite3").is_file()


def test_shadow_demo_is_isolated_idempotent_and_exposes_only_synthetic_tasks(tmp_path, monkeypatch):
    monkeypatch.setattr(run_hr_demo, "DEMO_DIR", tmp_path / "demo")
    app = run_hr_demo.build_shadow_demo_app()
    with TestClient(app) as client:
        assert client.post("/api/v1/auth/login", json={"username": "demo", "password": "DemoReview2026!"}).status_code == 200
        data = client.get("/api/v1/analysis-tasks").json()
        assert data["enabled"] and data["total"] == 7
        assert any(row["can_retry"] for row in data["items"])
        assert {row["source_type"] for row in data["items"]} == {"synthetic"}
    again = run_hr_demo.build_shadow_demo_app()
    with TestClient(again) as client:
        client.post("/api/v1/auth/login", json={"username": "demo", "password": "DemoReview2026!"})
        assert client.get("/api/v1/analysis-tasks").json() == data
    assert (tmp_path / "demo" / "demo-v5-shadow.sqlite3").is_file()
    assert not (tmp_path / "demo" / "demo-v4.sqlite3").exists()
