import hashlib
import json

import pytest

from scripts import verify_upgrade_acceptance as verification


@pytest.fixture
def bundle(tmp_path):
    """Small file bundle for corruption tests, not business acceptance evidence."""
    checks = []
    for name in verification.CHECKS:
        path = tmp_path / name / "report.json"
        path.parent.mkdir()
        path.write_bytes(b'{"quality_claim_allowed": false}\r\n')
        checks.append({"name": name, "status": "passed", "report": f"{name}/report.json",
                       "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    data = {"schema_version": 2, "scope": "isolated_synthetic_acceptance_only", "status": "passed",
            "quality_claim_allowed": False, "release_approved": False, "real_model_calls": 0,
            "checks": checks, "not_run": sorted(verification.NOT_RUN),
            "entrypoint_hashes": {name: "a" * 64 for name in (*verification.ENTRYPOINTS,
                                   "scripts/verify_upgrade_acceptance.py")},
            "source_manifest": {"source_tree_hash": "b" * 64, "dirty_diff_hash": "c" * 64}}
    path = tmp_path / "manifest.json"
    save(path, data)
    return path, data


def save(path, data):
    path.write_text(json.dumps(data), encoding="utf-8")


def test_report_hashes_use_actual_bytes_and_sdk_can_be_required(bundle):
    path, _ = bundle
    report = verification.verify(path, require_sdk=True)
    assert len(report["verified_checks"]) == 5 and report["skipped_checks"] == []
    assert report["quality_claim_allowed"] is False and report["release_approved"] is False
    assert verification.main([str(path), "--require-sdk"]) == 0


@pytest.mark.parametrize(("field", "value", "code"), [
    ("schema_version", True, "unsupported_schema"),
    ("schema_version", 99, "unsupported_schema"),
    ("scope", "production", "invalid_scope"),
    ("status", "failed", "acceptance_not_passed"),
    ("quality_claim_allowed", True, "invalid_claims"),
    ("release_approved", True, "invalid_claims"),
    ("real_model_calls", False, "invalid_model_claim"),
    ("real_model_calls", 1, "invalid_model_claim"),
    ("not_run", [], "missing_scope_exclusions"),
    ("entrypoint_hashes", {"../../private": "a" * 64}, "invalid_entrypoint_hashes"),
    ("source_manifest", {}, "invalid_source_manifest"),
])
def test_incorrect_scope_and_provenance_are_rejected(bundle, field, value, code):
    path, data = bundle
    data[field] = value
    save(path, data)
    with pytest.raises(verification.VerificationError, match=code):
        verification.verify(path)


@pytest.mark.parametrize("change", ["missing", "duplicate", "failed", "path", "digest"])
def test_missing_duplicate_failed_or_redirected_checks_fail(bundle, change):
    path, data = bundle
    if change == "missing":
        data["checks"].pop()
    elif change == "duplicate":
        data["checks"][-1] = dict(data["checks"][0])
    elif change == "failed":
        data["checks"][0]["status"] = "failed"
    elif change == "path":
        data["checks"][0]["report"] = "../outside.json"
    else:
        data["checks"][0]["sha256"] = "d" * 64
    save(path, data)
    with pytest.raises(verification.VerificationError):
        verification.verify(path)


def test_optional_sdk_is_explicit_and_required_mode_rejects_it(bundle):
    path, data = bundle
    data["checks"][-1] = {"name": "sdk_loopback", "status": "not_run", "reason": "enable_with_include_sdk"}
    save(path, data)
    assert verification.verify(path)["skipped_checks"] == ["sdk_loopback"]
    with pytest.raises(verification.VerificationError, match="required_check_not_run"):
        verification.verify(path, require_sdk=True)


@pytest.mark.parametrize("change", ["missing", "corrupt", "duplicate", "claim", "nan"])
def test_missing_or_changed_reports_fail_without_echoing_their_contents(bundle, change, capsys):
    path, data = bundle
    report = path.parent / "retrieval/report.json"
    if change == "missing":
        report.unlink()
    else:
        content = {"corrupt": 'PRIVATE-RESUME',
                   "duplicate": '{"quality_claim_allowed":false,"quality_claim_allowed":false}',
                   "claim": '{"quality_claim_allowed":true}',
                   "nan": '{"quality_claim_allowed":false,"secret":NaN}'}[change]
        report.write_text(content, encoding="utf-8")
        # Even a consistent re-hash cannot legitimize invalid JSON/claims.
        data["checks"][0]["sha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
        save(path, data)
    assert verification.main([str(path)]) == 1
    assert "PRIVATE-RESUME" not in capsys.readouterr().out


def test_source_comparison_is_explicit_and_checks_tree_and_entrypoints(bundle, monkeypatch, tmp_path):
    path, data = bundle
    monkeypatch.setattr(verification, "ROOT", tmp_path / "source")
    for name in data["entrypoint_hashes"]:
        source = verification.ROOT / name
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes(b"local-source")
        data["entrypoint_hashes"][name] = hashlib.sha256(source.read_bytes()).hexdigest()
    from eval import run_eval
    monkeypatch.setattr(run_eval, "_source_manifest", lambda: {"source_tree_hash": "b" * 64})
    save(path, data)
    assert verification.verify(path, check_source=True)["source_checked"] is True
    monkeypatch.setattr(run_eval, "_source_manifest", lambda: {"source_tree_hash": "d" * 64})
    with pytest.raises(verification.VerificationError, match="source_tree_mismatch"):
        verification.verify(path, check_source=True)
    (verification.ROOT / verification.ENTRYPOINTS[0]).write_bytes(b"modified")
    with pytest.raises(verification.VerificationError, match="entrypoint_source_mismatch"):
        verification.verify(path, check_source=True)
    assert verification.verify(path)["source_checked"] is False


def test_schema_one_can_verify_archived_bundles_without_new_entrypoint(bundle):
    path, data = bundle
    data["schema_version"] = 1
    data["entrypoint_hashes"].pop("scripts/verify_upgrade_acceptance.py")
    save(path, data)
    assert verification.verify(path)["status"] == "verified"
