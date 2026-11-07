from __future__ import annotations

LABELS = ("relevant", "needs_review", "not_relevant")


def classification_metrics(cases: list[dict]) -> dict:
    if not cases:
        raise ValueError("Evaluation requires at least one case")
    matrix = {gold: {pred: 0 for pred in LABELS} for gold in LABELS}
    for case in cases:
        matrix[case["gold"]][case["predicted"]] += 1
    per_class = {}
    for label in LABELS:
        tp = matrix[label][label]
        support = sum(matrix[label].values())
        predicted = sum(matrix[gold][label] for gold in LABELS)
        per_class[label] = {
            "support": support, "predicted_count": predicted, "true_positive": tp,
            "precision": tp / predicted if predicted else None,
            "recall": tp / support if support else None,
            # Fixed three-label macro F1, with zero_division=0.
            "f1": 2 * tp / (support + predicted) if support + predicted else 0.0,
        }
    count = len(cases)
    gold_relevant = per_class["relevant"]["support"]
    false_rejects = matrix["relevant"]["not_relevant"]
    review_count = per_class["needs_review"]["predicted_count"]
    auto_cases = [c for c in cases if c["predicted"] != "needs_review"]
    auto_errors = sum(c["gold"] != c["predicted"] for c in auto_cases)
    failed = sum(c["execution_status"] != "succeeded" for c in cases)
    return {
        "sample_count": count, "confusion_matrix": matrix, "per_class": per_class,
        "macro_f1": sum(c["f1"] for c in per_class.values()) / len(LABELS),
        "accuracy": sum(matrix[label][label] for label in LABELS) / count,
        "qualified_false_rejection": {
            "numerator": false_rejects, "denominator": gold_relevant,
            "rate": false_rejects / gold_relevant if gold_relevant else None,
        },
        "manual_review": {"numerator": review_count, "denominator": count, "rate": review_count / count},
        "automatic_coverage": {"numerator": len(auto_cases), "denominator": count, "rate": len(auto_cases) / count},
        "automatic_error": {
            "numerator": auto_errors, "denominator": len(auto_cases),
            "rate": auto_errors / len(auto_cases) if auto_cases else None,
        },
        "execution_failure": {"numerator": failed, "denominator": count, "rate": failed / count},
    }
