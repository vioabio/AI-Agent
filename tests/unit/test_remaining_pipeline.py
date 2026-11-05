from dataclasses import replace
from datetime import datetime, timedelta
from decimal import Decimal
import json

import pytest
from sqlalchemy import create_engine, insert, select, update, func

from liepin_rpa.analysis_jobs import AnalysisJobQueue, JobBudget, QueueLimits
from liepin_rpa.analysis_intake import SyntheticCollectionIntake, CollectedAnalysisIntake
from liepin_rpa.analysis_observability import Price
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.analysis_job_schema import collected_inputs, tool_rounds, analysis_jobs
from liepin_rpa.db import metadata, resume_analysis_cache, resume_source_locators, resume_experience_records
from liepin_rpa.bounded_tools.durable import run_durable_tools
from liepin_rpa.bounded_tools.runner import ModelReply, ToolCall, LoopPolicy
from liepin_rpa.analysis_projection import project_current_result
from eval.fixtures.tool_provider import ScriptedToolProvider

QUOTE = "独立开发 RAG 服务并部署上线。"
TEXT = "【纯虚构】\n求职意向：上海。\n教育经历：统招本科。\n项目经历：" + QUOTE
RECORD = {"experience": TEXT, "sections": {"求职意向": ["上海"]},
          "education_experiences": [{"degree": "本科", "admission_type": "统招"}]}
PAYLOAD = {"beauty_relevant": True, "match_result": "relevant", "match_score": 90,
           "score_breakdown": {"industry_depth": 30, "beauty_continuity": 20, "role_evidence": 40},
           "analysis_reason": "纯合成输出", "evidence_summary": [QUOTE]}
PRICE = Price("synthetic-fixed-v1", Decimal("1"), Decimal("2"))


@pytest.fixture
def queue(tmp_path):
    engine = create_engine(f"sqlite+pysqlite:///{(tmp_path / 'pipeline.sqlite').as_posix()}")
    metadata.create_all(engine)
    clock = [datetime(2026, 11, 5, 1)]
    queue = AnalysisJobQueue(engine, clock=lambda: clock[0])
    queue.configure()
    queue.create_batch("pipeline-batch", max_tokens=1000000, max_cost_micros=1000000)
    queue.test_clock = clock
    yield queue
    engine.dispose()


def submit(queue, *, text=TEXT, person="pipeline-person", budget=None):
    decision = SyntheticCollectionIntake(queue).submit(batch_id="pipeline-batch", candidate_fingerprint=person,
        candidate_name="虚构候选人", job_profile_id="ai_development_engineer_v1",
        resume_record={**RECORD, "experience": text}, pipeline_fingerprint=content_hash("pipeline-v1"),
        budget=budget or JobBudget(max_tokens=200000, max_cost_micros=200000))
    assert decision.state == "queued"
    return decision.job_id


def final_reply():
    return ModelReply(payload=PAYLOAD, citations=[{"summary_index": 0, "start": TEXT.index(QUOTE),
        "end": TEXT.index(QUOTE) + len(QUOTE), "quote": QUOTE}], input_tokens=100, output_tokens=20)


def steps():
    return [ModelReply(tool_calls=(ToolCall(call_id="requirements", name="get_job_requirements", arguments="{}"),),
                       input_tokens=100, output_tokens=10), final_reply()]


def test_collected_input_is_frozen_in_enqueue_and_idempotent(queue):
    job = submit(queue)
    assert submit(queue) == job
    with queue.engine.connect() as conn:
        row = conn.execute(select(collected_inputs)).mappings().one()
    assert json.loads(row["record_json"]) == RECORD
    assert row["input_hash"] == content_hash({"record": RECORD, "profile": json.loads(row["profile_json"])})
    assert submit(queue, text=TEXT + "\n新增经历") != job


def test_durable_tool_rounds_charge_shared_budget_and_replay_without_new_requests(queue):
    submit(queue)
    lease = queue.claim("worker")
    item = queue.read_input(lease)
    provider = ScriptedToolProvider(steps())
    result, traffic = run_durable_tools(queue, item, provider, price=PRICE, as_of_month="2026.11")
    assert result.state == "succeeded" and traffic == {"new_requests": 2, "replayed_responses": 0}
    before = queue.status("pipeline-batch")
    replay, traffic = run_durable_tools(queue, item, ScriptedToolProvider([]), price=PRICE, as_of_month="2026.11")
    assert replay.state == "succeeded" and traffic == {"new_requests": 0, "replayed_responses": 2}
    assert queue.status("pipeline-batch") == before
    assert before["charged_requests"] == 2
    with queue.engine.connect() as conn:
        rows = conn.execute(select(tool_rounds)).mappings().all()
    assert len(rows) == 2 and all(row["state"] == "received" and row["finished_at"] is not None for row in rows)
    assert before["charged_tokens"] == sum(row["reserved_tokens"] for row in rows)


def test_started_but_unacknowledged_tool_request_is_never_resent_after_reclaim(queue):
    submit(queue)
    lease = queue.claim("old-worker")
    class Crash(ScriptedToolProvider):
        def complete(self, request):
            raise SystemExit("simulated process death")
    with pytest.raises(SystemExit):
        run_durable_tools(queue, queue.read_input(lease), Crash([]), price=PRICE, as_of_month="2026.11")
    queue.test_clock[0] += timedelta(seconds=40)
    assert queue.claim("new-worker") is None  # Reclamation schedules bounded retry.
    queue.test_clock[0] += timedelta(seconds=60)
    new = queue.claim("new-worker")
    assert new is not None
    provider = ScriptedToolProvider(steps())
    result, traffic = run_durable_tools(queue, queue.read_input(new), provider, price=PRICE, as_of_month="2026.11")
    assert result.state == "needs_review" and traffic["new_requests"] == 0 and provider.requests == []


def test_durable_tool_session_rejects_policy_and_fact_changes(queue):
    job = submit(queue)
    item = queue.read_input(queue.claim("worker"))
    run_durable_tools(queue, item, ScriptedToolProvider(steps()), price=PRICE, as_of_month="2026.11")
    with pytest.raises(ValueError, match="identity"):
        run_durable_tools(queue, item, ScriptedToolProvider([]), price=PRICE, as_of_month="2026.11",
                          policy=replace(LoopPolicy(), max_model_rounds=2))
    with queue.engine.begin() as conn:
        conn.execute(update(collected_inputs).where(collected_inputs.c.job_id == job).values(record_json='{}'))
    with pytest.raises(ValueError, match="integrity"):
        run_durable_tools(queue, item, ScriptedToolProvider([]), price=PRICE, as_of_month="2026.11")


def test_projection_is_current_atomic_and_preserves_human_fields(queue):
    job = submit(queue)
    with queue.engine.begin() as conn:
        conn.execute(insert(resume_source_locators).values(id=1, candidate_fingerprint="pipeline-person",
            job_profile_id="ai_development_engineer_v1", search_batch_id="pipeline-batch", search_keyword="AI"))
        conn.execute(insert(resume_experience_records).values(id=1, candidate_fingerprint="pipeline-person",
            manual_review_status="on_hold", manual_review_feedback="人工意见", manual_review_revision=8,
            manual_suppress_communication=1, manual_suppress_report=1))
    lease = queue.claim("worker")
    queue.complete(lease, payload=PAYLOAD, analyzed_text_hash=queue.read_input(lease).input_hash)
    assert project_current_result(queue, job) == "projected"
    assert project_current_result(queue, job) == "already_projected"
    with queue.engine.connect() as conn:
        person = conn.execute(select(resume_experience_records)).mappings().one()
        assert person["manual_review_revision"] == 8 and person["manual_review_feedback"] == "人工意见"
        assert person["manual_suppress_communication"] == person["manual_suppress_report"] == 1
        assert conn.scalar(select(func.count()).select_from(resume_analysis_cache)) == 1
    submit(queue, text=TEXT + "\n更新原文")
    assert project_current_result(queue, job) == "not_current"


def test_projection_without_collected_source_rolls_back_all_machine_writes(queue):
    job = submit(queue)
    lease = queue.claim("worker")
    queue.complete(lease, payload=PAYLOAD, analyzed_text_hash=queue.read_input(lease).input_hash)
    with pytest.raises(ValueError, match="locator"):
        project_current_result(queue, job)
    with queue.engine.connect() as conn:
        assert conn.scalar(select(func.count()).select_from(resume_analysis_cache)) == 0
        assert conn.scalar(select(func.count()).select_from(resume_experience_records)) == 0


def test_actual_sdk_tool_frames_use_one_persistent_round_per_http_request(queue):
    from eval.fixtures.tool_sdk_server import ToolSDKFixtureServer
    from liepin_rpa.bounded_tools.sdk_fixture import SDKToolFixtureProvider
    from liepin_rpa.bounded_tools.durable import DurableToolWorkerAdapter
    from liepin_rpa.analysis_worker import AnalysisWorker
    submit(queue)
    with ToolSDKFixtureServer(payload=PAYLOAD, quote=QUOTE) as server:
        with SDKToolFixtureProvider(queue, base_url=server.base_url) as provider:
            worker = AnalysisWorker(queue, DurableToolWorkerAdapter(queue, provider, price=PRICE, as_of_month="2026.11"))
            assert worker.run_ready()["succeeded"] == 1
        assert len(server.requests) == 2 and server.requests[-1]["tool_id_bound"]
    with queue.engine.connect() as conn:
        rows = conn.execute(select(tool_rounds)).mappings().all()
    assert len(rows) == 2 and all(row["input_tokens"] == 100 and row["output_tokens"] == 20 for row in rows)
    assert queue.status("pipeline-batch")["charged_requests"] == 2


def test_tools_cannot_bypass_shared_batch_request_limit(queue):
    with queue.engine.begin() as conn:
        from liepin_rpa.analysis_job_schema import job_batches
        conn.execute(update(job_batches).values(max_requests=1))
    submit(queue)
    provider = ScriptedToolProvider(steps())
    result, traffic = run_durable_tools(queue, queue.read_input(queue.claim("worker")), provider,
                                       price=PRICE, as_of_month="2026.11")
    assert result.state == "needs_review" and traffic["new_requests"] == 1
    assert queue.status("pipeline-batch")["charged_requests"] == 1


@pytest.mark.parametrize("currency", ["USD", "CNY"])
def test_configured_gateway_adapter_freezes_plan_and_never_inherits_env_headers(tmp_path, monkeypatch, currency):
    import httpx
    from liepin_rpa.queued_request_plan import QueuedRequestPlan
    from liepin_rpa.queued_openai import QueuedOpenAIAdapter
    from liepin_rpa.analysis_worker import AnalysisWorker
    engine = create_engine(f"sqlite+pysqlite:///{(tmp_path / 'configured.sqlite').as_posix()}")
    metadata.create_all(engine)
    queue = AnalysisJobQueue(engine, binding_source="gateway_input", price_version="test-price-v1", currency=currency)
    queue.configure()
    queue.create_batch("configured-batch")
    plan = QueuedRequestPlan.configured(model="fixture-model", system_prompt="合成协议测试",
        price_version="test-price-v1", input_micro_per_million=1000000, output_micro_per_million=2000000, input_reserve=4096, currency=currency)
    job = CollectedAnalysisIntake(queue).submit(batch_id="configured-batch", candidate_fingerprint="configured-person",
        candidate_name="虚构候选人", job_profile_id="ai_development_engineer_v1", resume_record=RECORD,
        pipeline_fingerprint=content_hash("configured-fixture"), request_plan=plan, budget=JobBudget(**plan.budget_fields()))
    monkeypatch.setenv("OPENAI_CUSTOM_HEADERS", "X-Private: NEVER-SEND")
    observed = []
    def respond(request):
        observed.append(request)
        return httpx.Response(200, json={"id": "fixture", "object": "chat.completion", "created": 1,
            "model": "fixture-model", "choices": [{"index": 0, "message": {"role": "assistant", "content": json.dumps(PAYLOAD)},
                "finish_reason": "stop"}], "usage": {"prompt_tokens": 321, "completion_tokens": 64, "total_tokens": 385}})
    with QueuedOpenAIAdapter(queue, base_url="https://fixture.invalid/v1/", api_key="fixture-explicit-key",
                            transport=httpx.MockTransport(respond)) as adapter:
        assert AnalysisWorker(queue, adapter).run_ready()["succeeded"] == 1
    assert len(observed) == 1 and observed[0].headers["authorization"] == "Bearer fixture-explicit-key"
    assert plan.currency == queue.currency == currency
    assert "X-Private" not in observed[0].headers
    with engine.connect() as conn:
        from liepin_rpa.analysis_job_schema import collection_receipts, request_receipts
        assert conn.scalar(select(collection_receipts.c.source_type)) == "collected"
        assert conn.scalar(select(request_receipts.c.reported_tokens)) == 385
    engine.dispose()


def test_workers_only_claim_the_frozen_analysis_strategy(queue):
    fulltext = submit(queue, person="fulltext-person")
    decision = SyntheticCollectionIntake(queue).submit(batch_id="pipeline-batch", candidate_fingerprint="tools-person",
        candidate_name="虚构工具候选人", job_profile_id="ai_development_engineer_v1",
        resume_record={**RECORD, "_analysis_strategy": "tools"}, pipeline_fingerprint=content_hash("strategy-v1"))
    tools = queue.claim("tools-worker", strategy="tools")
    assert tools.job_id == decision.job_id
    queue.fail(tools, "invalid_output")
    assert queue.claim("fulltext-worker", strategy="fulltext").job_id == fulltext


def test_configured_tool_sdk_requires_opt_in_and_persists_each_mocked_request(tmp_path):
    import httpx
    from liepin_rpa.bounded_tools.sdk_fixture import SDKToolProvider
    engine = create_engine(f"sqlite+pysqlite:///{(tmp_path / 'tools-configured.sqlite').as_posix()}")
    metadata.create_all(engine)
    queue = AnalysisJobQueue(engine, binding_source="gateway_input")
    queue.configure()
    queue.create_batch("tools-configured", max_cost_micros=1000000)
    CollectedAnalysisIntake(queue).submit(batch_id="tools-configured", candidate_fingerprint="configured-tools",
        candidate_name="虚构工具样本", job_profile_id="ai_development_engineer_v1", resume_record={**RECORD, "_analysis_strategy": "tools"},
        pipeline_fingerprint=content_hash("configured-tools-test"), budget=JobBudget(max_tokens=200000, max_cost_micros=200000))
    item = queue.read_input(queue.claim("worker", strategy="tools"))
    calls = []
    def respond(request):
        body = json.loads(request.content)
        calls.append(body)
        final = body["messages"][-1]["role"] == "tool"
        message = {"role": "assistant", "content": json.dumps({"payload": PAYLOAD, "citations": list(final_reply().citations)})} if final else {
            "role": "assistant", "content": None, "tool_calls": [{"id": "requirements", "type": "function",
                "function": {"name": "get_job_requirements", "arguments": "{}"}}]}
        return httpx.Response(200, json={"id": "fixture", "object": "chat.completion", "created": 1, "model": "fixture-model",
            "choices": [{"index": 0, "message": message, "finish_reason": "stop" if final else "tool_calls"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}})
    with SDKToolProvider(queue, base_url="https://fixture.invalid/v1/", api_key="fixture-explicit", model="fixture-model",
                         tool_support="supported", transport=httpx.MockTransport(respond)) as provider:
        with pytest.raises(ValueError):
            run_durable_tools(queue, item, provider, price=PRICE, as_of_month="2026.11")
        assert calls == []
        result, traffic = run_durable_tools(queue, item, provider, price=PRICE, as_of_month="2026.11", allow_configured=True)
    assert result.state == "succeeded" and traffic["new_requests"] == len(calls) == 2
    engine.dispose()


def test_partial_tool_usage_overage_keeps_conservative_shared_cost(queue):
    submit(queue)
    # A known input overage with unknown output still consumes conservative budget.
    provider = ScriptedToolProvider([replace(final_reply(), input_tokens=50000, output_tokens=None)])
    result, _ = run_durable_tools(queue, queue.read_input(queue.claim("worker")), provider, price=PRICE, as_of_month="2026.11")
    assert result.state == "succeeded"
    status = queue.status("pipeline-batch")
    assert status["charged_tokens"] >= 50000
    assert status["charged_cost_micros"] >= 50000
    with queue.engine.connect() as conn:
        assert conn.scalar(select(tool_rounds.c.reported_cost_micros)) is None


@pytest.mark.parametrize("url", ["mysql+pymysql://fixture@db.example/hr_upgrade_test_x",
    "mysql+pymysql://fixture@127.0.0.1/production", "sqlite:///hr_upgrade_test_x",
    "mysql+pymysql://fixture@127.0.0.1/hr_upgrade_test_x?x=y"])
def test_mysql_acceptance_rejects_non_disposable_connections_without_connecting(url):
    from eval.mysql_queue_acceptance import validate_database_url
    with pytest.raises(ValueError):
        validate_database_url(url)


@pytest.mark.parametrize("blocked", [None, "manual", "education", "cooldown"])
def test_collection_queue_hook_runs_after_existing_gates_and_never_calls_model_or_chat(monkeypatch, blocked):
    from types import SimpleNamespace
    from pathlib import Path
    import liepin_rpa.search_execution.result_filter as module
    from liepin_rpa.search_execution.result_filter import ResultPageFilter, CandidateCard
    from liepin_rpa.task_generation.models import SearchTaskSeed
    from liepin_rpa.analysis_intake import IntakeDecision
    calls = []
    device = SimpleNamespace(tap=lambda *_: None, dump_hierarchy_text=lambda: "<hierarchy/>",
                             current_app=lambda: {}, press_back=lambda: None)
    dispatcher = SimpleNamespace(submit=lambda **values: calls.append(values) or IntakeDecision("queued", "queued", "fake-job"))
    result_filter = ResultPageFilter(device, detector=object(), write_to_db=True, send_messages=False,
        job_profile_path=Path("config/job_profiles/finance_shared_management.yaml"), selection_mode="jd_smart",
        search_batch_id="test-batch", analysis_dispatcher=dispatcher)
    result_filter._wait = lambda *_: None
    result_filter._record_entered_candidate_detail = lambda **_: None
    result_filter._is_intention_communication_page = lambda _: False
    result_filter._handle_manual_review_suppressed_candidate = lambda **_: "manual"
    result_filter._handle_unified_bachelor_requirement_failed = lambda **_: "education"
    monkeypatch.setattr(module, "get_settings", lambda: SimpleNamespace())
    monkeypatch.setattr(module, "get_candidate_record_by_fingerprint", lambda *_: {"manual_suppress_communication": 1} if blocked == "manual" else None)
    monkeypatch.setattr(module, "get_candidate_record_by_experience_hash", lambda *_: None)
    monkeypatch.setattr(module, "get_candidate_record_by_strong_identity_evidence", lambda *_, **__: None)
    monkeypatch.setattr(module, "was_analyzed_in_prior_batch_within_days", lambda *_, **__: blocked == "cooldown")
    monkeypatch.setattr(module, "record_candidate_search_hit", lambda *_, **__: 1)
    report = SimpleNamespace(sections={"求职意向": ["上海"], "工作经历": ["财务共享中心，负责核算。"]},
        structured_resume={"basic_info": {"name": "虚构张女士", "age": 34}, "work_experiences": [],
            "education_experiences": [] if blocked == "education" else [{"degree": "本科", "admission_type": "统招"}]})
    monkeypatch.setattr(module, "CandidateProfileCollector", lambda **_: SimpleNamespace(collect_current_profile=lambda: report))
    def forbidden(*_, **__):
        raise AssertionError("Queued collection cannot invoke model or chat")
    monkeypatch.setattr(module, "analyze_resume_record", forbidden)
    result_filter._tap_continue_communication = forbidden
    outcome = result_filter._collect_profile_for_candidate(
        seed=SearchTaskSeed(brand_keyword="", role_keyword="财务", query_text="财务", strategy="jd_smart"),
        card=CandidateCard("card", "虚构张女士 34岁 本科", "[0,0][100,100]"))
    if blocked:
        assert calls == []
    else:
        assert len(calls) == 1 and outcome.analysis_job_id == "fake-job"
        assert outcome.match_score is None and outcome.message_send_success is False
