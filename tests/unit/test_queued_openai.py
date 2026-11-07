from dataclasses import asdict, replace
from datetime import datetime, timedelta
import json

import pytest
from sqlalchemy import create_engine, event, select

from eval.fixtures.sdk_server import SDKFixtureServer
from liepin_rpa.analysis_contract import DEFAULT_ANALYSIS_CONTRACT
from liepin_rpa.analysis_evidence_store import evidence_metadata, analysis_evidence_runs
from liepin_rpa.analysis_job_schema import analysis_jobs, job_attempts, request_plans, request_receipts, queue_metadata
from liepin_rpa.analysis_jobs import AnalysisJobQueue, JobBudget, LeaseLost, InvalidJobOutput
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.analysis_worker import AnalysisWorker, WorkerFailure
from liepin_rpa.queued_openai import QueuedOpenAIFixtureAdapter, _retry_delay, fixture_endpoint
from liepin_rpa.queued_request_plan import QueuedRequestPlan, RequestOutcome

TEXT = "【纯合成协议样本】\n教育经历：本科。\n求职意向：上海。\n2020年起，独立开发 RAG 服务。\n"


@pytest.fixture
def setup_queue(tmp_path):
    engine = create_engine(f"sqlite+pysqlite:///{(tmp_path / 'sdk.sqlite').as_posix()}")
    @event.listens_for(engine, "connect")
    def foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")
    evidence_metadata.create_all(engine)
    queue_metadata.create_all(engine)
    clock = [datetime(2026, 11, 5, 1)]
    queue = AnalysisJobQueue(engine, clock=lambda: clock[0], lease_seconds=10)
    queue.configure()
    queue.create_batch("sdk-batch")
    yield queue, clock
    engine.dispose()


def rows(queue, table):
    with queue.engine.connect() as connection:
        return [dict(row) for row in connection.execute(select(table)).mappings()]


def enqueue(queue, model="fixture-ok", *, person="sdk-person", plan=None, batch="sdk-batch", max_attempts=3):
    plan = plan or QueuedRequestPlan.synthetic(model=model)
    job = queue.enqueue(batch_id=batch, candidate_fingerprint=person, job_profile_id="fde_ai_deployment_engineer_v1",
        raw_text=TEXT, pipeline_fingerprint=content_hash("sdk-fixture-pipeline-v1"), contract=DEFAULT_ANALYSIS_CONTRACT,
        budget=JobBudget(**plan.budget_fields(max_attempts=max_attempts)), request_plan=plan)
    return job, plan


def test_real_sdk_success_has_one_request_one_receipt_and_no_raw_event_data(setup_queue, monkeypatch):
    queue, _ = setup_queue
    job, plan = enqueue(queue)
    monkeypatch.setenv("OPENAI_API_KEY", "PRIVATE-ENV-KEY")
    monkeypatch.setenv("OPENAI_CUSTOM_HEADERS", "X-Fixture-Private: PRIVATE-ENV-HEADER")
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        result = AnalysisWorker(queue, adapter).run_ready()
        assert result["succeeded"] == 1 and len(server.requests) == 1
        assert server.requests[0]["max_output_tokens"] == plan.max_output_tokens
        assert not server.requests[0]["custom_header_seen"]
    receipt = rows(queue, request_receipts)[0]
    assert receipt["state"] == "received" and receipt["reported_tokens"] == 385
    assert receipt["reported_cost_micros"] == 449
    assert receipt["accounted_tokens"] == plan.input_reserve + plan.max_output_tokens
    assert queue.status("sdk-batch")["charged_requests"] == 1
    assert rows(queue, job_attempts)[0]["reported_tokens"] == 385
    encoded = json.dumps(rows(queue, request_receipts), default=str)
    assert all(secret not in encoded for secret in (TEXT, "PRIVATE-ENV", "PRIVATE-UPSTREAM", "system_prompt"))
    assert len(rows(queue, analysis_evidence_runs)) == 1


def test_sdk_has_no_hidden_retry_and_queue_honors_retry_after(setup_queue):
    queue, clock = setup_queue
    enqueue(queue, "fixture-rate-once")
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        worker = AnalysisWorker(queue, adapter)
        assert worker.run_ready()["failed_attempts"] == 1
        assert len(server.requests) == 1 and worker.run_ready()["dispatched"] == 0
        clock[0] += timedelta(seconds=64)
        assert worker.run_ready()["dispatched"] == 0
        clock[0] += timedelta(seconds=1)
        assert worker.run_ready()["succeeded"] == 1 and len(server.requests) == 2
    assert len(rows(queue, request_receipts)) == 2
    assert queue.status("sdk-batch")["charged_requests"] == 2


@pytest.mark.parametrize("model,code", [
    ("fixture-auth", "authentication"), ("fixture-quota", "quota_exhausted"),
    ("fixture-long-retry", "retry_delay_exceeded"), ("fixture-bad-json", "invalid_output"),
    ("fixture-bad-score", "invalid_output"), ("fixture-model-mismatch", "model_mismatch"),
    ("fixture-negative-usage", "usage_invalid"), ("fixture-bad-usage-total", "usage_invalid"),
    ("fixture-length", "invalid_output"), ("fixture-refusal", "model_refusal"),
    ("fixture-redirect", "provider_rejected"),
])
def test_terminal_sdk_failures_are_not_retried_or_published(setup_queue, model, code):
    queue, _ = setup_queue
    enqueue(queue, model)
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        assert AnalysisWorker(queue, adapter).run_ready()["failed_attempts"] == 1
        assert len(server.requests) == 1
    task = queue.status("sdk-batch")["tasks"][0]
    assert task["status"] == "failed" and task["last_error_code"] == code
    assert not rows(queue, analysis_evidence_runs)
    if model in {"fixture-bad-json", "fixture-bad-score", "fixture-length", "fixture-refusal"}:
        assert rows(queue, job_attempts)[0]["reported_tokens"] == 385
    if model == "fixture-model-mismatch":
        assert rows(queue, request_receipts)[0]["reported_cost_micros"] is None
    assert "PRIVATE-UPSTREAM" not in json.dumps(rows(queue, request_receipts), default=str)


@pytest.mark.parametrize("model,reported,accounted", [("fixture-missing-usage", None, 4352), ("fixture-partial-usage", None, 10256)])
def test_unknown_or_partial_usage_remains_null_and_conservatively_charged(setup_queue, model, reported, accounted):
    queue, _ = setup_queue
    enqueue(queue, model)
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        assert AnalysisWorker(queue, adapter).run_ready()["succeeded"] == 1
    receipt = rows(queue, request_receipts)[0]
    assert receipt["reported_tokens"] == reported and receipt["reported_cost_micros"] is None
    assert receipt["accounted_tokens"] == accounted
    assert queue.status("sdk-batch")["charged_tokens"] == accounted


def test_overage_is_charged_once_and_blocks_other_batch_requests(setup_queue):
    queue, _ = setup_queue
    queue.create_batch("limited", max_tokens=9000)
    enqueue(queue, "fixture-overage", batch="limited")
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        worker = AnalysisWorker(queue, adapter)
        assert worker.run_ready()["succeeded"] == 1
        enqueue(queue, batch="limited", person="second")
        assert worker.run_ready()["dispatched"] == 0 and len(server.requests) == 1
    assert queue.status("limited")["charged_requests"] == 1
    assert queue.status("limited")["charged_tokens"] == 10064
    assert queue.status("limited")["counts"]["failed"] == 1


def test_timeout_uses_single_sdk_attempt_and_conservatively_keeps_reservation(setup_queue):
    queue, _ = setup_queue
    enqueue(queue, plan=QueuedRequestPlan.synthetic(model="fixture-slow", timeout_seconds=0.03), max_attempts=1)
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        assert AnalysisWorker(queue, adapter).run_ready()["failed_attempts"] == 1
        assert len(server.requests) == 1
    assert rows(queue, request_receipts)[0]["state"] == "timeout"
    assert rows(queue, job_attempts)[0]["reported_tokens"] is None


def test_same_lease_cannot_dispatch_a_second_sdk_request(setup_queue):
    queue, _ = setup_queue
    enqueue(queue)
    item = queue.read_input(queue.claim("worker"))
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        adapter(item)
        with pytest.raises(WorkerFailure) as error:
            adapter(item)
        assert error.value.code == "request_already_started" and len(server.requests) == 1


def test_late_usage_after_cancellation_charges_once_without_publishing(setup_queue):
    queue, clock = setup_queue
    job, plan = enqueue(queue)
    lease = queue.claim("worker")
    queue.start_request(lease, plan_hash=plan.fingerprint, request_hash=content_hash("request"))
    queue.cancel(job["job_id"], operator="operator")
    clock[0] += timedelta(seconds=20)
    outcome = RequestOutcome("received", 100, input_tokens=10000, output_tokens=64, http_status=200)
    first = queue.record_request_outcome(lease, outcome)
    assert queue.record_request_outcome(lease, outcome) == first
    assert queue.status("sdk-batch")["charged_tokens"] == 10064
    assert queue.status("sdk-batch")["counts"]["cancelled"] == 1
    assert rows(queue, job_attempts)[0]["status"] == "abandoned"
    assert rows(queue, job_attempts)[0]["reported_tokens"] == 10064
    with pytest.raises(ValueError, match="replaced"):
        queue.record_request_outcome(lease, replace(outcome, input_tokens=10001))
    assert not rows(queue, analysis_evidence_runs)


def test_cancelled_sdk_callback_still_records_late_overage_without_a_result(setup_queue):
    queue, _ = setup_queue
    job, _ = enqueue(queue, "fixture-overage")
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        server.on_request = lambda _: queue.cancel(job["job_id"], operator="operator")
        stats = AnalysisWorker(queue, adapter).run_ready()
        assert stats["lease_lost"] == 1 and len(server.requests) == 1
    assert queue.status("sdk-batch")["charged_tokens"] == 10064
    assert queue.status("sdk-batch")["counts"]["cancelled"] == 1
    assert rows(queue, request_receipts)[0]["reported_tokens"] == 10064
    assert not rows(queue, analysis_evidence_runs)


def test_request_outcome_cannot_be_forged_by_another_worker(setup_queue):
    queue, _ = setup_queue
    _, plan = enqueue(queue)
    lease = queue.claim("original-worker")
    queue.start_request(lease, plan_hash=plan.fingerprint, request_hash=content_hash("request"))
    with pytest.raises(LeaseLost):
        queue.record_request_outcome(replace(lease, worker_id="foreign-worker"), RequestOutcome("received", 1))
    assert rows(queue, request_receipts)[0]["outcome_hash"] is None


def test_started_request_cannot_publish_without_a_durable_outcome(setup_queue):
    from eval.fixtures.sdk_server import PAYLOAD
    queue, _ = setup_queue
    _, plan = enqueue(queue)
    lease = queue.claim("worker")
    item = queue.read_input(lease)
    queue.start_request(lease, plan_hash=plan.fingerprint, request_hash=content_hash("request"))
    with pytest.raises(InvalidJobOutput, match="recorded"):
        queue.complete(lease, payload=PAYLOAD, analyzed_text_hash=item.input_hash)
    assert not rows(queue, analysis_evidence_runs)
    assert queue.status("sdk-batch")["counts"]["running"] == 1


def test_receipt_and_overage_charge_roll_back_together(setup_queue, monkeypatch):
    queue, _ = setup_queue
    _, plan = enqueue(queue)
    lease = queue.claim("worker")
    queue.start_request(lease, plan_hash=plan.fingerprint, request_hash=content_hash("request"))
    original = queue._charge_extra
    def interrupted(*args):
        original(*args)
        raise RuntimeError("interrupted receipt transaction")
    outcome = RequestOutcome("received", 1, input_tokens=10000, output_tokens=64)
    with monkeypatch.context() as patch:
        patch.setattr(queue, "_charge_extra", interrupted)
        with pytest.raises(RuntimeError):
            queue.record_request_outcome(lease, outcome)
    assert rows(queue, request_receipts)[0]["outcome_hash"] is None
    assert rows(queue, job_attempts)[0]["reported_tokens"] is None
    assert queue.status("sdk-batch")["charged_tokens"] == 4352
    queue.record_request_outcome(lease, outcome)
    assert queue.status("sdk-batch")["charged_tokens"] == 10064


def test_plan_is_immutable_versioned_and_matches_reservation_and_price(setup_queue):
    queue, _ = setup_queue
    original, plan = enqueue(queue)
    changed, _ = enqueue(queue, plan=replace(plan, system_prompt="变更后的合成提示词"))
    assert original["job_id"] != changed["job_id"]
    assert len(rows(queue, request_plans)) == 2
    with pytest.raises(ValueError, match="price"):
        enqueue(queue, plan=replace(plan, price_version="wrong-version"))
    with pytest.raises(ValueError, match="reservation"):
        queue.enqueue(batch_id="sdk-batch", candidate_fingerprint="wrong-reserve", job_profile_id="fde_ai_deployment_engineer_v1",
            raw_text=TEXT, pipeline_fingerprint=content_hash("wrong"), contract=DEFAULT_ANALYSIS_CONTRACT,
            request_plan=plan)  # default JobBudget does not cover this plan
    record = asdict(plan)
    record["api_key"] = "must-not-be-a-plan-field"
    with pytest.raises(ValueError):
        QueuedRequestPlan.from_dict(record)


@pytest.mark.parametrize("change", [{"input_reserve": 1}, {"sdk_version": "0.0.0"}])
def test_preflight_failure_makes_no_http_request(setup_queue, change):
    queue, _ = setup_queue
    enqueue(queue, plan=QueuedRequestPlan.synthetic(**change))
    with SDKFixtureServer() as server, QueuedOpenAIFixtureAdapter(queue, base_url=server.base_url) as adapter:
        assert AnalysisWorker(queue, adapter).run_ready()["failed_attempts"] == 1
        assert not server.requests
    assert not rows(queue, request_receipts)


@pytest.mark.parametrize("url", ["https://api.openai.com/v1/", "http://localhost:1/v1/", "http://127.0.0.1:1/v1/?secret=1",
                                 "http://user:password@127.0.0.1:1/v1/", "http://127.0.0.1:1/other/"])
def test_external_ambiguous_or_credential_endpoints_are_rejected(url):
    with pytest.raises(ValueError):
        fixture_endpoint(url)


def test_retry_after_parsing_does_not_shorten_long_delays():
    assert _retry_delay({"retry-after": "7200"}) == 7200
    assert _retry_delay({"retry-after": "9" * 400}) > 3600
    assert _retry_delay({"retry-after-ms": "1500"}) == 1.5
    assert _retry_delay({"retry-after": "nan"}) == 0
    assert _retry_delay({"retry-after": "garbage"}) == 0


@pytest.mark.parametrize("fields", [{"input_micro_per_million": None}, {"max_output_tokens": True}, {"mode": "live"}])
def test_unknown_price_or_invalid_plan_cannot_reserve_a_request(fields):
    with pytest.raises(ValueError):
        QueuedRequestPlan.synthetic(**fields)


def test_full_sdk_fixture_experiment_has_auditable_counts_and_no_quality_claim(tmp_path):
    from eval.sdk_queue_demo import simulate
    report = simulate(tmp_path)
    assert report["scenario_count"] == 14
    assert report["observed_http_requests"] == report["request_receipts"] == report["reserved_attempts"] == 16
    assert report["batch"]["counts"]["succeeded"] == 5 and report["batch"]["counts"]["failed"] == 9
    assert report["total_estimated_cost_micros"] is None and report["unknown_usage_count"] > 0
    assert report["quality_claim_allowed"] is False and report["real_model_calls"] == 0
    assert "PRIVATE-UPSTREAM" not in (tmp_path / "request_receipts.jsonl").read_text(encoding="utf-8")
