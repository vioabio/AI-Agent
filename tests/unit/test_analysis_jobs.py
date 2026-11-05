from datetime import datetime, timedelta, timezone
import json
import subprocess
import sys
import time
from types import SimpleNamespace
from threading import Barrier, Lock

import pytest
from sqlalchemy import create_engine, event, select

import liepin_rpa.analysis_jobs as queue_module
from liepin_rpa.analysis_contract import AnalysisContract, DEFAULT_ANALYSIS_CONTRACT
from liepin_rpa.analysis_evidence_store import analysis_evidence_runs, evidence_metadata, resume_snapshots
from liepin_rpa.analysis_job_schema import analysis_jobs, job_attempts, job_events, job_heads, queue_metadata
from liepin_rpa.analysis_jobs import AnalysisJobQueue, InvalidJobOutput, JobBudget, Lease, LeaseLost, QueueFull, QueueLimits
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.analysis_worker import AnalysisWorker, WorkerFailure, WorkerOutput

TEXT = "【虚构】教育经历：本科。\n求职意向：上海。\n2020年起独立开发 RAG 服务。\n"
PIPELINE = content_hash({"prompt": "synthetic-v1", "model": "no-live-provider"})
PAYLOAD = {"beauty_relevant": True, "match_result": "relevant", "match_score": 90,
           "score_breakdown": {"industry_depth": 30, "beauty_continuity": 25, "role_evidence": 35},
           "analysis_reason": "纯合成预置输出", "evidence_summary": ["2020年起独立开发 RAG 服务。"]}


class Clock:
    def __init__(self):
        self.now = datetime(2026, 11, 5, 1)

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += timedelta(seconds=seconds)


@pytest.fixture
def context(tmp_path):
    engine = create_engine(f"sqlite+pysqlite:///{(tmp_path / 'queue.sqlite').as_posix()}")

    @event.listens_for(engine, "connect")
    def enable_foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")

    evidence_metadata.create_all(engine)
    queue_metadata.create_all(engine)
    clock = Clock()
    queue = AnalysisJobQueue(engine, clock=clock, lease_seconds=10)
    queue.configure()
    queue.create_batch("batch-one")
    yield queue, clock
    engine.dispose()


def enqueue(queue, **kwargs):
    return queue.enqueue(**{"batch_id": "batch-one", "candidate_fingerprint": "synthetic-person",
                           "job_profile_id": "ai_development_engineer_v1", "raw_text": TEXT,
                           "pipeline_fingerprint": PIPELINE, "contract": DEFAULT_ANALYSIS_CONTRACT, **kwargs})


def finish(queue, lease, **kwargs):
    job_input = queue.read_input(lease)
    return queue.complete(lease, **{"payload": PAYLOAD, "analyzed_text_hash": job_input.input_hash, **kwargs})


def records(queue, table):
    with queue.engine.connect() as connection:
        return [dict(row) for row in connection.execute(select(table)).mappings()]


def test_enqueue_persists_snapshot_before_inference_and_reuses_across_batches(context):
    queue, _ = context
    first = enqueue(queue)
    assert enqueue(queue)["job_id"] == first["job_id"]
    queue.create_batch("batch-two")
    assert enqueue(queue, batch_id="batch-two")["job_id"] == first["job_id"]
    assert len(records(queue, resume_snapshots)) == 1
    assert not records(queue, analysis_evidence_runs)
    assert len(records(queue, analysis_jobs)) == 1
    queue.close_collection("batch-one")
    assert enqueue(queue)["job_id"] == first["job_id"]
    status = queue.status("batch-one")
    assert status["collection_completed"] and status["analysis_pending"] == 1
    assert not status["all_succeeded"]
    finish(queue, queue.claim("worker-one"))
    assert queue.status("batch-two")["analysis_succeeded"] == 1
    assert queue.status("batch-two")["charged_requests"] == 0
    assert queue.status("batch-one")["charged_requests"] == 1
    assert TEXT not in json.dumps(queue.status("batch-one"))


def test_closed_collection_cannot_gain_new_members_or_new_versions(context):
    queue, _ = context
    enqueue(queue)
    queue.create_batch("batch-two")
    queue.close_collection("batch-two")
    with pytest.raises(ValueError, match="closed"):
        enqueue(queue, batch_id="batch-two")
    queue.close_collection("batch-one")
    with pytest.raises(ValueError, match="closed"):
        enqueue(queue, raw_text=TEXT + "新正文")


def test_backpressure_rolls_back_snapshot_and_head(context):
    queue, _ = context
    # Existing control is immutable; use a small fresh queue in another database.
    engine = create_engine("sqlite+pysqlite:///:memory:")
    evidence_metadata.create_all(engine)
    queue_metadata.create_all(engine)
    small = AnalysisJobQueue(engine)
    small.configure(QueueLimits(max_queued=1))
    small.create_batch("batch-one")
    first = enqueue(small)
    with pytest.raises(QueueFull):
        enqueue(small, candidate_fingerprint="second-person")
    assert len(records(small, resume_snapshots)) == 1
    assert records(small, job_heads)[0]["current_job_id"] == first["job_id"]
    newer = enqueue(small, raw_text=TEXT + "新版")
    assert newer["job_id"] != first["job_id"]
    assert small.status("batch-one")["analysis_pending"] == 1
    engine.dispose()


def test_expired_worker_cannot_heartbeat_fail_or_publish_after_reclaim(context):
    queue, clock = context
    enqueue(queue)
    old = queue.claim("old-worker")
    old_input = queue.read_input(old)
    clock.advance(11)
    assert queue.claim("new-worker") is None  # recovery backoff
    clock.advance(4)
    current = queue.claim("new-worker")
    assert current.token == old.token + 1
    for operation in (lambda: queue.heartbeat(old), lambda: queue.fail(old, "timeout"),
                      lambda: queue.complete(old, payload=PAYLOAD, analyzed_text_hash=old_input.input_hash)):
        with pytest.raises(LeaseLost):
            operation()
    assert finish(queue, current) == "succeeded"
    assert finish_from_saved(queue, current, old_input.input_hash) == "succeeded"
    assert len(records(queue, analysis_evidence_runs)) == 1
    attempts = records(queue, job_attempts)
    assert [row["status"] for row in attempts] == ["abandoned", "succeeded"]
    assert attempts[0]["reported_tokens"] is None
    assert queue.status("batch-one")["charged_requests"] == 2


def finish_from_saved(queue, lease, text_hash):
    return queue.complete(lease, payload=PAYLOAD, analyzed_text_hash=text_hash)


def test_two_queue_objects_share_capacity_and_single_flight(context):
    queue, clock = context
    enqueue(queue)
    enqueue(queue, candidate_fingerprint="second-person")
    other = AnalysisJobQueue(queue.engine, clock=clock, lease_seconds=10)
    lease = queue.claim("one")
    assert other.claim("two") is None
    finish(queue, lease)
    assert other.claim("two") is not None


@pytest.mark.parametrize("change", ["text", "pipeline"])
def test_old_version_result_is_history_and_never_replaces_new_head(context, change):
    queue, _ = context
    first = enqueue(queue)
    lease = queue.claim("old")
    old_input = queue.read_input(lease)
    kwargs = {"raw_text": TEXT + "新版履历。"} if change == "text" else {"pipeline_fingerprint": content_hash("new-prompt")}
    new = enqueue(queue, **kwargs)
    assert first["job_id"] != new["job_id"]
    assert finish_from_saved(queue, lease, old_input.input_hash) == "superseded"
    assert records(queue, job_heads)[0]["published_run_id"] is None
    finish(queue, queue.claim("current"))
    head = records(queue, job_heads)[0]
    assert head["current_job_id"] == new["job_id"] and head["published_run_id"]
    assert finish_from_saved(queue, lease, old_input.input_hash) == "superseded"
    assert records(queue, job_heads)[0] == head
    assert enqueue(queue)["job_id"] == first["job_id"]  # old duplicate does not rewind head
    assert records(queue, job_heads)[0] == head
    assert len(records(queue, analysis_evidence_runs)) == 2


def test_unstarted_superseded_job_does_not_consume_requests(context):
    queue, _ = context
    first = enqueue(queue)
    second = enqueue(queue, raw_text=TEXT + "新版")
    assert queue.status("batch-one")["counts"]["superseded"] == 1
    assert queue.claim("one").job_id == second["job_id"]
    assert next(r for r in records(queue, analysis_jobs) if r["job_id"] == first["job_id"])["attempts"] == 0


def test_result_and_current_pointer_rollback_together(context, monkeypatch):
    queue, _ = context
    enqueue(queue)
    lease = queue.claim("one")
    original = queue_module.persist_analysis_evidence

    def broken(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("simulate failure after run insert")

    with monkeypatch.context() as patch:
        patch.setattr(queue_module, "persist_analysis_evidence", broken)
        with pytest.raises(RuntimeError):
            finish(queue, lease)
    assert not records(queue, analysis_evidence_runs)
    assert records(queue, job_heads)[0]["published_run_id"] is None
    assert records(queue, analysis_jobs)[0]["status"] == "running"
    finish(queue, lease)


def test_heartbeat_and_cancellation_revoke_publication(context):
    queue, clock = context
    job = enqueue(queue)
    lease = queue.claim("one")
    text_hash = queue.read_input(lease).input_hash
    clock.advance(6)
    renewed = queue.heartbeat(lease)
    assert renewed.token == lease.token and renewed.expires_at > lease.expires_at
    clock.advance(5)
    queue.cancel(job["job_id"], operator="local-operator")
    with pytest.raises(LeaseLost):
        finish_from_saved(queue, renewed, text_hash)
    assert records(queue, job_attempts)[0]["status"] == "abandoned"
    assert queue.status("batch-one")["counts"]["cancelled"] == 1


def test_retry_after_is_persisted_and_operator_cannot_bypass_429(context):
    queue, clock = context
    job = enqueue(queue)
    queue.fail(queue.claim("one"), "rate_limited", retry_after_seconds=90)
    assert queue.claim("two") is None
    with pytest.raises(ValueError):
        queue.retry(job["job_id"], operator="local-operator")
    clock.advance(89)
    assert queue.claim("two") is None
    clock.advance(1)
    assert queue.claim("two").token == 2


def test_operator_retry_preserves_attempt_and_budget_counters_and_audits_actor(context):
    queue, _ = context
    job = enqueue(queue)
    queue.fail(queue.claim("one"), "timeout")
    queue.retry(job["job_id"], operator="local-operator")
    assert queue.claim("two").token == 2
    assert queue.status("batch-one")["charged_requests"] == 2
    assert any(row["event_type"] == "operator_retry" and row["actor"] == "local-operator" for row in records(queue, job_events))


@pytest.mark.parametrize("budget", [JobBudget(max_tokens=3999), JobBudget(max_cost_micros=999)])
def test_job_budget_blocks_before_dispatch(context, budget):
    queue, _ = context
    enqueue(queue, budget=budget)
    assert queue.claim("one") is None
    assert queue.status("batch-one")["charged_requests"] == 0
    assert queue.status("batch-one")["counts"]["failed"] == 1


def test_batch_budget_and_unknown_usage_remain_conservative(context):
    queue, _ = context
    queue.create_batch("limited", max_requests=1)
    enqueue(queue, batch_id="limited")
    enqueue(queue, batch_id="limited", candidate_fingerprint="second")
    finish(queue, queue.claim("one"))
    assert queue.claim("one") is None
    status = queue.status("limited")
    assert status["charged_requests"] == 1 and status["charged_tokens"] == 4000
    assert status["charged_cost_micros"] == 1000
    assert status["counts"]["failed"] == 1
    assert records(queue, job_attempts)[0]["reported_tokens"] is None


def test_reported_overage_is_recorded_and_stops_subsequent_dispatch(context):
    queue, _ = context
    queue.create_batch("limited", max_tokens=5000)
    enqueue(queue, batch_id="limited")
    enqueue(queue, batch_id="limited", candidate_fingerprint="second")
    finish(queue, queue.claim("one"), reported_tokens=6000, reported_cost_micros=2000)
    assert queue.claim("one") is None
    status = queue.status("limited")
    assert status["charged_tokens"] == 6000 and status["charged_cost_micros"] == 2000
    assert status["charged_requests"] == 1


def test_shared_fixed_minute_limiter_persists_across_queue_restart(context):
    queue, clock = context
    # Consume twenty reservations via terminal successes and a new object per call.
    for index in range(21):
        enqueue(queue, candidate_fingerprint=f"person-{index:02d}")
    for index in range(20):
        finish(queue, queue.claim("one"))
    other = AnalysisJobQueue(queue.engine, clock=clock, lease_seconds=10)
    assert other.claim("two") is None
    assert other.status("batch-one")["charged_requests"] == 20
    clock.advance(60)
    assert other.claim("two") is not None


def test_invalid_input_or_output_cannot_create_a_result(context):
    queue, _ = context
    enqueue(queue)
    lease = queue.claim("one")
    for kwargs in ({"analyzed_text_hash": "wrong"}, {"payload": {**PAYLOAD, "match_score": 20}},
                   {"payload": {**PAYLOAD, "evidence_summary": [123]}}):
        with pytest.raises(InvalidJobOutput):
            finish(queue, lease, **kwargs)
    assert not records(queue, analysis_evidence_runs)
    queue.fail(lease, "invalid_output")
    assert queue.status("batch-one")["counts"]["failed"] == 1


def test_scoring_contract_is_frozen_and_changes_task_identity(context):
    queue, _ = context
    limits = {"industry_depth": 35, "beauty_continuity": 25, "role_evidence": 40}
    first = enqueue(queue, contract=AnalysisContract(80, limits))
    limits["industry_depth"], limits["role_evidence"] = 30, 45
    second = enqueue(queue, contract=AnalysisContract(80, limits))
    assert first["pipeline_fingerprint"] != second["pipeline_fingerprint"]
    assert json.loads(first["contract_json"])["score_breakdown_limits"]["industry_depth"] == 35


def test_deadline_and_retry_budget_are_not_reset(context):
    queue, clock = context
    enqueue(queue, deadline_seconds=5)
    lease = queue.claim("one")
    text_hash = queue.read_input(lease).input_hash
    clock.advance(5)
    with pytest.raises(LeaseLost):
        finish_from_saved(queue, lease, text_hash)
    assert queue.claim("two") is None
    assert queue.status("batch-one")["tasks"][0]["last_error_code"] == "deadline_exceeded"
    assert not records(queue, analysis_evidence_runs)


def test_worker_sanitizes_exceptions_and_stops_after_attempt_cap(context):
    queue, clock = context
    enqueue(queue, budget=JobBudget(max_attempts=2))

    def fail(_):
        raise RuntimeError("private-provider-body-and-secret")

    worker = AnalysisWorker(queue, fail)
    assert worker.run_ready()["failed_attempts"] == 1
    clock.advance(5)
    assert worker.run_ready()["failed_attempts"] == 1
    assert queue.status("batch-one")["counts"]["failed"] == 1
    assert "private-provider" not in json.dumps(records(queue, job_attempts) + records(queue, job_events), default=str)


def test_worker_renews_lease_during_a_slow_callback(context):
    queue, _ = context
    queue.clock = lambda: datetime.now(timezone.utc)
    queue.lease_seconds = 1
    enqueue(queue)

    def analyze(item):
        time.sleep(1.3)
        return WorkerOutput(PAYLOAD, item.input_hash)

    result = AnalysisWorker(queue, analyze).run_ready()
    assert result["succeeded"] == 1 and result["lease_lost"] == 0


def test_restart_after_process_exits_with_committed_lease(context):
    queue, clock = context
    enqueue(queue)
    code = """
import sys
from datetime import datetime
from sqlalchemy import create_engine
from liepin_rpa.analysis_jobs import AnalysisJobQueue
engine = create_engine(sys.argv[1])
queue = AnalysisJobQueue(engine, clock=lambda: datetime(2026, 11, 5, 1), lease_seconds=10)
assert queue.claim('exited-process') is not None
engine.dispose()
"""
    subprocess.run([sys.executable, "-c", code, str(queue.engine.url)], check=True, capture_output=True)
    assert records(queue, analysis_jobs)[0]["status"] == "running"
    clock.advance(11)
    assert queue.claim("restarted") is None
    clock.advance(4)
    finish(queue, queue.claim("restarted"))
    assert queue.status("batch-one")["analysis_succeeded"] == 1


def test_queue_configuration_and_demo_concurrency_are_explicit(context):
    queue, clock = context
    with pytest.raises(ValueError):
        queue.configure(QueueLimits(max_in_flight=2))
    with pytest.raises(ValueError):
        queue.configure(QueueLimits(max_queued=200))
    with pytest.raises(ValueError):
        AnalysisWorker(queue, lambda _: None, concurrency=2)
    with pytest.raises(ValueError, match="attribution"):
        AnalysisJobQueue(queue.engine, clock=clock, price_version="other-price").create_batch("other")


def test_token_rate_limit_and_clock_rollback_do_not_dispatch_extra_calls(tmp_path):
    engine = create_engine(f"sqlite+pysqlite:///{(tmp_path / 'limits.sqlite').as_posix()}")
    evidence_metadata.create_all(engine)
    queue_metadata.create_all(engine)
    clock = Clock()
    queue = AnalysisJobQueue(engine, clock=clock)
    queue.configure(QueueLimits(tokens_per_minute=4000))
    queue.create_batch("batch-one")
    enqueue(queue)
    enqueue(queue, candidate_fingerprint="second")
    finish(queue, queue.claim("one"))
    clock.advance(-60)
    assert queue.claim("two") is None
    clock.advance(120)
    assert queue.claim("two") is not None
    engine.dispose()


def test_enqueue_failure_after_snapshot_rolls_back_all_entities(context, monkeypatch):
    queue, _ = context

    def broken(*_):
        raise RuntimeError("simulate interruption after task insert")

    monkeypatch.setattr(queue, "_event", broken)
    with pytest.raises(RuntimeError):
        enqueue(queue)
    assert not records(queue, resume_snapshots)
    assert not records(queue, analysis_jobs)
    assert not records(queue, job_heads)


def test_invalid_output_still_accounts_for_reported_usage(context):
    queue, _ = context
    enqueue(queue)
    result = AnalysisWorker(queue, lambda item: WorkerOutput(
        {**PAYLOAD, "match_score": 0}, item.input_hash, 6000, 2000)).run_ready()
    assert result["failed_attempts"] == 1
    assert records(queue, job_attempts)[0]["reported_tokens"] == 6000
    assert queue.status("batch-one")["charged_tokens"] == 6000
    assert queue.status("batch-one")["counts"]["failed"] == 1


def test_worker_pool_bounds_physical_callbacks_with_a_stub_queue():
    # Tests the executor, not MySQL lock correctness. Real SQLite stays single-worker.
    barrier, lock = Barrier(2), Lock()
    active, peak, dispatched = 0, 0, 0

    def claim(worker):
        nonlocal dispatched
        with lock:
            if dispatched == 4:
                return None
            dispatched += 1
            return Lease(str(dispatched), worker, 1, datetime(2030, 1, 1))

    def analyze(item):
        nonlocal active, peak
        with lock:
            active += 1
            peak = max(peak, active)
        barrier.wait(timeout=5)
        with lock:
            active -= 1
        return WorkerOutput(PAYLOAD, "stub-only")

    stub = SimpleNamespace(engine=SimpleNamespace(dialect=SimpleNamespace(name="mysql")),
        lease_seconds=30, claim=claim, read_input=lambda lease: lease,
        complete=lambda *_args, **_kwargs: "succeeded", heartbeat=lambda lease: lease)
    result = AnalysisWorker(stub, analyze, concurrency=2).run_ready(max_dispatches=4)
    assert result["succeeded"] == 4 and peak == 2 and active == 0


def test_synthetic_queue_demo_and_management_boundary(tmp_path, monkeypatch):
    from eval.queue_demo import simulate
    from scripts.manage_analysis_queue import main
    report = simulate(tmp_path)
    assert report["job_count"] == 12 and report["attempt_count"] == 13
    assert report["stale_lease_rejected"] and report["live_provider_calls"] == 0
    assert report["quality_claim_allowed"] is False and report["real_provider_cost"] is None
    assert report["statuses"] == {"succeeded": 7, "superseded": 1, "failed": 3, "cancelled": 1}
    assert TEXT not in json.dumps(report)
    with pytest.raises(SystemExit) as error:
        main(["--database", str(tmp_path / "queue.sqlite"), "status", "--batch-id", "synthetic-main"])
    assert error.value.code == 2  # out of managed directory; no connection made


@pytest.mark.parametrize("kwargs", [{"deadline_seconds": 0}, {"candidate_fingerprint": "../../.env"},
                                    {"pipeline_fingerprint": "manual-v1"}])
def test_malformed_enqueue_is_rejected(context, kwargs):
    queue, _ = context
    with pytest.raises(ValueError):
        enqueue(queue, **kwargs)


def test_intake_reuses_configured_qualification_rules_and_never_dispatches_unknown(context):
    from liepin_rpa.analysis_intake import SyntheticCollectionIntake
    queue, _ = context
    intake = SyntheticCollectionIntake(queue)
    kwargs = {"batch_id": "batch-one", "candidate_fingerprint": "intake-person", "candidate_name": "虚构姓名",
              "job_profile_id": "daren_live_bd_v1", "pipeline_fingerprint": PIPELINE}
    record = {"experience": TEXT, "sections": {"求职意向": ["上海"]}}
    assert intake.submit(**kwargs, resume_record={"experience": TEXT, "sections": ["malformed"]}).state == "needs_review"
    assert intake.submit(**kwargs, resume_record=record).state == "needs_review"
    assert not records(queue, analysis_jobs)
    record["education_experiences"] = [{"degree": "本科", "admission_type": "非统招"}]
    assert intake.submit(**kwargs, resume_record=record).state == "ineligible"
    record["education_experiences"] = [{"degree": "本科", "admission_type": "统招"}]
    record["sections"] = {"求职意向": ["不考虑上海"]}
    assert intake.submit(**kwargs, resume_record=record).state == "ineligible"
    assert not records(queue, resume_snapshots)
    record["sections"] = {"求职意向": ["上海"]}
    first = intake.submit(**kwargs, resume_record=record)
    assert first.state == "queued"
    assert intake.submit(**{**kwargs, "candidate_name": "后续显示名"}, resume_record=record).job_id == first.job_id
    record["education_experiences"].append({"degree": "硕士", "admission_type": "统招"})
    assert intake.submit(**kwargs, resume_record=record).job_id != first.job_id


def test_receipt_failure_rolls_back_intake_snapshot_and_job(context, monkeypatch):
    from liepin_rpa.analysis_intake import SyntheticCollectionIntake
    queue, _ = context
    def broken(*_):
        raise RuntimeError("receipt write interrupted")
    monkeypatch.setattr(queue, "_receipt", broken)
    with pytest.raises(RuntimeError):
        SyntheticCollectionIntake(queue).submit(batch_id="batch-one", candidate_fingerprint="intake-person",
            candidate_name="虚构姓名", job_profile_id="ai_development_engineer_v1",
            resume_record={"experience": TEXT}, pipeline_fingerprint=PIPELINE)
    assert not records(queue, resume_snapshots) and not records(queue, analysis_jobs)


def test_operator_retry_rechecks_budget_inside_the_transaction(context):
    queue, _ = context
    job = enqueue(queue, budget=JobBudget(max_tokens=4000))
    queue.fail(queue.claim("worker"), "timeout")
    with pytest.raises(ValueError, match="budgets"):
        queue.retry(job["job_id"], operator="local-operator")
