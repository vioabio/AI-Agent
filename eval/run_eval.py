"""Evaluate saved provider responses; no live credentials, database or network."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

from liepin_rpa.analysis_contract import (
    analysis_contract_from_job_profile,
    parse_analysis_payload,
    validate_analysis_payload,
)
from liepin_rpa.analysis_execution import has_analysis_execution_failure
from liepin_rpa.task_generation.config_loader import load_job_profile
from liepin_rpa.analysis_versioning import content_hash, pipeline_manifest

from eval.evidence import evidence_metrics, valid_span
from eval.metrics import LABELS, classification_metrics

ROOT = Path(__file__).resolve().parents[1]
JOB_FILES = {
    "ai_development_engineer_v1": "ai_development_engineer.yaml",
    "fde_ai_deployment_engineer_v1": "fde_ai_deployment_engineer_draft.yaml",
}
ID_PATTERN = re.compile(r"[A-Za-z0-9_.-]{1,80}")


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _jsonl(path: Path) -> list[dict]:
    result = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            raise ValueError(f"Invalid JSON at line {line_number}") from None
        if not isinstance(value, dict):
            raise ValueError(f"Expected an object at line {line_number}")
        result.append(value)
    return result


def load_dataset(path: Path) -> list[dict]:
    rows = _jsonl(path)
    ids = set()
    groups = {}
    resume_splits = {}
    for index, row in enumerate(rows, 1):
        for key in ("sample_id", "group_id"):
            if not isinstance(row.get(key), str) or not ID_PATTERN.fullmatch(row[key]):
                raise ValueError(f"Invalid {key} in dataset row {index}; use opaque IDs")
        if row["sample_id"] in ids:
            raise ValueError("Duplicate dataset sample_id")
        ids.add(row["sample_id"])
        if not isinstance(row.get("gold"), str) or row["gold"] not in LABELS or (
            not isinstance(row.get("split"), str) or row["split"] not in {"dev", "test"}
        ):
            raise ValueError(f"Invalid label/split in dataset row {index}")
        if not isinstance(row.get("source_type"), str) or row["source_type"] not in {"synthetic", "authorized_real"}:
            raise ValueError(f"Missing source_type in dataset row {index}")
        if not isinstance(row.get("job_profile_id"), str) or row["job_profile_id"] not in JOB_FILES:
            raise ValueError(f"Unsupported job in dataset row {index}")
        if not isinstance(row.get("resume_text"), str) or not row["resume_text"].strip():
            raise ValueError(f"Missing resume text in dataset row {index}")
        if row["source_type"] == "authorized_real" and not row.get("annotator_id"):
            raise ValueError("Real evaluation data requires a human annotator_id")
        # Group leakage is checked across the entire dataset before selecting a split.
        group = row["group_id"]
        if group in groups and groups[group] != row["split"]:
            raise ValueError("Candidate group leaks across development/test splits")
        groups[group] = row["split"]
        text_hash = _digest(" ".join(row["resume_text"].split()).encode("utf-8"))
        if text_hash in resume_splits and resume_splits[text_hash] != row["split"]:
            raise ValueError("Identical normalized resume leaks across splits")
        resume_splits[text_hash] = row["split"]
        interval = row.get("score_interval")
        if interval is not None and (
            not isinstance(interval, list) or len(interval) != 2
            or any(type(n) is not int for n in interval)
            or not 0 <= interval[0] <= interval[1] <= 100
        ):
            raise ValueError(f"Invalid score_interval in dataset row {index}")
        evidence = row.get("gold_evidence", [])
        if not isinstance(evidence, list) or any(not valid_span(span, row["resume_text"]) for span in evidence):
            raise ValueError(f"Invalid gold evidence in dataset row {index}")
    if not rows:
        raise ValueError("Dataset is empty")
    return rows


def _job_manifest(job_id: str) -> tuple[dict, dict]:
    job_path = ROOT / "config" / "job_profiles" / JOB_FILES[job_id]
    profile = load_job_profile(job_path)
    prompt_path = (ROOT / str(profile["analysis_prompt_path"])).resolve()
    if not prompt_path.is_relative_to((ROOT / "config" / "prompts").resolve()):
        raise ValueError("Prompt must belong to the repository prompt directory")
    return profile, {
        "job_profile_hash": _digest(job_path.read_bytes()),
        "prompt_hash": _digest(prompt_path.read_bytes()),
    }


def _source_manifest() -> dict:
    try:
        commit = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, check=True
        ).stdout.decode().strip()
        diff = subprocess.run(
            ["git", "diff", "--binary", "HEAD"], cwd=ROOT, capture_output=True, check=True
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        commit, diff = None, b""
    # Also hash untracked evaluation/implementation files; never include .env or reports.
    source_hashes = {}
    for directory in ("eval", "src", "apps", "config"):
        for path in sorted((ROOT / directory).rglob("*")):
            if path.suffix in {".py", ".yaml", ".txt"} and path.is_file():
                source_hashes[path.relative_to(ROOT).as_posix()] = _digest(path.read_bytes())
    return {
        "git_commit": commit, "dirty_diff_hash": _digest(diff),
        "source_tree_hash": _digest(json.dumps(source_hashes, sort_keys=True).encode()),
    }


def _provenance_status(prediction: dict, row: dict, profile: dict, rows_hash: str) -> str:
    identity = prediction.get("inference")
    if identity is None:
        return "unverified_recording"
    if not isinstance(identity, dict):
        return "provenance_mismatch"
    try:
        expected = pipeline_manifest(
            job_profile=profile,
            system_prompt=(ROOT / profile["analysis_prompt_path"]).read_text(encoding="utf-8"),
            provider=identity["provider"], model=identity["model"],
            decoding_params=identity["decoding_params"],
            contract_version=content_hash((ROOT / "src/liepin_rpa/analysis_contract.py").read_text(encoding="utf-8")),
            rules_version=content_hash((ROOT / "src/liepin_rpa/reporting_rules.py").read_text(encoding="utf-8")),
            retrieval_version=identity["retrieval_version"], tool_version=identity["tool_version"],
        )
    except (ValueError, TypeError, KeyError):
        return "provenance_mismatch"
    if identity != expected:
        return "provenance_mismatch"
    if prediction.get("resume_hash") != content_hash(row["resume_text"]) or prediction.get("dataset_hash") != rows_hash:
        return "provenance_mismatch"
    if prediction.get("simulated") is True and row["source_type"] == "synthetic":
        return "self_consistent_synthetic_recording"
    # Matching hashes establish consistency, not authenticated inference provenance.
    return "self_consistent_unattested_recording"


def evaluate(dataset_path: Path, predictions_path: Path, *, split: str = "dev") -> dict:
    if split not in {"dev", "test"}:
        raise ValueError("Split must be dev or test")
    all_rows = load_dataset(dataset_path)
    predictions = {}
    known_ids = {row["sample_id"] for row in all_rows}
    for row in _jsonl(predictions_path):
        sample_id = row.get("sample_id")
        if not isinstance(sample_id, str) or sample_id not in known_ids:
            raise ValueError("Prediction has an unknown sample_id")
        if sample_id in predictions:
            raise ValueError("Duplicate prediction sample_id")
        predictions[sample_id] = row
    selected = [row for row in all_rows if row["split"] == split]
    if not selected:
        raise ValueError("Requested split has no samples")
    cases, jobs, provenance_counts, evidence_predictions = [], {}, Counter(), {}
    rows_hash = content_hash(all_rows)
    for row in selected:
        job_id = row["job_profile_id"]
        profile, job_version = _job_manifest(job_id)
        jobs[job_id] = job_version
        prediction = predictions.get(row["sample_id"])
        state, payload = "missing_prediction", {}
        provenance = "missing_prediction"
        if prediction is not None:
            provenance = _provenance_status(prediction, row, profile, rows_hash)
            declared_state = prediction.get("execution_status", "succeeded")
            if isinstance(declared_state, str) and not has_analysis_execution_failure(execution_status=declared_state):
                raw = prediction.get("raw_response")
                payload = parse_analysis_payload(raw) if isinstance(raw, str) else prediction.get("payload", {})
                if not isinstance(payload, dict):
                    payload = {}
                errors = validate_analysis_payload(
                    payload, contract=analysis_contract_from_job_profile(profile)
                )
                state = "invalid_output" if errors else "succeeded"
            else:
                state = "execution_error"
            if provenance == "provenance_mismatch":
                state = "provenance_mismatch"
            if state == "succeeded":
                evidence_predictions[row["sample_id"]] = prediction
        provenance_counts[provenance] += 1
        predicted = payload["match_result"] if state == "succeeded" else "needs_review"
        case = {
            "sample_id": row["sample_id"], "group_id": row["group_id"],
            "job_profile_id": job_id, "source_type": row["source_type"],
            "gold": row["gold"], "predicted": predicted, "execution_status": state,
            "inference_provenance": provenance,
        }
        interval = row.get("score_interval")
        if state == "succeeded" and interval is not None:
            score = payload["match_score"]
            case["score_interval_error"] = max(interval[0] - score, score - interval[1], 0)
        cases.append(case)
    metrics = classification_metrics(cases)
    by_job = {
        job: classification_metrics([c for c in cases if c["job_profile_id"] == job])
        for job in jobs
    }
    by_source = {
        source: classification_metrics([c for c in cases if c["source_type"] == source])
        for source in sorted({c["source_type"] for c in cases})
    }
    errors = [case["score_interval_error"] for case in cases if "score_interval_error" in case]
    manifest = {
        "mode": "recorded_response_replay", "split": split,
        "fingerprint_scope": "evaluation_implementation_and_input_files",
        "inference_provenance": dict(provenance_counts),
        "dataset_hash": _digest(dataset_path.read_bytes()),
        "predictions_hash": _digest(predictions_path.read_bytes()),
        "jobs": jobs, **_source_manifest(),
        "contract_hash": _digest((ROOT / "src/liepin_rpa/analysis_contract.py").read_bytes()),
        "rules_hash": _digest((ROOT / "src/liepin_rpa/reporting_rules.py").read_bytes()),
    }
    manifest["evaluation_fingerprint"] = _digest(json.dumps(manifest, sort_keys=True).encode())
    return {
        "schema_version": 1, "manifest": manifest,
        "source_counts": dict(Counter(c["source_type"] for c in cases)),
        "quality_claim_allowed": False,
        "limitations": [
            "Replay metrics describe supplied responses, not a fresh model run.",
            "Human label quality, near-duplicate grouping and dataset authorization require review.",
            "Quote location checks do not establish semantic support; live cost/latency are not measured.",
        ],
        "metrics": metrics, "by_job": by_job, "by_source": by_source,
        "evidence": evidence_metrics(selected, evidence_predictions),
        "score_interval": {
            "evaluated_count": len(errors), "total_count": len(cases),
            "mean_error": sum(errors) / len(errors) if errors else None,
        },
        "cost": {"status": "unknown", "amount": None},
        "latency": {"status": "not_measured", "p50": None, "p95": None},
        # Public case report never contains resume text, provider text or raw errors.
        "cases": cases,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--split", choices=("dev", "test"), default="dev")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "artifacts/eval/runs")
    args = parser.parse_args(argv)
    try:
        result = evaluate(args.dataset, args.predictions, split=args.split)
    except (ValueError, OSError) as exc:
        # Do not echo raw input exceptions: they may contain resume/provider data.
        parser.exit(2, f"Evaluation input rejected ({type(exc).__name__}); check the dataset schema.\n")
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / f"{run_id}.json"
    result["run_id"] = run_id
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Replay evaluation: {len(result['cases'])} cases; no live calls; report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
