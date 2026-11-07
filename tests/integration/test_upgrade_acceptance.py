import json
import hashlib

from scripts import run_upgrade_acceptance as acceptance


def test_acceptance_bundle_is_isolated_hashed_and_does_not_claim_release(tmp_path):
    directory = tmp_path / "acceptance"
    report = acceptance.assemble(directory)
    assert report["status"] == "passed" and not report["release_approved"]
    assert report["quality_claim_allowed"] is False and report["real_model_calls"] == 0
    for name, digest in report["entrypoint_hashes"].items():
        assert hashlib.sha256((acceptance.ROOT / name).read_bytes()).hexdigest() == digest
    for check in report["checks"]:
        if check["status"] == "passed":
            data = (directory / check["report"]).read_bytes()
            assert hashlib.sha256(data).hexdigest() == check["sha256"]
    assert report["checks"][-1]["status"] == "not_run"
    assert "pytest_suite" in report["not_run"] and "production_database" in report["not_run"]
    assert json.loads((directory / "manifest.json").read_text(encoding="utf-8"))["status"] == "passed"
    from scripts.verify_upgrade_acceptance import verify
    assert verify(directory / "manifest.json", check_source=True)["skipped_checks"] == ["sdk_loopback"]


def test_failed_check_is_recorded_and_cannot_be_misreported_as_pass(tmp_path, monkeypatch):
    def fail(_):
        raise RuntimeError("PRIVATE-RESUME-OR-PROVIDER-BODY")
    monkeypatch.setattr(acceptance, "check_shadow", fail)
    report = acceptance.assemble(tmp_path / "failed")
    assert report["status"] == "failed"
    assert next(check for check in report["checks"] if check["name"] == "shadow_api")["status"] == "failed"
    assert "PRIVATE-RESUME" not in json.dumps(report)
