"""Candidate-group paired bootstrap, descriptive even for synthetic fixtures."""
from __future__ import annotations

import random
from eval.metrics import classification_metrics


def _percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower = int(index)
    return ordered[lower] + (ordered[min(lower + 1, len(ordered) - 1)] - ordered[lower]) * (index - lower)


def paired_bootstrap(before: list[dict], after: list[dict], *, iterations: int = 500, seed: int = 2026) -> dict:
    if type(iterations) is not int or not 100 <= iterations <= 10_000:
        raise ValueError("Bootstrap iterations must be between 100 and 10000")
    left = {row["sample_id"]: row for row in before}
    right = {row["sample_id"]: row for row in after}
    if len(left) != len(before) or len(right) != len(after) or left.keys() != right.keys() or not left:
        raise ValueError("Bootstrap requires unique identical paired sample IDs")
    groups = {}
    for sample_id, row in left.items():
        if any(row[key] != right[sample_id][key] for key in ("gold", "group_id", "job_profile_id", "source_type")):
            raise ValueError("Bootstrap case definitions differ")
        groups.setdefault(row["group_id"], []).append(sample_id)
    if len(groups) < 2:
        return {"status": "insufficient_groups", "group_count": len(groups), "intervals": {}}
    rng, group_ids = random.Random(seed), sorted(groups)
    draws = {"macro_f1": [], "qualified_false_rejection": [], "relevant_precision": [], "manual_review": []}

    def values(metrics):
        return {
            "macro_f1": metrics["macro_f1"],
            "qualified_false_rejection": metrics["qualified_false_rejection"]["rate"],
            "relevant_precision": metrics["per_class"]["relevant"]["precision"],
            "manual_review": metrics["manual_review"]["rate"],
        }

    for _ in range(iterations):
        sampled = [sample_id for group in rng.choices(group_ids, k=len(group_ids)) for sample_id in groups[group]]
        old = values(classification_metrics([left[i] for i in sampled]))
        new = values(classification_metrics([right[i] for i in sampled]))
        for key in draws:
            if old[key] is not None and new[key] is not None:
                draws[key].append(new[key] - old[key])
    observed_before = values(classification_metrics(before))
    observed_after = values(classification_metrics(after))
    intervals = {}
    for key, samples in draws.items():
        intervals[key] = {
            "delta_after_minus_before": observed_after[key] - observed_before[key]
            if observed_before[key] is not None and observed_after[key] is not None else None,
            "lower": _percentile(samples, 0.025) if samples else None,
            "upper": _percentile(samples, 0.975) if samples else None,
            "valid_draws": len(samples), "requested_draws": iterations,
        }
    return {
        "status": "computed", "method": "candidate_group_paired_percentile_bootstrap",
        "confidence_level": 0.95, "group_count": len(groups), "seed": seed,
        "intervals": intervals,
    }
