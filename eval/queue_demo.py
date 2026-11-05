"""Run synthetic durable-queue failure scenarios; never initialize live settings."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import uuid

from sqlalchemy import create_engine, event, select
import yaml

from eval.run_eval import _source_manifest
from liepin_rpa.analysis_contract import analysis_contract_from_job_profile
from liepin_rpa.analysis_evidence_store import analysis_evidence_runs, evidence_metadata
from liepin_rpa.analysis_job_schema import analysis_jobs, job_attempts, job_heads, queue_metadata
from liepin_rpa.analysis_jobs import AnalysisJobQueue, JobBudget, LeaseLost
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.analysis_worker import AnalysisWorker, WorkerFailure, WorkerOutput

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = ROOT / "artifacts/analysis_queue"
TEXT = "【虚构队列演示，非真实简历】\n教育经历：统招本科。\n求职意向：上海。\n2020年起独立开发 RAG 服务。\n"
PAYLOAD = {"beauty_relevant": True, "match_result": "relevant", "match_score": 90,
           "score_breakdown": {"industry_depth": 30, "beauty_continuity": 20, "role_evidence": 40},
           "analysis_reason": "预计算的合成队列演示结果，未调用模型",
           "evidence_summary": ["2020年起独立开发 RAG 服务。"]}


class DemoClock:
    def __init__(self):
        self.value = datetime(2026, 11, 5, 1)

    def __call__(self):
        return self.value

    def advance(self, seconds):
        self.value += timedelta(seconds=seconds)


def open_engine(database):
    engine = create_engine(f"sqlite+pysqlite:///{database.as_posix()}")

    @event.listens_for(engine, "connect")
    def foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")

    return engine


def simulate(run_dir):
    database = run_dir / "queue.sqlite"
    clock = DemoClock()
    profiles = {}
    for filename in ("ai_development_engineer.yaml", "fde_ai_deployment_engineer_draft.yaml"):
        profile = yaml.safe_load((ROOT / "config/job_profiles" / filename).read_text(encoding="utf-8"))
        profiles[profile["id"]] = profile
    ai, fde = "ai_development_engineer_v1", "fde_ai_deployment_engineer_v1"
    engine = open_engine(database)
    evidence_metadata.create_all(engine)
    queue_metadata.create_all(engine)
    queue = AnalysisJobQueue(engine, clock=clock, lease_seconds=10)
    queue.configure()
    queue.create_batch("synthetic-main")
    queue.create_batch("synthetic-limited", max_requests=1)
    budget = JobBudget(reserve_tokens=1000, max_tokens=3000, reserve_cost_micros=100, max_cost_micros=300)

    def enqueue(person, *, text=TEXT, job=ai, batch="synthetic-main", max_attempts=3):
        return queue.enqueue(batch_id=batch, candidate_fingerprint=person, job_profile_id=job, raw_text=text,
            pipeline_fingerprint=content_hash({"mode": "synthetic-fixed-output-v1", "profile": profiles[job]}),
            contract=analysis_contract_from_job_profile(profiles[job]), budget=JobBudget(
                **{**budget.__dict__, "max_attempts": max_attempts}), deadline_seconds=1800)

    try:
        old_job = enqueue("synthetic-stale")
        old = queue.claim("old-worker")
        original_input = queue.read_input(old)
        new_job = enqueue("synthetic-stale", text=TEXT + "补充的新版本履历。\n")
        assert queue.complete(old, payload=PAYLOAD, analyzed_text_hash=original_input.input_hash) == "superseded"
        current = queue.claim("current-worker")
        queue.complete(current, payload=PAYLOAD, analyzed_text_hash=queue.read_input(current).input_hash)

        enqueue("synthetic-crash")
        abandoned = queue.claim("exited-worker")
        abandoned_input = queue.read_input(abandoned)
        engine.dispose()  # committed lease survives connection/process ownership loss
        engine = open_engine(database)
        queue = AnalysisJobQueue(engine, clock=clock, lease_seconds=10)
        queue.configure()
        clock.advance(11)
        assert queue.claim("restarted-worker") is None
        clock.advance(4)
        reclaimed = queue.claim("restarted-worker")
        rejected = False
        try:
            queue.complete(abandoned, payload=PAYLOAD, analyzed_text_hash=abandoned_input.input_hash)
        except LeaseLost:
            rejected = True
        assert rejected
        queue.complete(reclaimed, payload=PAYLOAD, analyzed_text_hash=queue.read_input(reclaimed).input_hash)

        for person in ("normal", "rate", "invalid", "unknown", "timeout"):
            enqueue("synthetic-" + person, max_attempts=2 if person == "timeout" else 3)
        enqueue("synthetic-fde", job=fde)
        cancelled = enqueue("synthetic-cancelled")
        queue.cancel(cancelled["job_id"], operator="synthetic-operator")
        for person in ("synthetic-limited-one", "synthetic-limited-two"):
            enqueue(person, batch="synthetic-limited")
        assert enqueue("synthetic-normal")["job_id"] == enqueue("synthetic-normal")["job_id"]
        queue.close_collection("synthetic-main")
        queue.close_collection("synthetic-limited")
        before = queue.status("synthetic-main")
        assert before["collection_completed"] and before["analysis_pending"] > 0

        def analyze(item):
            person = item.candidate_fingerprint
            if person == "synthetic-rate" and item.attempt == 1:
                raise WorkerFailure("rate_limited", retry_after_seconds=65)
            if person == "synthetic-timeout":
                raise WorkerFailure("timeout")
            payload = {**PAYLOAD, "match_score": 0} if person == "synthetic-invalid" else PAYLOAD
            unknown = person == "synthetic-unknown"
            return WorkerOutput(payload, item.input_hash, None if unknown else 800, None if unknown else 80)

        passes = []
        for _ in range(4):
            passes.append(AnalysisWorker(queue, analyze).run_ready())
            if all(queue.status(batch)["analysis_pending"] == 0 for batch in ("synthetic-main", "synthetic-limited")):
                break
            clock.advance(75)
        with engine.connect() as connection:
            attempts = [dict(row) for row in connection.execute(select(job_attempts)).mappings()]
            jobs = [dict(row) for row in connection.execute(select(analysis_jobs)).mappings()]
            runs = connection.execute(select(analysis_evidence_runs.c.analysis_run_id)).all()
            head = connection.execute(select(job_heads).where(job_heads.c.scope_id == old_job["scope_id"])).mappings().one()
        assert head["current_job_id"] == new_job["job_id"] and head["published_run_id"]
        batches = [queue.status(batch) for batch in ("synthetic-main", "synthetic-limited")]
        assert all(batch["analysis_pending"] == 0 for batch in batches)
        return {
            "schema_version": 1, "mode": "synthetic_durable_queue_only", "quality_claim_allowed": False,
            "simulated": True, "live_provider_calls": 0, "currency": "USD", "price_version": "synthetic-fixed-v1",
            "database": str(database), "source_manifest": _source_manifest(), "output_fixture_hash": content_hash(PAYLOAD),
            "job_count": len(jobs), "statuses": dict(Counter(row["status"] for row in jobs)),
            "attempt_count": len(attempts), "attempt_statuses": dict(Counter(row["status"] for row in attempts)),
            "history_run_count": len(runs), "unknown_usage_attempts": sum(row["reported_tokens"] is None for row in attempts),
            "stale_lease_rejected": rejected, "old_result_cannot_replace_new": True,
            "collection_and_analysis_separated": before["analysis_pending"] > 0,
            "worker_passes": passes, "batches": batches,
            "real_provider_latency": None, "real_provider_cost": None, "real_model_quality": None,
            "limitations": ["SQLite single-worker only; MySQL concurrency and live adapter integration are unverified.",
                            "Declared usage and prices are synthetic. Reservations are conservative and not actual spend.",
                            "Engine restart is simulated here; a subprocess-exit recovery test is in the regression suite."],
        }
    finally:
        engine.dispose()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)
    run_id = "queue-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    run_dir = OUTPUT_ROOT / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    report = simulate(run_dir)
    report["run_id"] = run_id
    report_path = run_dir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Synthetic queue only; no live calls: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
