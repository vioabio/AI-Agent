"""Pinned project-local tools and isolated platform profiles, without application Settings."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
from pathlib import PurePosixPath
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]
PACKAGES = {"liepin": ("@viyzhu/liepin-cli", "0.2.10"), "boss": ("@viyzhu/boss-cli-fork", "0.8.7")}


class ToolchainError(RuntimeError):
    """Only sanitized operational codes, never upstream error bodies."""


def tool_entry(platform, root=ROOT):
    if platform not in PACKAGES:
        raise ToolchainError("unknown_platform")
    name, version = PACKAGES[platform]
    package = root / "tools/recruiting/node_modules" / name
    try:
        meta = json.loads((package / "package.json").read_text(encoding="utf-8"))
        entry = (package / meta["bin"][platform]).resolve()
    except (OSError, ValueError, KeyError, TypeError):
        raise ToolchainError("package_missing_or_invalid") from None
    if not isinstance(meta, dict) or meta.get("name") != name or meta.get("version") != version:
        raise ToolchainError("package_version_mismatch")
    if not entry.is_relative_to(package.resolve()) or not entry.is_file():
        raise ToolchainError("invalid_package_entry")
    return entry


def isolated_environment(platform, account, root=ROOT):
    if platform not in PACKAGES or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,31}", account):
        raise ToolchainError("invalid_platform_or_account")
    if account not in {"primary", "doctor"}:
        raise ToolchainError("account_port_not_provisioned")
    directory = root / "artifacts/recruiting/accounts" / platform / account
    directory.mkdir(parents=True, exist_ok=True)
    allowed = {"systemroot", "windir", "path", "pathext", "temp", "tmp", "comspec",
               "programfiles", "programfiles(x86)", "localappdata", "appdata"}
    env = {key: value for key, value in os.environ.items() if key.lower() in allowed}
    env.update(HOME=str(directory), USERPROFILE=str(directory), PYTHONIOENCODING="utf-8",
        RECRUIT_BROWSER_HIDDEN="false", BOSS_BROWSER_HEADLESS="false", LIEPIN_HEADLESS="false",
        BOSS_AGENT_SKILLS_DIR=str(directory / "agent-skills"),
        BOSS_BROWSER_USER_DATA_DIR=str(directory / "browser"),
        LIEPIN_USER_DATA_DIR=str(directory / "browser"),
        LIEPIN_CONFIG_DIR=str(directory / "config"), LIEPIN_SCREENSHOT_DIR=str(directory / "screenshots"))
    # This desktop's WMI browser spawn timed out without a visible window.
    # Direct spawn is appropriate for the operator's foreground login terminal.
    env.update(LIEPIN_SPAWN_BREAKAWAY="false", BOSS_SPAWN_BREAKAWAY="false")
    # Primary profiles use ports independent from upstream global/DSH profiles.
    # Additional accounts need explicit port allocation before they can be run.
    port = (19470 if platform == "boss" else 19471) + (10 if account == "doctor" else 0)
    env["BOSS_BROWSER_REMOTE_DEBUGGING_PORT" if platform == "boss" else "LIEPIN_BROWSER_REMOTE_DEBUGGING_PORT"] = str(port)
    if platform == "boss":
        cache = directory / "update-check-policy.json"
        # Suppress upstream's daily registry lookup during help/login; this is a
        # local policy cache, NOT a claim that the pinned release is npm latest.
        cache.write_text(json.dumps({"checkedAt": datetime.now(timezone.utc).isoformat(),
            "packageName": PACKAGES[platform][0], "currentVersion": PACKAGES[platform][1],
            "latestVersion": PACKAGES[platform][1]}), encoding="utf-8")
        env["BOSS_CLI_UPDATE_CHECK_STATE_PATH"] = str(cache)
    return directory, env


def load_copilot_lock(root=ROOT):
    lock = json.loads((root / "tools/recruiting/copilot-lock.json").read_text(encoding="utf-8"))
    if (not isinstance(lock, dict)
            or not isinstance(lock.get("commit"), str) or not re.fullmatch(r"[a-f0-9]{40}", lock["commit"])
            or not isinstance(lock.get("archive_sha256"), str) or not re.fullmatch(r"[a-f0-9]{64}", lock["archive_sha256"])
            or not isinstance(lock.get("tag"), str) or not re.fullmatch(r"v\d+\.\d+\.\d+", lock["tag"])
            or not isinstance(lock.get("files"), dict) or not 1 <= len(lock["files"]) <= 1024):
        raise ToolchainError("invalid_copilot_lock")
    for name, digest in lock["files"].items():
        if not isinstance(name, str) or not isinstance(digest, str):
            raise ToolchainError("invalid_copilot_lock")
        path = PurePosixPath(name)
        if (path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name
                or not re.fullmatch(r"[a-f0-9]{64}", digest)):
            raise ToolchainError("invalid_copilot_lock")
    return lock


def verify_copilot(root=ROOT):
    lock = load_copilot_lock(root)
    directory = root / "artifacts/recruiting/upstream" / lock["commit"]
    for name, expected in lock["files"].items():
        path = (directory / name).resolve()
        if not path.is_relative_to(directory.resolve()) or not path.is_file():
            raise ToolchainError("copilot_snapshot_missing")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ToolchainError("copilot_snapshot_changed")
    return {"tag": lock["tag"], "commit": lock["commit"], "files_verified": len(lock["files"])}


def doctor(root=ROOT):
    node = shutil.which("node")
    if not node:
        raise ToolchainError("node_missing")
    version = subprocess.run([node, "--version"], capture_output=True, text=True, timeout=10, check=True).stdout.strip()
    if not re.fullmatch(r"v\d+\.\d+\.\d+", version) or int(version[1:].split(".")[0]) < 22:
        raise ToolchainError("node_version_unsupported")
    result = {"mode": "local_help_and_snapshot_only", "node": version, "copilot": verify_copilot(root),
              "platforms": {}, "authenticated": "not_checked", "platform_calls": 0, "model_calls": 0}
    for platform, (name, expected_version) in PACKAGES.items():
        entry = tool_entry(platform, root)
        directory, env = isolated_environment(platform, "doctor", root)
        observed_home = subprocess.run([node, "-p", "require('node:os').homedir()"], cwd=directory, env=env,
            capture_output=True, text=True, timeout=10, check=True).stdout.strip()
        if Path(observed_home).resolve() != directory.resolve():
            raise ToolchainError("node_profile_isolation_failed")
        process = subprocess.run([node, str(entry), "--help"], cwd=directory, env=env,
            capture_output=True, timeout=30, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        output = process.stdout + process.stderr
        if process.returncode or not output or len(output) > 100000:
            raise ToolchainError("cli_help_failed")
        result["platforms"][platform] = {"package": name, "version": expected_version,
            "help_verified": True, "entry_sha256": hashlib.sha256(entry.read_bytes()).hexdigest(),
            "help_sha256": hashlib.sha256(output).hexdigest(), "account_capabilities": "not_checked"}
    return result
