from copy import deepcopy
from decimal import Decimal
import json

import pytest

from eval.evidence import evidence_metrics
from eval.generate_synthetic import generate
from eval.run_eval import evaluate, load_dataset
from eval.simulate import simulate
from eval.statistics import paired_bootstrap
from liepin_rpa.analysis_observability import BudgetExhausted, Price, RequestBudget
from liepin_rpa.analysis_versioning import analysis_cache_key, pipeline_manifest


def _identity(**overrides):
    settings = {
        "job_profile": {"id": "test", "selection": {"min": 80}},
        "system_prompt": "synthetic prompt", "provider": "fixture", "model": "fixture-model",
        "decoding_params": {"temperature": 0, "max_tokens": 128},
        "contract_version": "contract-v1", "rules_version": "rules-v1",
    }
    settings.update(overrides)
    return pipeline_manifest(**settings)


@pytest.mark.parametrize("change", [
    {"model": "other-model"}, {"provider": "other-provider"},
    {"system_prompt": "other-prompt"}, {"job_profile": {"id": "other-job"}},
    {"contract_version": "contract-v2"}, {"rules_version": "rules-v2"},
    {"decoding_params": {"temperature": 0.5}},
    {"retrieval_version": "retrieval-v2"}, {"tool_version": "tools-v2"},
])
def test_every_inference_change_invalidates_cache_key(change):
    before, after = _identity(), _identity(**change)
    key_args = {"candidate_id": "opaque-id", "resume_text": "synthetic resume"}
    assert before["pipeline_fingerprint"] != after["pipeline_fingerprint"]
    assert analysis_cache_key(**key_args, pipeline_fingerprint=before["pipeline_fingerprint"]) != analysis_cache_key(
        **key_args, pipeline_fingerprint=after["pipeline_fingerprint"]
    )


def test_manifest_is_order_stable_and_excludes_credentials():
    assert _identity(job_profile={"b": 2, "a": 1}) == _identity(job_profile={"a": 1, "b": 2})
    with pytest.raises(ValueError):
        _identity(decoding_params={"api_key": "private"})
    with pytest.raises(ValueError):
        _identity(decoding_params={"temperature": float("nan")})
    identity = _identity()
    assert "synthetic prompt" not in json.dumps(identity)
    with pytest.raises(ValueError):
        analysis_cache_key(candidate_id="x", resume_text="x", pipeline_fingerprint="z" * 64)


def test_unknown_usage_is_charged_and_duplicate_settlement_rejected():
    price = Price("fixture", Decimal(1), Decimal(2))
    budget = RequestBudget(max_requests=2, max_tokens=300)
    reservation = budget.reserve(input_tokens=100, max_output_tokens=100, price=price)
    result = budget.settle(reservation, input_tokens=100, output_tokens=None, price=price)
    assert result["estimated_cost"] is None
    assert budget.summary()["charged_tokens"] == 200
    assert budget.summary()["unknown_usage_calls"] == 1
    with pytest.raises(BudgetExhausted):
        budget.reserve(input_tokens=100, max_output_tokens=100, price=price)
    with pytest.raises(ValueError):
        budget.settle(reservation, input_tokens=1, output_tokens=1, price=price)


def test_cost_cap_needs_price_and_request_failures_still_consume_admissions():
    price = Price("fixture", Decimal(1), Decimal(2))
    budget = RequestBudget(max_requests=1, max_tokens=500, max_cost=Decimal("0.001"))
    with pytest.raises(BudgetExhausted):
        budget.reserve(input_tokens=100, max_output_tokens=100, price=None)
    assert budget.requests == 0
    reservation = budget.reserve(input_tokens=100, max_output_tokens=100, price=price)
    budget.settle(reservation, input_tokens=None, output_tokens=None, price=price)
    with pytest.raises(BudgetExhausted):
        budget.reserve(input_tokens=1, max_output_tokens=1, price=price)


def test_one_budget_cannot_mix_currencies():
    price = Price("fixture", Decimal(1), Decimal(2))
    budget = RequestBudget(max_requests=10, max_tokens=1000)
    reservation = budget.reserve(input_tokens=10, max_output_tokens=10, price=price)
    budget.settle(reservation, input_tokens=10, output_tokens=10, price=price)
    with pytest.raises(ValueError, match="currencies"):
        budget.reserve(input_tokens=10, max_output_tokens=10,
                       price=Price("other-currency", Decimal(1), Decimal(2), "CNY"))


def test_actual_usage_overrun_blocks_new_calls_and_price_cannot_change():
    price = Price("fixture", Decimal(1), Decimal(2))
    budget = RequestBudget(max_requests=10, max_tokens=200)
    reservation = budget.reserve(input_tokens=50, max_output_tokens=50, price=price)
    with pytest.raises(ValueError):
        budget.settle(reservation, input_tokens=1, output_tokens=1, price=None)
    result = budget.settle(reservation, input_tokens=250, output_tokens=None, price=price)
    assert result["budget_exceeded"]
    assert budget.charged_tokens == 300
    with pytest.raises(BudgetExhausted):
        budget.reserve(input_tokens=1, max_output_tokens=1, price=price)


def test_synthetic_runner_records_all_attempts_and_validates_provenance(tmp_path):
    dataset, scenarios = generate(tmp_path / "fixtures")
    rows = load_dataset(dataset)
    assert len(rows) == 42
    assert sum(row["split"] == "dev" for row in rows) == 30
    report_path = simulate(dataset, scenarios, tmp_path / "runs", split="dev")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    events = [json.loads(line) for line in (report_path.parent / "call_events.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len(report["cases"]) == 30
    assert len(events) > 30
    assert report["simulation"]["calls"]["retry_call_count"] > 0
    assert report["simulation"]["calls"]["estimated_total_cost"] is None
    assert report["simulation"]["budget"]["pending_reservations"] == 0
    assert all(event["simulated"] is True for event in events)
    assert "resume_text" not in json.dumps(events)
    assert "analysis_reason" not in json.dumps(events)
    assert report["manifest"]["inference_provenance"] == {"self_consistent_synthetic_recording": 30}
    assert report["quality_claim_allowed"] is False
    assert report["evidence"]["semantic_support"]["rate"] is None


def test_mismatched_recording_cannot_become_a_valid_scored_case(tmp_path):
    dataset, scenarios = generate(tmp_path / "fixtures")
    path = simulate(dataset, scenarios, tmp_path / "runs", max_requests=1)
    predictions_path = path.parent / "predictions.jsonl"
    predictions = [json.loads(line) for line in predictions_path.read_text(encoding="utf-8").splitlines()]
    predictions[0]["resume_hash"] = "forged"
    predictions_path.write_text("".join(json.dumps(row) + "\n" for row in predictions), encoding="utf-8")
    report = evaluate(dataset, predictions_path)
    assert report["cases"][0]["execution_status"] == "provenance_mismatch"
    assert report["cases"][0]["predicted"] == "needs_review"
    assert "simulation" not in report  # A plain replay cannot invent request telemetry.


def test_incomplete_inference_identity_is_not_accepted_as_bound_provenance(tmp_path):
    dataset, scenarios = generate(tmp_path / "fixtures")
    path = simulate(dataset, scenarios, tmp_path / "runs", max_requests=1)
    prediction_path = path.parent / "predictions.jsonl"
    predictions = [json.loads(line) for line in prediction_path.read_text(encoding="utf-8").splitlines()]
    predictions[0]["inference"].pop("model")
    prediction_path.write_text("".join(json.dumps(row) + "\n" for row in predictions), encoding="utf-8")
    assert evaluate(dataset, prediction_path)["cases"][0]["execution_status"] == "provenance_mismatch"


def test_exhausted_simulation_budget_never_dispatches_extra_calls(tmp_path):
    dataset, scenarios = generate(tmp_path / "fixtures")
    path = simulate(dataset, scenarios, tmp_path / "runs", max_requests=2)
    report = json.loads(path.read_text(encoding="utf-8"))
    assert report["simulation"]["budget"]["request_count"] == 2
    assert report["simulation"]["calls"]["call_count"] == 2
    assert report["metrics"]["sample_count"] == 30
    assert report["metrics"]["manual_review"]["numerator"] >= 29


def test_synthetic_cost_cap_blocks_dispatch_and_real_source_is_rejected(tmp_path):
    dataset, scenarios = generate(tmp_path / "fixtures")
    path = simulate(dataset, scenarios, tmp_path / "runs", max_cost=Decimal("0.00001"))
    report = json.loads(path.read_text(encoding="utf-8"))
    assert report["simulation"]["calls"]["call_count"] == 0
    assert report["metrics"]["sample_count"] == 30
    rows = [json.loads(line) for line in dataset.read_text(encoding="utf-8").splitlines()]
    rows[0]["source_type"], rows[0]["annotator_id"] = "authorized_real", "test-only-tag"
    dataset.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    with pytest.raises(ValueError, match="only synthetic"):
        simulate(dataset, scenarios, tmp_path / "runs")


def test_quote_position_does_not_prove_semantic_support_and_overlaps_are_unioned():
    row = {"sample_id": "x", "resume_text": "0123456789😀", "gold_evidence": [{"start": 0, "end": 10}]}
    prediction = {"retrieved_spans": [{"start": 0, "end": 3}, {"start": 2, "end": 6}],
                  "evidence_refs": [{"start": 10, "end": 11, "quote": "😀"},
                                    {"start": 0, "end": 2, "quote": "wrong"}]}
    result = evidence_metrics([row], {"x": prediction})
    assert result["retrieval_recall"]["rate"] == 1
    assert result["citation_location_validity"]["rate"] == 0.5
    assert result["semantic_support"]["rate"] is None


def test_bootstrap_uses_groups_and_is_reproducible():
    before = [{"sample_id": str(i), "group_id": str(i // 2), "job_profile_id": "x", "source_type": "synthetic",
               "gold": "relevant", "predicted": "needs_review", "execution_status": "succeeded"}
              for i in range(6)]
    after = deepcopy(before)
    for row in after:
        row["predicted"] = "relevant"
    first = paired_bootstrap(before, after, iterations=100)
    assert first == paired_bootstrap(before, after, iterations=100)
    assert first["group_count"] == 3
    assert first["intervals"]["manual_review"]["lower"] == -1
    assert first["intervals"]["relevant_precision"]["valid_draws"] == 0
    assert paired_bootstrap(before[:2], after[:2], iterations=100)["status"] == "insufficient_groups"
