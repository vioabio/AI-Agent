import json
from pathlib import Path

import pytest

from copy import deepcopy
from eval.compare_runs import compare_runs

from eval.metrics import classification_metrics
from eval.run_eval import evaluate, load_dataset


DATASET = Path("eval/fixtures/synthetic_dev.jsonl")
PREDICTIONS = Path("eval/fixtures/synthetic_predictions.jsonl")


def test_replay_keeps_failures_in_denominator_and_separates_synthetic_sources():
    result = evaluate(DATASET, PREDICTIONS)
    assert result["metrics"]["sample_count"] == 4
    assert result["metrics"]["confusion_matrix"]["relevant"]["needs_review"] == 1
    assert result["metrics"]["confusion_matrix"]["needs_review"]["not_relevant"] == 1
    assert result["metrics"]["execution_failure"] == {"numerator": 1, "denominator": 4, "rate": 0.25}
    assert result["metrics"]["manual_review"]["numerator"] == 1
    assert result["source_counts"] == {"synthetic": 4}
    assert result["quality_claim_allowed"] is False
    assert result["cost"]["amount"] is None
    assert result["score_interval"]["evaluated_count"] == 3
    assert result["manifest"]["jobs"]["ai_development_engineer_v1"]["prompt_hash"]
    assert "resume_text" not in json.dumps(result)
    assert "analysis_reason" not in json.dumps(result)


def test_missing_and_invalid_predictions_remain_review_cases(tmp_path):
    predictions = tmp_path / "predictions.jsonl"
    predictions.write_text(json.dumps({"sample_id": "synth-001-ai", "raw_response": "invalid"}), encoding="utf-8")
    result = evaluate(DATASET, predictions)
    assert result["metrics"]["sample_count"] == 4
    assert result["metrics"]["manual_review"]["rate"] == 1
    assert result["metrics"]["per_class"]["relevant"]["precision"] is None
    assert result["metrics"]["execution_failure"]["numerator"] == 4


@pytest.mark.parametrize("leak_type", ["group", "text"])
def test_dataset_rejects_leakage_before_selecting_split(tmp_path, leak_type):
    rows = [json.loads(line) for line in DATASET.read_text(encoding="utf-8").splitlines()]
    rows[1]["split"] = "test"
    if leak_type == "group":
        rows[1]["group_id"] = rows[0]["group_id"]
    else:
        rows[1]["resume_text"] = rows[0]["resume_text"]
    path = tmp_path / "dataset.jsonl"
    path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
    with pytest.raises(ValueError, match="leaks across"):
        load_dataset(path)


def test_no_automatic_decisions_reports_undefined_automatic_error():
    result = classification_metrics([
        {"gold": "relevant", "predicted": "needs_review", "execution_status": "invalid_output"}
    ])
    assert result["qualified_false_rejection"]["rate"] == 0
    assert result["manual_review"]["rate"] == 1
    assert result["automatic_error"]["rate"] is None


def test_frozen_test_split_is_not_silently_replaced_by_development_data():
    with pytest.raises(ValueError, match="no samples"):
        evaluate(DATASET, PREDICTIONS, split="test")


def test_comparison_rejects_different_split_and_tracks_changed_cases():
    before = evaluate(DATASET, PREDICTIONS)
    after = deepcopy(before)
    after["cases"][0]["predicted"] = "needs_review"
    comparison = compare_runs(before, after)
    assert comparison["changed_count"] == 1
    assert comparison["quality_claim_allowed"] is False
    after["manifest"]["split"] = "test"
    with pytest.raises(ValueError, match="identical dataset"):
        compare_runs(before, after)
