"""Exercise the installed SDK against an ephemeral loopback fixture, never a model."""
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import uuid

from sqlalchemy import create_engine, event, select

from eval.fixtures.sdk_server import PAYLOAD, SDKFixtureServer
from eval.run_eval import _source_manifest
from liepin_rpa.analysis_contract import DEFAULT_ANALYSIS_CONTRACT
from liepin_rpa.analysis_evidence_store import evidence_metadata
from liepin_rpa.analysis_job_schema import job_attempts, request_receipts, queue_metadata
from liepin_rpa.analysis_jobs import AnalysisJobQueue, JobBudget
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.analysis_worker import AnalysisWorker
from liepin_rpa.queued_openai import QueuedOpenAIFixtureAdapter
from liepin_rpa.queued_request_plan import QueuedRequestPlan

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = ("fixture-ok", "fixture-rate-once", "fixture-503-once", "fixture-auth", "fixture-quota",
             "fixture-long-retry", "fixture-bad-json", "fixture-bad-score", "fixture-length", "fixture-missing-usage",
             "fixture-partial-usage", "fixture-model-mismatch", "fixture-refusal", "fixture-negative-usage")


def simulate(run_dir):
    database = run_dir / "queue.sqlite"
    engine = create_engine(f"sqlite+pysqlite:///{database.as_posix()}")
    @event.listens_for(engine, "connect")
    def foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")
    evidence_metadata.create_all(engine)
    queue_metadata.create_all(engine)
    clock = [datetime(2026, 11, 5, 1)]
    queue = AnalysisJobQueue(engine, clock=lambda: clock[0])
    queue.configure()
    queue.create_batch("sdk-synthetic-batch")
    plans = []
    try:
        for index, scenario in enumerate(SCENARIOS):
            plan = QueuedRequestPlan.synthetic(model=scenario)
            plans.append(plan)
            queue.enqueue(batch_id="sdk-synthetic-batch", candidate_fingerprint=f"sdk-synthetic-{index:02d}",
                job_profile_id="fde_ai_deployment_engineer_v1", raw_text="【纯合成 SDK 协议夹具】\n2020年起，独立开发 RAG 服务。\n",
                pipeline_fingerprint=content_hash("sdk-fixture-pipeline-v1"), contract=DEFAULT_ANALYSIS_CONTRACT,
                request_plan=plan, budget=JobBudget(**plan.budget_fields()))
        queue.close_collection("sdk-synthetic-batch")
        passes = []
        with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
            for _ in range(4):
                passes.append(AnalysisWorker(queue, adapter).run_ready())
                if queue.status("sdk-synthetic-batch")["analysis_pending"] == 0:
                    break
                clock[0] += timedelta(seconds=75)
            observed = list(server.requests)
        status = queue.status("sdk-synthetic-batch")
        with engine.connect() as connection:
            receipts = [dict(row) for row in connection.execute(select(request_receipts)).mappings()]
            attempts = [dict(row) for row in connection.execute(select(job_attempts)).mappings()]
        assert status["analysis_pending"] == 0
        assert len(observed) == len(receipts) == len(attempts) == status["charged_requests"]
        assert all(receipt["outcome_hash"] for receipt in receipts)
        assert all(not item["custom_header_seen"] for item in observed)
        report = {"schema_version": 1, "mode": "real_sdk_loopback_fixture_only", "simulated": True,
            "quality_claim_allowed": False, "real_model_calls": 0, "real_model_cost": None,
            "real_provider_latency": None, "real_model_quality": None,
            "source_manifest": _source_manifest(), "output_fixture_hash": content_hash(PAYLOAD),
            "sdk_version": plans[0].sdk_version, "implementation_hash": plans[0].implementation_hash,
            "plan_hashes": [plan.fingerprint for plan in plans], "scenario_count": len(SCENARIOS),
            "observed_http_requests": len(observed), "reserved_attempts": len(attempts), "request_receipts": len(receipts),
            "request_counts_match": True, "receipt_states": dict(Counter(row["state"] for row in receipts)),
            "receipt_errors": dict(Counter(row["error_code"] or "none" for row in receipts)),
            "unknown_usage_count": sum(row["reported_tokens"] is None for row in receipts),
            "unknown_cost_count": sum(row["reported_cost_micros"] is None for row in receipts),
            "known_partial_estimated_cost_micros": sum(row["reported_cost_micros"] or 0 for row in receipts),
            "total_estimated_cost_micros": sum(row["reported_cost_micros"] for row in receipts) if all(row["reported_cost_micros"] is not None for row in receipts) else None,
            "currency": plans[0].currency, "price_version": plans[0].price_version, "batch": status, "worker_passes": passes,
            "limitations": ["The SDK uses real HTTP to an ephemeral local fixture; no remote model or key was used.",
                "Token counts and prices are declared fixtures; the input-unit convention is not a real tokenizer.",
                "Per-request latency is local protocol time, not provider/model performance.",
                "Timeouts do not cancel already-started work; unknown usage keeps reservations."],
        }
        (run_dir / "request_receipts.jsonl").write_text("".join(
            json.dumps(row, ensure_ascii=False, default=lambda v: v.isoformat()) + "\n" for row in receipts), encoding="utf-8")
        return report
    finally:
        engine.dispose()


def main():
    run_id = "sdk-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    directory = ROOT / "artifacts/analysis_queue" / run_id
    directory.mkdir(parents=True, exist_ok=False)
    report = simulate(directory)
    report["run_id"] = run_id
    path = directory / "report.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Real SDK, synthetic loopback only; no model calls: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
