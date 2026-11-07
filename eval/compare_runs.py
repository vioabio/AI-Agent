"""Describe paired replay differences; do not infer statistical significance."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import classification_metrics
from eval.statistics import paired_bootstrap


def compare_runs(before: dict, after: dict, *, iterations: int = 500, seed: int = 2026) -> dict:
    for key in ("dataset_hash", "split"):
        if before["manifest"][key] != after["manifest"][key]:
            raise ValueError("Comparison requires identical dataset hash and split")
    left = {case["sample_id"]: case for case in before["cases"]}
    right = {case["sample_id"]: case for case in after["cases"]}
    if len(left) != len(before["cases"]) or len(right) != len(after["cases"]):
        raise ValueError("Comparison cannot contain duplicate sample IDs")
    if not left or left.keys() != right.keys():
        raise ValueError("Comparison requires the same nonempty set of sample IDs")
    changes = []
    for sample_id in sorted(left):
        old, new = left[sample_id], right[sample_id]
        for key in ("gold", "group_id", "job_profile_id", "source_type"):
            if old[key] != new[key]:
                raise ValueError("Case definitions differ between reports")
        if (old["predicted"], old["execution_status"]) != (new["predicted"], new["execution_status"]):
            changes.append({
                "sample_id": sample_id, "gold": old["gold"],
                "before": old["predicted"], "after": new["predicted"],
                "before_execution": old["execution_status"], "after_execution": new["execution_status"],
            })
    return {
        "comparison_mode": "paired_descriptive_only", "quality_claim_allowed": False,
        "sample_count": len(left), "changed_count": len(changes), "changes": changes,
        "before_metrics": classification_metrics(list(left.values())),
        "after_metrics": classification_metrics(list(right.values())),
        "bootstrap": paired_bootstrap(list(left.values()), list(right.values()), iterations=iterations, seed=seed),
        "limitations": [
            "Bootstrap intervals are descriptive and do not establish dataset representativeness.",
            "Saved responses do not establish model or prompt provenance.",
        ],
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--iterations", type=int, default=500)
    parser.add_argument("--seed", type=int, default=2026)
    args = parser.parse_args(argv)
    try:
        result = compare_runs(
            json.loads(args.before.read_text(encoding="utf-8")),
            json.loads(args.after.read_text(encoding="utf-8")),
            iterations=args.iterations, seed=args.seed,
        )
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(2, "Report comparison rejected; check schemas, dataset and split.\n")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Paired descriptive comparison: {result['changed_count']}/{result['sample_count']} changed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
