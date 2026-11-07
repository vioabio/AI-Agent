"""Assemble isolated synthetic acceptance evidence; no production release claim."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import uuid

from fastapi.testclient import TestClient

from eval.run_eval import _source_manifest

ROOT = Path(__file__).resolve().parents[1]


def require(condition):
    if not condition:
        raise ValueError("Acceptance invariant failed")


def check_shadow(directory):
    from scripts.run_hr_demo import build_demo_app
    app = build_demo_app(include_shadow=True, demo_dir=directory)
    engine = app.state.services.candidates.query_service.engine
    try:
        with TestClient(app) as client:
            require(client.get("/api/v1/analysis-tasks").status_code == 401)
            require(client.post("/api/v1/auth/login", json={"username": "demo", "password": "DemoReview2026!"}).status_code == 200)
            original = client.get("/api/v1/candidates/demo-expanded-005").json()
            tasks = client.get("/api/v1/analysis-tasks").json()
            require(tasks["enabled"] and tasks["total"] == 7)
            require(all(row["source_type"] == "synthetic" for row in tasks["items"]))
            ai = next(row for row in tasks["items"] if row["candidate_fingerprint"] == "shadow-ai")
            detail = client.get(f"/api/v1/analysis-tasks/{ai['job_id']}").json()
            require(detail["result_status"] == "verified")
            require(detail["execution"]["mode"] == "synthetic_only")
            require(len(detail["execution"]["attempts"]) == ai["attempts"])
            require(detail["execution"]["attempts"][0]["request"] is None)
            for ref in detail["evidence_refs"]:
                require(detail["snapshot"]["raw_text"][ref["start"]:ref["end"]] == ref["quote"])
            require(bool(detail["evidence_refs"]))
            retry = next(row for row in tasks["items"] if row["can_retry"])
            url = f"/api/v1/analysis-tasks/{retry['job_id']}/retry"
            require(client.post(url, json={"lease_token": retry["lease_token"]}).status_code == 403)
            response = client.post(url, json={"lease_token": retry["lease_token"]},
                                   headers={"X-CSRF-Token": client.cookies["hr_demo_csrf"]})
            require(response.status_code == 200)
            require(response.json()["task"]["attempts"] == retry["attempts"])
            require(original == client.get("/api/v1/candidates/demo-expanded-005").json())
            require(client.post("/api/v1/auth/logout", headers={"X-CSRF-Token": client.cookies["hr_demo_csrf"]}).status_code == 200)
        return {"mode": "synthetic_api_integration", "quality_claim_allowed": False, "task_count": 7,
            "exact_quotes_verified": True, "csrf_verified": True, "legacy_detail_unchanged": True,
            "execution_history_verified": True,
            "schema_creation": "fresh_metadata_not_migration_test", "review_submitted": False, "real_model_calls": 0}
    finally:
        engine.dispose()


def assemble(directory, *, include_sdk=False):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    checks = []

    def execute(name, callback):
        check_dir = directory / name
        check_dir.mkdir()
        try:
            report = callback(check_dir)
            require(report.get("quality_claim_allowed") is False)
            encoded = json.dumps(report, ensure_ascii=False, indent=2)
            path = check_dir / "report.json"
            path.write_text(encoded, encoding="utf-8")
            checks.append({"name": name, "status": "passed", "report": f"{name}/report.json",
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        except Exception as error:
            checks.append({"name": name, "status": "failed", "error_type": type(error).__name__})

    def retrieval(path):
        from eval.retrieval_benchmark import benchmark, generate_fixtures
        dataset = path / "synthetic.jsonl"
        generate_fixtures(dataset)
        dev, test = benchmark(dataset, split="dev"), benchmark(dataset, split="test")
        require(dev["sample_count"] == 12 and test["sample_count"] == 6)
        require(dev["manifest"]["dataset_hash"] == test["manifest"]["dataset_hash"])
        return {"mode": "synthetic_retrieval", "quality_claim_allowed": False, "dev": dev, "test": test}

    def queue(path):
        from eval.queue_demo import simulate
        report = simulate(path)
        require(report["stale_lease_rejected"] and report["old_result_cannot_replace_new"])
        return report

    def tools(_):
        from eval.bounded_tools_demo import simulate
        report = simulate()
        require(report["observed_scripted_calls"] == report["reserved_model_calls"])
        require(report["baseline_comparison"]["fixed_outputs_equal"])
        return report

    def sdk(path):
        from eval.sdk_queue_demo import simulate
        report = simulate(path)
        require(report["observed_http_requests"] == report["request_receipts"] == report["reserved_attempts"])
        return report

    execute("retrieval", retrieval)
    execute("queue", queue)
    execute("tools", tools)
    execute("shadow_api", lambda path: check_shadow(path / "demo"))
    if include_sdk:
        execute("sdk_loopback", sdk)
    else:
        checks.append({"name": "sdk_loopback", "status": "not_run", "reason": "enable_with_include_sdk"})
    result = {"schema_version": 2, "scope": "isolated_synthetic_acceptance_only", "quality_claim_allowed": False,
        "status": "failed" if any(check["status"] == "failed" for check in checks) else "passed",
        "source_manifest": _source_manifest(),
        "entrypoint_hashes": {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
                              for name in ("scripts/run_upgrade_acceptance.py", "scripts/run_hr_demo.py",
                                           "scripts/verify_upgrade_acceptance.py")},
        "checks": checks,
        "not_run": ["real_provider_quality", "production_database", "mysql_concurrency", "device_actions",
                    "fresh_machine_install", "remote_ci", "browser_and_frontend_checks", "pytest_suite"],
        "release_approved": False, "real_model_calls": 0}
    (directory / "manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--include-sdk", action="store_true", help="Also exercise the real SDK against an ephemeral loopback fixture")
    parser.add_argument("--verify", action="store_true", help="Verify report hashes and current source after assembling the bundle")
    args = parser.parse_args(argv)
    run_id = "acceptance-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    directory = ROOT / "artifacts/acceptance" / run_id
    result = assemble(directory, include_sdk=args.include_sdk)
    print(f"Synthetic acceptance {result['status']}: {directory / 'manifest.json'}")
    if args.verify and result["status"] == "passed":
        from scripts.verify_upgrade_acceptance import VerificationError, verify
        try:
            verification = verify(directory / "manifest.json", require_sdk=args.include_sdk, check_source=True)
        except VerificationError as error:
            print(f"Acceptance verification failed: {error}")
            return 1
        print(json.dumps(verification, ensure_ascii=False))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
