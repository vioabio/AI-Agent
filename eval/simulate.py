"""Run synthetic fixtures with budgets and call traces; never calls a live provider."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
import uuid

from liepin_rpa.analysis_contract import analysis_contract_from_job_profile, validate_analysis_payload
from liepin_rpa.analysis_observability import (
    BudgetExhausted, CallEvent, Price, RequestBudget, write_call_events, summarize_call_events,
)
from liepin_rpa.analysis_versioning import content_hash, pipeline_manifest
from eval.run_eval import ROOT, _jsonl, _job_manifest, evaluate, load_dataset

MODEL = "synthetic-fixture-model"
PRICE = Price("synthetic-rate-v1", Decimal("1"), Decimal("2"))


def simulate(dataset: Path, scenarios: Path, output_dir: Path, *, split: str = "dev",
             max_requests: int = 100, max_tokens: int = 100_000,
             max_cost: Decimal | None = None, max_attempts: int = 3) -> Path:
    if split not in {"dev", "test"} or type(max_attempts) is not int or not 1 <= max_attempts <= 4:
        raise ValueError("Invalid split or attempt cap")
    rows = load_dataset(dataset)
    if any(row["source_type"] != "synthetic" for row in rows):
        raise ValueError("Synthetic runner accepts only synthetic datasets")
    by_id = {}
    for scenario in _jsonl(scenarios):
        sample_id = scenario.get("sample_id")
        if not isinstance(sample_id, str) or sample_id in by_id or sample_id not in {r["sample_id"] for r in rows}:
            raise ValueError("Invalid/duplicate scenario sample_id")
        attempts = scenario.get("attempts")
        if not isinstance(attempts, list) or not attempts or len(attempts) > 4:
            raise ValueError("Scenario requires one to four fixed attempts")
        # Validate the complete fixture before creating outputs/reserving anything.
        for attempt in attempts:
            if not isinstance(attempt, dict):
                raise ValueError("Invalid simulated attempt")
            for key in ("input_tokens", "output_tokens"):
                value = attempt.get(key)
                if value is not None and (type(value) is not int or value < 0):
                    raise ValueError("Invalid simulated usage")
            latency = attempt.get("latency_ms", 1)
            if type(latency) not in (int, float) or not 0 <= latency <= 3_600_000:
                raise ValueError("Invalid simulated latency")
            if attempt.get("error_code") not in (None, "rate_limited", "timeout", "authentication_failed"):
                raise ValueError("Unknown simulated error code")
        by_id[sample_id] = scenario
    selected = [row for row in rows if row["split"] == split]
    if not selected:
        raise ValueError("Selected split has no samples")
    run_id = "sim-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    run_dir = output_dir / run_id
    budget = RequestBudget(max_requests=max_requests, max_tokens=max_tokens, max_cost=max_cost)
    events, predictions, identities = [], [], {}
    contract_hash = content_hash((ROOT / "src/liepin_rpa/analysis_contract.py").read_text(encoding="utf-8"))
    rules_hash = content_hash((ROOT / "src/liepin_rpa/reporting_rules.py").read_text(encoding="utf-8"))
    for row in selected:
        job_id, sample_id = row["job_profile_id"], row["sample_id"]
        profile, _ = _job_manifest(job_id)
        prompt = (ROOT / profile["analysis_prompt_path"]).read_text(encoding="utf-8")
        identity = pipeline_manifest(
            job_profile=profile, system_prompt=prompt, provider="synthetic_fixture", model=MODEL,
            decoding_params={"temperature": 0, "max_tokens": 128},
            contract_version=contract_hash, rules_version=rules_hash,
        )
        identities[job_id] = identity
        trace_id = uuid.uuid4().hex
        prediction = {
            "sample_id": sample_id, "execution_status": "missing_fixture", "simulated": True,
            "inference": identity, "trace_id": trace_id, "analysis_run_id": run_id,
            "resume_hash": content_hash(row["resume_text"]),
            "dataset_hash": content_hash(rows),
        }
        for number, attempt in enumerate(by_id.get(sample_id, {}).get("attempts", [])[:max_attempts], 1):
            # This runner uses declared synthetic token reservations, not a tokenizer estimate.
            reservation_input = max(128, attempt.get("input_tokens") or 0)
            reservation_output = max(128, attempt.get("output_tokens") or 0)
            try:
                reservation = budget.reserve(
                    input_tokens=reservation_input, max_output_tokens=reservation_output, price=PRICE
                )
            except BudgetExhausted:
                prediction["execution_status"] = "budget_exhausted"
                break
            error = attempt.get("error_code")
            payload = attempt.get("payload", {})
            state = "transient_error" if error else "succeeded"
            if error == "authentication_failed":
                state = "blocked"
            if not error and (not isinstance(payload, dict) or validate_analysis_payload(
                payload, contract=analysis_contract_from_job_profile(profile)
            )):
                state, error = "invalid_output", "invalid_payload"
            settlement = budget.settle(
                reservation, input_tokens=attempt.get("input_tokens"),
                output_tokens=attempt.get("output_tokens"), price=PRICE,
            )
            events.append(CallEvent(
                trace_id=trace_id, analysis_run_id=run_id, sample_id=sample_id, attempt=number,
                provider="synthetic_fixture", model=MODEL,
                pipeline_fingerprint=identity["pipeline_fingerprint"],
                execution_status=state, error_code=error, latency_ms=attempt.get("latency_ms", 1),
                input_tokens=settlement["input_tokens"], output_tokens=settlement["output_tokens"],
                estimated_cost=settlement["estimated_cost"], price_version=PRICE.version,
            ))
            prediction["execution_status"] = state
            if state == "succeeded":
                prediction["payload"] = payload
                for key in ("retrieved_spans", "evidence_refs"):
                    if key in attempt:
                        prediction[key] = attempt[key]
                break
            if state == "blocked" or settlement["budget_exceeded"]:
                break
        predictions.append(prediction)
    run_dir.mkdir(parents=True, exist_ok=False)
    predictions_path = run_dir / "predictions.jsonl"
    predictions_path.write_text(
        "".join(json.dumps(p, ensure_ascii=False) + "\n" for p in predictions), encoding="utf-8"
    )
    write_call_events(run_dir / "call_events.jsonl", events)
    report = evaluate(dataset, predictions_path, split=split)
    report["simulation"] = {
        "run_id": run_id, "identities": identities, "budget": budget.summary(),
        "scenario_hash": content_hash(_jsonl(scenarios)),
        "event_hash": content_hash([e.as_dict() for e in events]),
        "calls": summarize_call_events(events),
        "cost_is_synthetic": True, "latency_is_synthetic": True,
    }
    report["run_id"] = run_id
    report_path = run_dir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report_path


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--scenarios", type=Path, required=True)
    parser.add_argument("--split", choices=("dev", "test"), default="dev")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "artifacts/eval/simulations")
    parser.add_argument("--max-requests", type=int, default=100)
    parser.add_argument("--max-tokens", type=int, default=100_000)
    parser.add_argument("--max-cost", type=Decimal)
    args = parser.parse_args(argv)
    try:
        path = simulate(args.dataset, args.scenarios, args.output_dir, split=args.split,
                        max_requests=args.max_requests, max_tokens=args.max_tokens, max_cost=args.max_cost)
    except (ValueError, OSError):
        parser.exit(2, "Synthetic simulation rejected; check fixture schema and budgets.\n")
    print(f"Synthetic simulation only; no live calls: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
