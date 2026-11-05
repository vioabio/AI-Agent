"""Opt-in collection dispatcher. Device gates run before this adapter; no provider calls."""
from pathlib import Path

import yaml
from sqlalchemy import inspect

from liepin_rpa.analysis_intake import CollectedAnalysisIntake, PROFILE_FILES, ROOT
from liepin_rpa.analysis_jobs import AnalysisJobQueue
from liepin_rpa.analysis_job_schema import queue_metadata
from liepin_rpa.analysis_versioning import content_hash


class CollectedDispatcher:
    def __init__(self, queue, *, job_profile_id, pipeline_fingerprint=None, request_plan=None, strategy="fulltext"):
        if strategy not in {"fulltext", "tools"}:
            raise ValueError("Unknown queue strategy")
        if job_profile_id not in PROFILE_FILES:
            raise ValueError("Unknown queue job profile")
        self.queue, self.job_profile_id = queue, job_profile_id
        self.request_plan = request_plan
        self.strategy = strategy
        self.path = ROOT / "config/job_profiles" / PROFILE_FILES[job_profile_id]
        self.profile_hash = content_hash(yaml.safe_load(self.path.read_text(encoding="utf-8")))
        profile = yaml.safe_load(self.path.read_text(encoding="utf-8"))
        prompt = ROOT / profile["analysis_prompt_path"]
        self.pipeline = pipeline_fingerprint or content_hash({"mode": "collected-queue-v1", "profile": self.profile_hash,
            "prompt": prompt.read_text(encoding="utf-8"), "dispatcher": Path(__file__).read_text(encoding="utf-8")})

    def submit(self, **kwargs):
        if (kwargs.get("job_profile_id") != self.job_profile_id
                or content_hash(yaml.safe_load(self.path.read_text(encoding="utf-8"))) != self.profile_hash):
            raise ValueError("The configured job changed during collection")
        from liepin_rpa.analysis_jobs import JobBudget
        kwargs["resume_record"] = {**kwargs["resume_record"], "_analysis_strategy": self.strategy}
        return CollectedAnalysisIntake(self.queue).submit(**kwargs, pipeline_fingerprint=self.pipeline,
            request_plan=self.request_plan, budget=JobBudget(**self.request_plan.budget_fields()) if self.request_plan else JobBudget())


def build_collected_dispatcher(engine, *, job_profile_id, batch_id, model, price_version,
                               input_price, output_price, input_reserve=30000, output_limit=1024, strategy="fulltext", concurrency_limit=1,
                               currency="USD"):
    if not set(queue_metadata.tables) <= set(inspect(engine).get_table_names()):
        raise ValueError("Explicit Alembic upgrade to 20261105_08 is required before queued collection")
    from liepin_rpa.queued_request_plan import QueuedRequestPlan
    profile = yaml.safe_load((ROOT / "config/job_profiles" / PROFILE_FILES[job_profile_id]).read_text(encoding="utf-8"))
    plan = QueuedRequestPlan.configured(model=model, system_prompt=(ROOT / profile["analysis_prompt_path"]).read_text(encoding="utf-8"),
        price_version=price_version, input_micro_per_million=input_price, output_micro_per_million=output_price,
        input_reserve=input_reserve, max_output_tokens=output_limit, currency=currency)
    queue = AnalysisJobQueue(engine, binding_source="gateway_input", price_version=price_version, currency=currency)
    from liepin_rpa.analysis_jobs import QueueLimits
    queue.configure(QueueLimits(max_in_flight=concurrency_limit))
    queue.create_batch(batch_id)
    return CollectedDispatcher(queue, job_profile_id=job_profile_id, request_plan=plan, strategy=strategy)
