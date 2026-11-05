"""Synthetic collection -> durable SDK tools -> HR projection -> manual review integration."""
from decimal import Decimal
import json
import hashlib
from pathlib import Path
import uuid

from fastapi.testclient import TestClient
from sqlalchemy import insert, select, func

from scripts.run_hr_demo import build_demo_app
from liepin_rpa.analysis_intake import SyntheticCollectionIntake
from liepin_rpa.analysis_jobs import AnalysisJobQueue, JobBudget
from liepin_rpa.analysis_job_schema import tool_rounds
from liepin_rpa.analysis_observability import Price
from liepin_rpa.analysis_projection import project_current_result
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.bounded_tools.durable import run_durable_tools
from liepin_rpa.bounded_tools.sdk_fixture import SDKToolFixtureProvider
from liepin_rpa.db import resume_source_locators, resume_analysis_cache
from eval.fixtures.tool_sdk_server import ToolSDKFixtureServer
from eval.run_eval import _source_manifest

QUOTE = "独立开发检索问答服务并负责上线。"
TEXT = "【纯虚构集成样本】\n教育经历：统招本科。\n求职意向：上海。\n项目经历：" + QUOTE
RECORD = {"experience": TEXT, "_analysis_strategy": "tools", "sections": {"求职意向": ["上海"]},
          "education_experiences": [{"degree": "本科", "admission_type": "统招"}]}
PAYLOAD = {"beauty_relevant": True, "match_result": "relevant", "match_score": 90,
    "score_breakdown": {"industry_depth": 30, "beauty_continuity": 20, "role_evidence": 40},
    "analysis_reason": "预置合成输出，不是模型质量证据", "evidence_summary": [QUOTE]}


def simulate(directory):
    directory = Path(directory)
    app = build_demo_app(scenario="ai", include_shadow=True, demo_dir=directory / "demo", queue_projection=True)
    engine = app.state.services.candidates.query_service.engine
    try:
        queue = AnalysisJobQueue(engine)
        queue.create_batch("synthetic-ai-1", max_tokens=1000000, max_cost_micros=1000000)
        intake = SyntheticCollectionIntake(queue)
        kwargs = {"batch_id": "synthetic-ai-1", "candidate_fingerprint": "remaining-integration-person",
            "candidate_name": "虚构集成候选人", "job_profile_id": "ai_development_engineer_v1",
            "pipeline_fingerprint": content_hash("remaining-integration-v1"), "budget": JobBudget(max_tokens=200000, max_cost_micros=200000)}
        decision = intake.submit(**kwargs, resume_record=RECORD)
        assert intake.submit(**kwargs, resume_record=RECORD).job_id == decision.job_id
        with engine.begin() as connection:
            connection.execute(insert(resume_source_locators).values(
                id=10001, candidate_fingerprint=kwargs["candidate_fingerprint"], candidate_name=kwargs["candidate_name"],
                job_profile_id=kwargs["job_profile_id"], search_keyword="AI 开发工程师", search_batch_id=kwargs["batch_id"],
                locator_quality="snapshot"))
        lease = queue.claim("integration-worker", strategy="tools")
        assert lease.job_id == decision.job_id
        item = queue.read_input(lease)
        price = Price("synthetic-fixed-v1", Decimal("1"), Decimal("2"))
        with ToolSDKFixtureServer(payload=PAYLOAD, quote=QUOTE) as server:
            with SDKToolFixtureProvider(queue, base_url=server.base_url) as provider:
                result, traffic = run_durable_tools(queue, item, provider, price=price, as_of_month="2026.11")
                assert result.state == "succeeded" and traffic["new_requests"] == 2
                replay, cached = run_durable_tools(queue, item, provider, price=price, as_of_month="2026.11")
                assert replay.state == "succeeded" and cached["replayed_responses"] == 2 and cached["new_requests"] == 0
            requests = len(server.requests)
        assert requests == 2
        queue.complete(lease, payload=result.payload, analyzed_text_hash=item.input_hash)
        assert project_current_result(queue, decision.job_id) == "projected"
        with TestClient(app) as client:
            assert client.post("/api/v1/auth/login", json={"username": "demo", "password": "DemoReview2026!"}).status_code == 200
            task = client.get(f"/api/v1/analysis-tasks/{decision.job_id}").json()
            assert len(task["execution"]["tool_rounds"]) == 2
            assert "reply_json" not in json.dumps(task)
            url = f"/api/v1/candidates/{kwargs['candidate_fingerprint']}"
            response = client.get(url)
            assert response.status_code == 200
            detail = response.json()
            assert detail["evidence_status"] == "verified" and detail["evidence_refs"][0]["quote"] == QUOTE
            payload = {"review_status": "on_hold", "revision": detail["summary"]["revision"],
                       "feedback": "仅在合成库验证审核保存", "assignee_id": None}
            headers = {"X-CSRF-Token": client.cookies["hr_ai_demo_csrf"]}
            assert client.patch(url + "/review", json=payload, headers=headers).status_code == 200
            assert client.patch(url + "/review", json=payload, headers=headers).status_code == 409
            assert project_current_result(queue, decision.job_id) == "already_projected"
            assert client.get(url).json()["manual_review_feedback"] == payload["feedback"]
            newer = intake.submit(**kwargs, resume_record={**RECORD, "experience": TEXT + "\n新版本经历"})
            assert newer.job_id != decision.job_id
            assert project_current_result(queue, decision.job_id) == "not_current"
            assert client.get(url).status_code == 404  # An older queued score cannot masquerade as current.
            assert client.post("/api/v1/auth/logout", headers=headers).status_code == 200
        with engine.connect() as connection:
            rounds = connection.scalar(select(func.count()).select_from(tool_rounds).where(tool_rounds.c.job_id == decision.job_id))
        return {"mode": "synthetic_remaining_integration_only", "status": "passed", "quality_claim_allowed": False,
            "real_model_calls": 0, "release_approved": False, "observed_loopback_http_requests": requests,
            "durable_tool_rounds": rounds, "replayed_responses": 2, "qualification_and_snapshot_frozen": True,
            "hr_projection_verified": True, "synthetic_manual_review_and_conflict_verified": True,
            "new_version_hides_old_projection": True, "production_database_connected": False,
            "source_manifest": _source_manifest(), "pipeline_fingerprint": result.pipeline_fingerprint,
            "entrypoint_hashes": {name: hashlib.sha256((Path(__file__).resolve().parents[1] / name).read_bytes()).hexdigest()
                for name in ("scripts/run_search_workflow.py", "scripts/run_analysis_worker.py", "scripts/run_hr_demo.py",
                             "scripts/run_integrated_demo.py")},
            "expected_schema_revision": "20261105_08", "schema_initialization": "fresh_metadata_not_migration_test"}
    finally:
        engine.dispose()


def main():
    directory = Path("artifacts/remaining") / f"remaining-{uuid.uuid4().hex[:12]}"
    directory.mkdir(parents=True)
    try:
        report = simulate(directory)
    except Exception as error:
        report = {"status": "failed", "error_type": type(error).__name__, "release_approved": False}
    (directory / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Remaining integration {report['status']}: {directory / 'report.json'}")
    return int(report["status"] != "passed")


if __name__ == "__main__":
    raise SystemExit(main())
