"""Verify a local synthetic acceptance bundle without replaying its checks."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CHECKS = ("retrieval", "queue", "tools", "shadow_api", "sdk_loopback")
ENTRYPOINTS = ("scripts/run_upgrade_acceptance.py", "scripts/run_hr_demo.py")
NOT_RUN = {"real_provider_quality", "production_database", "mysql_concurrency", "device_actions",
           "fresh_machine_install", "remote_ci", "browser_and_frontend_checks", "pytest_suite"}


class VerificationError(ValueError):
    """Only stable diagnostic codes, never JSON contents or exception bodies."""


def require(condition, code):
    if not condition:
        raise VerificationError(code)


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate_json_key")
        result[key] = value
    return result


def read_json(path):
    try:
        with path.open("rb") as stream:
            data = stream.read(5_000_001)
        require(len(data) <= 5_000_000, "json_too_large")
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                           parse_constant=lambda _: require(False, "invalid_json_number"))
    except (OSError, UnicodeError, json.JSONDecodeError, RecursionError):
        raise VerificationError("unreadable_json") from None
    require(isinstance(value, dict), "invalid_json_object")
    return value, data


def _digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def verify(manifest_path, *, require_sdk=False, check_source=False):
    try:
        manifest_path = Path(manifest_path).resolve()
    except (OSError, RuntimeError):
        raise VerificationError("invalid_manifest_path") from None
    manifest, _ = read_json(manifest_path)
    require(type(manifest.get("schema_version")) is int and manifest["schema_version"] in (1, 2), "unsupported_schema")
    require(manifest.get("scope") == "isolated_synthetic_acceptance_only", "invalid_scope")
    require(manifest.get("quality_claim_allowed") is False and manifest.get("release_approved") is False,
            "invalid_claims")
    require(type(manifest.get("real_model_calls")) is int and manifest["real_model_calls"] == 0, "invalid_model_claim")
    require(manifest.get("status") == "passed", "acceptance_not_passed")
    not_run = manifest.get("not_run")
    require(isinstance(not_run, list) and all(isinstance(name, str) for name in not_run)
            and len(not_run) == len(set(not_run)) and NOT_RUN.issubset(not_run), "missing_scope_exclusions")
    checks = manifest.get("checks")
    require(isinstance(checks, list) and len(checks) == len(CHECKS), "incomplete_checks")
    require(all(isinstance(check, dict) for check in checks), "invalid_check")
    names = [check.get("name") for check in checks]
    require(all(isinstance(name, str) for name in names) and set(names) == set(CHECKS), "invalid_check_names")
    verified, skipped = [], []
    for check in checks:
        name = check["name"]
        if check.get("status") == "not_run":
            require(name == "sdk_loopback" and not require_sdk
                    and check.get("reason") == "enable_with_include_sdk", "required_check_not_run")
            skipped.append(name)
            continue
        require(check.get("status") == "passed", "check_not_passed")
        # Fixed allowlisted paths; never open arbitrary manifest-supplied file paths.
        relative = f"{name}/report.json"
        require(check.get("report") == relative, "invalid_report_path")
        try:
            path = (manifest_path.parent / relative).resolve()
        except (OSError, RuntimeError):
            raise VerificationError("invalid_report_path") from None
        require(path.is_relative_to(manifest_path.parent), "report_outside_bundle")
        require(_digest(check.get("sha256")), "invalid_report_digest")
        report, data = read_json(path)
        require(hashlib.sha256(data).hexdigest() == check["sha256"], "report_digest_mismatch")
        require(report.get("quality_claim_allowed") is False, "invalid_report_claims")
        verified.append(name)
    entries = manifest.get("entrypoint_hashes")
    expected_entries = set(ENTRYPOINTS)
    if manifest["schema_version"] == 2:
        expected_entries.add("scripts/verify_upgrade_acceptance.py")
    require(isinstance(entries, dict) and set(entries) == expected_entries
            and all(_digest(value) for value in entries.values()), "invalid_entrypoint_hashes")
    source = manifest.get("source_manifest")
    require(isinstance(source, dict) and _digest(source.get("source_tree_hash"))
            and _digest(source.get("dirty_diff_hash")), "invalid_source_manifest")
    if check_source:
        for name, digest in entries.items():
            try:
                actual = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
            except OSError:
                raise VerificationError("source_unreadable") from None
            require(actual == digest, "entrypoint_source_mismatch")
        from eval.run_eval import _source_manifest
        require(_source_manifest()["source_tree_hash"] == source["source_tree_hash"], "source_tree_mismatch")
    return {"status": "verified", "verified_checks": verified, "skipped_checks": skipped,
            "source_checked": check_source, "release_approved": False, "quality_claim_allowed": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--require-sdk", action="store_true")
    parser.add_argument("--check-source", action="store_true")
    args = parser.parse_args(argv)
    try:
        report = verify(args.manifest, require_sdk=args.require_sdk, check_source=args.check_source)
    except VerificationError as error:
        print(f"Acceptance verification failed: {error}")
        return 1
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
