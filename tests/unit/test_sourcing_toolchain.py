import hashlib
import json
from pathlib import Path

import pytest

from liepin_rpa.sourcing.toolchain import PACKAGES, ToolchainError, isolated_environment, tool_entry, verify_copilot


def test_platform_process_receives_no_model_database_or_inherited_browser_secrets(tmp_path, monkeypatch):
    for key in ("LLM_API_KEY", "MYSQL_PASSWORD", "HR_REVIEW_DATABASE_URL", "GITHUB_TOKEN", "NODE_OPTIONS",
                "BOSS_BROWSER_USER_DATA_DIR", "LIEPIN_USER_DATA_DIR", "HTTP_PROXY"):
        monkeypatch.setenv(key, "must-not-be-inherited")
    for platform in PACKAGES:
        directory, env = isolated_environment(platform, "primary", tmp_path)
        assert all(value != "must-not-be-inherited" for value in env.values())
        assert env["USERPROFILE"] == env["HOME"] == str(directory)
        assert env["BOSS_BROWSER_USER_DATA_DIR"] == env["LIEPIN_USER_DATA_DIR"] == str(directory / "browser")
        assert env["RECRUIT_BROWSER_HIDDEN"] == "false"
    assert isolated_environment("boss", "primary", tmp_path)[1]["BOSS_BROWSER_REMOTE_DEBUGGING_PORT"] != isolated_environment(
        "liepin", "primary", tmp_path)[1]["LIEPIN_BROWSER_REMOTE_DEBUGGING_PORT"]


@pytest.mark.parametrize("platform,account", [("other", "primary"), ("boss", "../outside"), ("liepin", "a/b"), ("boss", "second")])
def test_unknown_platform_account_or_unallocated_port_is_rejected(tmp_path, platform, account):
    with pytest.raises(ToolchainError):
        isolated_environment(platform, account, tmp_path)


def test_cli_version_and_entry_path_must_match_pin_before_execution(tmp_path):
    package = tmp_path / "tools/recruiting/node_modules/@viyzhu/liepin-cli"
    package.mkdir(parents=True)
    (package / "index.js").write_text("unexecuted", encoding="utf-8")
    metadata = {"name": "@viyzhu/liepin-cli", "version": "0.2.10", "bin": {"liepin": "index.js"}}
    (package / "package.json").write_text(json.dumps(metadata))
    assert tool_entry("liepin", tmp_path) == package / "index.js"
    for change in ({"version": "99.0.0"}, {"bin": {"liepin": "../../outside.js"}}):
        (package / "package.json").write_text(json.dumps({**metadata, **change}))
        with pytest.raises(ToolchainError):
            tool_entry("liepin", tmp_path)


def test_modified_copilot_source_cannot_silently_enter_workspace(tmp_path):
    directory = tmp_path / "artifacts/recruiting/upstream" / ("a" * 40)
    directory.mkdir(parents=True)
    source = directory / "README.md"
    source.write_bytes(b"pinned-source")
    lock = {"tag": "v0.8.3", "commit": "a" * 40, "archive_sha256": "0" * 64,
        "files": {"README.md": hashlib.sha256(source.read_bytes()).hexdigest()}}
    lock_dir = tmp_path / "tools/recruiting"
    lock_dir.mkdir(parents=True)
    (lock_dir / "copilot-lock.json").write_text(json.dumps(lock))
    assert verify_copilot(tmp_path)["files_verified"] == 1
    source.write_bytes(b"changed-instructions")
    with pytest.raises(ToolchainError, match="snapshot_changed"):
        verify_copilot(tmp_path)


def test_visible_browser_keeps_real_windows_environment_without_application_credentials(monkeypatch):
    from scripts.recruiting_login import native_browser_environment
    monkeypatch.setenv("USERPROFILE", "C:/Users/operator")
    monkeypatch.setenv("SystemDrive", "C:")
    monkeypatch.setenv("LLM_API_KEY", "private-model-key")
    monkeypatch.setenv("MYSQL_PASSWORD", "private-db-key")
    monkeypatch.setenv("NODE_OPTIONS", "untrusted-preload")
    env = native_browser_environment()
    normalized = {key.lower(): value for key, value in env.items()}
    assert normalized["userprofile"] == "C:/Users/operator" and normalized["systemdrive"] == "C:"
    assert not {"LLM_API_KEY", "MYSQL_PASSWORD", "NODE_OPTIONS"} & env.keys()


@pytest.mark.parametrize("bad_path", ["../outside", "/outside", "C:/outside", "..\\outside"])
def test_upstream_lock_rejects_paths_outside_snapshot(tmp_path, bad_path):
    folder = tmp_path / "tools/recruiting"
    folder.mkdir(parents=True)
    (folder / "copilot-lock.json").write_text(json.dumps({"tag": "v0.8.3", "commit": "a" * 40,
        "archive_sha256": "0" * 64, "files": {bad_path: "0" * 64}}))
    with pytest.raises(ToolchainError, match="invalid_copilot_lock"):
        verify_copilot(tmp_path)
