"""Explicit configured queue worker. No device or messaging; no automatic migrations."""
import argparse
import json

from sqlalchemy import inspect, select

from liepin_rpa.analysis_job_schema import queue_metadata, analysis_jobs
from liepin_rpa.analysis_jobs import AnalysisJobQueue
from liepin_rpa.analysis_projection import project_current_result
from liepin_rpa.analysis_worker import AnalysisWorker


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--price-version", required=True)
    parser.add_argument("--currency", default="USD")
    parser.add_argument("--max-dispatches", type=int, default=10)
    parser.add_argument("--concurrency", type=int, choices=(1, 2, 4), default=1)
    parser.add_argument("--project-hr", action="store_true", help="Explicitly project validated current results into HR machine fields")
    parser.add_argument("--projection-only", action="store_true", help="Retry projections without opening a model client")
    parser.add_argument("--execute-provider", action="store_true", help="Explicit opt-in to actual configured HTTPS requests")
    parser.add_argument("--tools", action="store_true", help="Use the bounded durable tool protocol instead of single-pass analysis")
    parser.add_argument("--tool-capability", choices=("supported", "unsupported", "unknown"), default="unknown")
    parser.add_argument("--as-of-month", help="Explicit frozen YYYY.MM cutoff for tool date calculations")
    args = parser.parse_args(argv)
    if not args.projection_only and not args.execute_provider:
        parser.error("Select --projection-only or explicitly enable --execute-provider")
    from liepin_rpa.config import get_settings
    from liepin_rpa.db import get_engine
    settings = get_settings()
    engine = get_engine(settings)
    if not set(queue_metadata.tables) <= set(inspect(engine).get_table_names()):
        parser.error("Explicitly migrate to 20261105_08 before starting the worker")
    queue = AnalysisJobQueue(engine, binding_source="gateway_input", price_version=args.price_version, currency=args.currency)
    with queue._transaction():
        pass
    if args.projection_only:
        with engine.connect() as conn:
            ids = conn.scalars(select(analysis_jobs.c.job_id).where(analysis_jobs.c.status == "succeeded")).all()
        counts = {}
        for job in ids:
            try:
                status = project_current_result(queue, job)
            except Exception:
                status = "projection_failed"
            counts[status] = counts.get(status, 0) + 1
        print(json.dumps(counts))
        return int(bool(counts.get("projection_failed")))
    from liepin_rpa.queued_openai import QueuedOpenAIAdapter
    if settings.llm_api_key is None:
        parser.error("Configured worker requires an explicit API credential")
    if args.tools:
        if not args.as_of_month:
            parser.error("--tools requires an explicit --as-of-month")
        from decimal import Decimal
        from liepin_rpa.bounded_tools.sdk_fixture import SDKToolProvider
        from liepin_rpa.bounded_tools.durable import DurableToolWorkerAdapter
        from liepin_rpa.bounded_tools.runner import LoopPolicy
        from liepin_rpa.analysis_observability import Price
        from liepin_rpa.queued_request_plan import QueuedRequestPlan, implementation_hash
        import openai
        def analyze(item):
            plan = QueuedRequestPlan.from_dict(item.request_plan)
            if plan.sdk_version != openai.__version__ or plan.implementation_hash != implementation_hash():
                raise ValueError("Frozen request implementation changed")
            price = Price(plan.price_version, Decimal(plan.input_micro_per_million) / Decimal(1000000),
                          Decimal(plan.output_micro_per_million) / Decimal(1000000), plan.currency)
            with SDKToolProvider(queue, base_url=settings.llm_base_url.rstrip("/") + "/", api_key=settings.llm_api_key.get_secret_value(),
                model=plan.model, tool_support=args.tool_capability) as provider:
                return DurableToolWorkerAdapter(queue, provider, price=price, as_of_month=args.as_of_month,
                    policy=LoopPolicy(max_output_tokens=plan.max_output_tokens, max_input_units=plan.input_reserve),
                    allow_configured=True)(item)
        result = AnalysisWorker(queue, analyze, concurrency=args.concurrency, project_to_hr=args.project_hr, strategy="tools").run_ready(
            max_dispatches=args.max_dispatches)
        print(json.dumps(result))
        return int(bool(result.get("projection_failed")))
    with QueuedOpenAIAdapter(queue, base_url=settings.llm_base_url.rstrip("/") + "/",
                            api_key=settings.llm_api_key.get_secret_value()) as adapter:
        result = AnalysisWorker(queue, adapter, concurrency=args.concurrency, project_to_hr=args.project_hr, strategy="fulltext").run_ready(
            max_dispatches=args.max_dispatches)
    print(json.dumps(result))
    return int(bool(result.get("projection_failed")))


if __name__ == "__main__":
    raise SystemExit(main())
