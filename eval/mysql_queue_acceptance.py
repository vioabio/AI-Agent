"""Opt-in disposable loopback MySQL integration, no provider/device/production data."""
import argparse
from datetime import timedelta
import json
import hashlib
import multiprocessing
import os
from pathlib import Path
import time
import traceback
import uuid

from sqlalchemy import create_engine, inspect, select, func, insert
from sqlalchemy.engine import make_url


def stub_payload():
    """A valid frozen-contract result; never substitute a live model in this harness."""
    from liepin_rpa.analysis_contract import validate_analysis_payload
    payload = {"beauty_relevant": True, "match_result": "relevant", "match_score": 90,
        "score_breakdown": {"industry_depth": 30, "beauty_continuity": 20, "role_evidence": 40},
        "analysis_reason": "Synthetic MySQL callback", "evidence_summary": ["Synthetic implementation evidence."]}
    if validate_analysis_payload(payload):
        raise ValueError("MySQL stub must satisfy the frozen scoring contract")
    return payload


def validate_database_url(value):
    url = make_url(value)
    if (url.drivername != "mysql+pymysql" or url.host != "127.0.0.1"
            or not url.database or not url.database.startswith("hr_upgrade_test_") or url.query):
        raise ValueError("Only a disposable hr_upgrade_test_* loopback PyMySQL database is allowed")
    return url


def _claim_process(value, output):
    engine = create_engine(value)
    try:
        from liepin_rpa.analysis_jobs import AnalysisJobQueue
        lease = AnalysisJobQueue(engine, lease_seconds=300).claim(f"process-{os.getpid()}")
        output.put(None if lease is None else {"job_id": lease.job_id, "token": lease.token, "worker_id": lease.worker_id})
    except Exception as error:
        output.put({"error_type": type(error).__name__})
    finally:
        engine.dispose()


def _wait_until_eligible(queue, batch):
    """Wait on real DB time: legacy MySQL DATETIME can round inserts to next second."""
    from liepin_rpa.analysis_job_schema import analysis_jobs
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        with queue.engine.connect() as connection:
            available = connection.scalar(select(func.max(analysis_jobs.c.available_at)).where(
                analysis_jobs.c.batch_id == batch))
            if available is not None and available <= queue._now(connection):
                return
        time.sleep(0.05)
    raise TimeoutError("Disposable MySQL tasks did not become eligible")


def _verify_projection(queue, enqueue, payload):
    """Exercise MySQL auto-increment, rollback and current-only idempotent HR writes."""
    from liepin_rpa.analysis_projection import project_current_result
    from liepin_rpa.db import resume_source_locators, resume_experience_records, resume_analysis_cache
    from liepin_rpa.analysis_jobs import JobBudget
    from liepin_rpa.analysis_contract import DEFAULT_ANALYSIS_CONTRACT
    from liepin_rpa.analysis_versioning import content_hash
    batch, person = "mysql-projection", "mysql-projection-0"
    queue.create_batch(batch)
    job = enqueue(batch, 0)
    assert enqueue(batch, 0) == job
    _wait_until_eligible(queue, batch)
    lease = queue.claim("projection-worker")
    assert lease.job_id == job
    queue.complete(lease, payload=payload, analyzed_text_hash=queue.read_input(lease).input_hash)
    try:
        project_current_result(queue, job)
    except ValueError as error:
        if str(error) != "Projection requires the original collected source locator":
            raise
    else:
        raise AssertionError("projection_without_locator_was_not_rejected")
    with queue.engine.begin() as connection:
        assert connection.scalar(select(func.count()).select_from(resume_analysis_cache)) == 0
        connection.execute(insert(resume_source_locators).values(candidate_fingerprint=person,
            candidate_name="Synthetic MySQL person", job_profile_id="ai_development_engineer_v1",
            search_batch_id=batch, search_keyword="AI"))
        connection.execute(insert(resume_experience_records).values(candidate_fingerprint=person,
            manual_review_status="on_hold", manual_review_feedback="Synthetic human feedback",
            manual_review_revision=8, manual_suppress_communication=1, manual_suppress_report=1))
    assert project_current_result(queue, job) == "projected"
    assert project_current_result(queue, job) == "already_projected"
    with queue.engine.connect() as connection:
        human = connection.execute(select(resume_experience_records).where(
            resume_experience_records.c.candidate_fingerprint == person)).mappings().one()
        assert human["manual_review_revision"] == 8 and human["manual_review_feedback"] == "Synthetic human feedback"
        assert human["manual_review_status"] == "on_hold"
        assert human["manual_suppress_communication"] == human["manual_suppress_report"] == 1
        assert connection.scalar(select(func.count()).select_from(resume_analysis_cache)) == 1
    newer = queue.enqueue(batch_id=batch, candidate_fingerprint=person, job_profile_id="ai_development_engineer_v1",
        raw_text="Synthetic implementation evidence. Updated.", pipeline_fingerprint=content_hash("mysql-stub-v1"),
        contract=DEFAULT_ANALYSIS_CONTRACT, budget=JobBudget(), deadline_seconds=86400)["job_id"]
    assert newer != job and project_current_result(queue, job) == "not_current"
    queue.cancel(newer, operator="mysql-acceptance")
    return {"duplicate_enqueue_idempotent": True, "projection_idempotent": True,
        "missing_locator_rollback": True, "manual_fields_preserved": True, "superseded_projection_rejected": True}


def exercise(value):
    url = validate_database_url(value)
    engine = create_engine(url, pool_pre_ping=True)
    try:
        if inspect(engine).get_table_names():
            raise ValueError("The disposable test database must be empty")
        from alembic import command
        from alembic.config import Config
        from liepin_rpa.db import metadata
        from liepin_rpa.analysis_job_schema import collected_inputs, tool_sessions, tool_rounds
        from liepin_rpa.analysis_jobs import AnalysisJobQueue, QueueLimits, JobBudget, Lease, LeaseLost
        from liepin_rpa.analysis_worker import AnalysisWorker, WorkerOutput
        from liepin_rpa.analysis_contract import DEFAULT_ANALYSIS_CONTRACT
        from liepin_rpa.analysis_versioning import content_hash
        excluded = {collected_inputs.name, tool_sessions.name, tool_rounds.name}
        metadata.create_all(engine, tables=[table for table in metadata.sorted_tables if table.name not in excluded])
        config = Config("alembic.ini")
        config.set_main_option("sqlalchemy.url", value.replace("%", "%%"))
        command.stamp(config, "20261105_07")
        command.upgrade(config, "head")
        assert excluded <= set(inspect(engine).get_table_names())
        command.downgrade(config, "20261105_07")
        assert not excluded & set(inspect(engine).get_table_names())
        command.upgrade(config, "head")
        queue = AnalysisJobQueue(engine, lease_seconds=300)
        queue.configure(QueueLimits(max_in_flight=4, requests_per_minute=1000, tokens_per_minute=10000000))
        payload = stub_payload()
        def enqueue(batch, number):
            return queue.enqueue(batch_id=batch, candidate_fingerprint=f"{batch}-{number}", job_profile_id="ai_development_engineer_v1",
                raw_text="Synthetic implementation evidence.", pipeline_fingerprint=content_hash("mysql-stub-v1"),
                contract=DEFAULT_ANALYSIS_CONTRACT, budget=JobBudget(), deadline_seconds=86400)["job_id"]
        def callback(item):
            time.sleep(0.01)
            return WorkerOutput(payload, item.input_hash)
        measurements = []
        for concurrency in (1, 2, 4):
            batch = f"mysql-c{concurrency}"
            queue.create_batch(batch)
            for number in range(12):
                enqueue(batch, number)
            _wait_until_eligible(queue, batch)
            start = time.perf_counter()
            stats = AnalysisWorker(queue, callback, concurrency=concurrency).run_ready(max_dispatches=12)
            assert stats["succeeded"] == 12 and stats["dispatched"] == 12, f"concurrency_{concurrency}_completion"
            measurements.append({"concurrency": concurrency, "database_and_stub_seconds": round(time.perf_counter() - start, 4),
                                 "tasks": 12, "stats": stats})
        queue.create_batch("mysql-crash")
        for number in range(8):
            enqueue("mysql-crash", number)
        _wait_until_eligible(queue, "mysql-crash")
        context = multiprocessing.get_context("spawn")
        output = context.Queue()
        processes = [context.Process(target=_claim_process, args=(value, output)) for _ in range(8)]
        try:
            for process in processes:
                process.start()
            results = [output.get(timeout=60) for _ in processes]
            for process in processes:
                process.join(timeout=10)
                assert process.exitcode == 0
        finally:
            for process in processes:
                if process.is_alive():
                    process.terminate()
                    process.join(timeout=5)
            output.close()
        claimed = [item for item in results if item is not None]
        assert len(claimed) == 4 and all("error_type" not in item for item in claimed)
        assert len({item["job_id"] for item in claimed}) == 4
        # Model process death without sleeping for a five-minute lease; locking remains real MySQL.
        with engine.connect() as connection:
            now = queue._now(connection)
            mysql_version = connection.scalar(select(__import__("sqlalchemy").func.version()))
        queue.clock = lambda: now + timedelta(seconds=600)
        with queue._transaction() as (connection, clock_now, _):
            queue._sweep(connection, clock_now)
        queue.clock = lambda: now + timedelta(seconds=660)
        recovered = AnalysisWorker(queue, callback).run_ready(max_dispatches=20)
        assert recovered["succeeded"] == 8 and queue.status("mysql-crash")["all_succeeded"]
        old = claimed[0]
        try:
            from liepin_rpa.resume_evidence import resume_text_hash
            queue.complete(Lease(old["job_id"], old["worker_id"], old["token"], now), payload=payload,
                           analyzed_text_hash=resume_text_hash("Synthetic implementation evidence."))
        except LeaseLost:
            stale_rejected = True
        else:
            stale_rejected = False
        assert stale_rejected
        queue.clock = None
        projection = _verify_projection(queue, enqueue, payload)
        return {"mode": "disposable_mysql_and_stub_only", "quality_claim_allowed": False, "release_approved": False,
            "real_model_calls": 0, "mysql_version": mysql_version,
            "schema_initialization": "metadata_baseline_07_then_real_migration_08",
            "migration_08_up_down_up": True, "measurements": measurements,
            "multiprocess_claims_unique": True, "in_flight_limit_observed": 4, "stale_completion_rejected": stale_rejected,
            "recovery_stats": recovered, "clock": "injected_for_expiry_only", "model_latency_measured": False,
            "hr_projection": projection}
    finally:
        engine.dispose()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    if not args.execute:
        parser.error("Explicit --execute and UPGRADE_MYSQL_TEST_URL are required")
    directory = Path("artifacts/mysql") / f"mysql-{uuid.uuid4().hex[:12]}"
    directory.mkdir(parents=True)
    try:
        report = exercise(os.environ.get("UPGRADE_MYSQL_TEST_URL", ""))
        report["status"] = "passed"
    except Exception as error:
        # Frame locations only: exception messages/locals can contain database credentials.
        report = {"status": "failed", "error_type": type(error).__name__, "release_approved": False,
            "failure_frames": [{"file": Path(frame.filename).name, "line": frame.lineno, "function": frame.name}
                               for frame in traceback.extract_tb(error.__traceback__)]}
    report["harness_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    (directory / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"MySQL isolated acceptance {report['status']}: {directory / 'report.json'}")
    return int(report["status"] != "passed")


if __name__ == "__main__":
    raise SystemExit(main())
