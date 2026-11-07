"""Local synthetic durable-tool and HR-projection demo; never uses Settings or real providers."""
from pathlib import Path

from sqlalchemy import insert, select, func

from scripts.run_hr_demo import build_demo_app
from eval.remaining_acceptance import RECORD, PAYLOAD, QUOTE
from eval.fixtures.tool_sdk_server import ToolSDKFixtureServer
from liepin_rpa.analysis_intake import SyntheticCollectionIntake
from liepin_rpa.analysis_jobs import AnalysisJobQueue, JobBudget
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.analysis_projection import project_current_result
from liepin_rpa.analysis_worker import AnalysisWorker
from liepin_rpa.bounded_tools.durable import DurableToolWorkerAdapter
from liepin_rpa.bounded_tools.sdk_fixture import SDKToolFixtureProvider
from liepin_rpa.analysis_observability import Price
from liepin_rpa.db import resume_source_locators
from decimal import Decimal


def build_integrated_demo_app(*, demo_dir=None):
    directory = demo_dir or Path(__file__).resolve().parents[1] / "artifacts/hr_demo/integrated-v1"
    app = build_demo_app(scenario="ai", include_shadow=True, queue_projection=True, demo_dir=directory)
    engine = app.state.services.candidates.query_service.engine
    queue = AnalysisJobQueue(engine)
    queue.create_batch("synthetic-ai-1", max_tokens=1000000, max_cost_micros=1000000)
    person = "remaining-browser-person"
    decision = SyntheticCollectionIntake(queue).submit(batch_id="synthetic-ai-1", candidate_fingerprint=person,
        candidate_name="虚构工具回合演示", job_profile_id="ai_development_engineer_v1", resume_record=RECORD,
        pipeline_fingerprint=content_hash("integrated-demo-v1"), budget=JobBudget(max_tokens=200000, max_cost_micros=200000))
    with engine.begin() as connection:
        if not connection.execute(select(resume_source_locators.c.id).where(resume_source_locators.c.candidate_fingerprint == person)).first():
            connection.execute(insert(resume_source_locators).values(
                id=(connection.scalar(select(func.max(resume_source_locators.c.id))) or 0) + 1,
                candidate_fingerprint=person, candidate_name="虚构工具回合演示", job_profile_id="ai_development_engineer_v1",
                search_keyword="AI 开发工程师", search_batch_id="synthetic-ai-1", locator_quality="snapshot"))
    with ToolSDKFixtureServer(payload=PAYLOAD, quote=QUOTE) as fixture:
        with SDKToolFixtureProvider(queue, base_url=fixture.base_url) as provider:
            callback = DurableToolWorkerAdapter(queue, provider,
                price=Price("synthetic-fixed-v1", Decimal("1"), Decimal("2")), as_of_month="2026.11")
            AnalysisWorker(queue, callback, strategy="tools").run_ready(max_dispatches=3)
    if project_current_result(queue, decision.job_id) not in {"projected", "already_projected"}:
        raise ValueError("Integrated synthetic demo did not produce a current result")
    return app
