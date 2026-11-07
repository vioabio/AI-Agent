"""Open a project-isolated recruiter login window; never searches, reads resumes or sends messages."""
import argparse
from datetime import datetime, timezone
import json
import os
import shutil
import socket
import subprocess

from liepin_rpa.sourcing.toolchain import ROOT, ToolchainError, isolated_environment, tool_entry


def native_browser_environment():
    keys = {"systemroot", "windir", "path", "pathext", "temp", "tmp", "comspec", "userprofile", "home",
        "programfiles", "programfiles(x86)", "localappdata", "appdata", "systemdrive", "homedrive", "homepath",
        "username", "userdomain", "userdomain_roamingprofile", "public", "allusersprofile", "programdata",
        "programw6432", "commonprogramfiles", "commonprogramfiles(x86)", "commonprogramw6432", "os",
        "processor_architecture", "processor_identifier", "number_of_processors", "computername"}
    return {key: value for key, value in os.environ.items() if key.lower() in keys}


def login(platform):
    node = shutil.which("node")
    if not node:
        raise ToolchainError("node_missing")
    tool_entry(platform)
    directory, env = isolated_environment(platform, "primary")
    port = int(env["BOSS_BROWSER_REMOTE_DEBUGGING_PORT" if platform == "boss" else "LIEPIN_BROWSER_REMOTE_DEBUGGING_PORT"])
    if os.name != "nt":
        raise ToolchainError("visible_login_launcher_currently_windows_only")
    # Browser receives the real Windows profile environment; the upstream Node
    # process alone receives the isolated HOME, avoiding this machine's failed
    # upstream WMI/direct-spawn paths without modifying vendor code.
    from pathlib import Path
    import time
    browser = next((path for path in [Path(os.environ.get("PROGRAMFILES", "C:/Program Files")) / "Google/Chrome/Application/chrome.exe",
        Path(os.environ.get("PROGRAMFILES(X86)", "C:/Program Files (x86)")) / "Microsoft/Edge/Application/msedge.exe"] if path.is_file()), None)
    if browser is None:
        raise ToolchainError("desktop_browser_missing")
    browser_env = native_browser_environment()
    powershell = shutil.which("pwsh") or shutil.which("powershell")
    if not powershell:
        raise ToolchainError("powershell_missing")
    start_url = "https://lpt.liepin.com/" if platform == "liepin" else "https://www.zhipin.com/"
    opened = subprocess.run([powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
        str(ROOT / "scripts/open_recruiting_browser.ps1"), "-BrowserPath", str(browser),
        "-ProfileDirectory", str(directory / "browser"), "-DebugPort", str(port), "-StartUrl", start_url],
        cwd=directory, env=browser_env, capture_output=True, timeout=20,
        creationflags=subprocess.CREATE_NO_WINDOW)
    if opened.returncode:
        raise ToolchainError("owned_browser_launch_or_verification_failed")
    ready = False
    for _ in range(30):
        with socket.socket() as probe:
            probe.settimeout(.2)
            ready = probe.connect_ex(("127.0.0.1", port)) == 0
        if ready:
            break
        time.sleep(.5)
    if not ready:
        raise ToolchainError("visible_browser_port_not_ready")
    # Do not ask the CLI to navigate, relogin or verify an account automatically.
    # A browser that is ready is distinct from an authenticated recruiter.
    report = {"platform": platform, "account_alias": "primary", "state": "awaiting_manual_verification",
        "browser_state": json.loads(opened.stdout.decode("utf-8-sig"))["state"], "manual_login_url": start_url,
        "reported_at": datetime.now(timezone.utc).isoformat(), "account_capabilities": "not_verified",
        "note": "Browser readiness is not proof of login or resume permissions", "resumes_collected": 0, "messages_sent": 0}
    (directory / "login-status.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True, choices=("liepin", "boss"))
    args = parser.parse_args()
    try:
        report = login(args.platform)
    except (ToolchainError, OSError, ValueError, subprocess.SubprocessError) as error:
        report = {"state": "failed", "error_code": str(error) if isinstance(error, ToolchainError) else type(error).__name__}
    print(json.dumps(report, indent=2))
    return 0 if report["state"] == "awaiting_manual_verification" else 1


if __name__ == "__main__":
    raise SystemExit(main())
