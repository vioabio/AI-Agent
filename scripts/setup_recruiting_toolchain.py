"""Install/verify project-local pinned recruitment dependencies; never logs into platforms."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

from liepin_rpa.sourcing.toolchain import ROOT, ToolchainError, doctor, verify_copilot, load_copilot_lock


def restore_copilot(root=ROOT):
    lock = load_copilot_lock(root)
    directory = root / "artifacts/recruiting/upstream" / lock["commit"]
    if directory.exists():
        return verify_copilot(root)
    request = urllib.request.Request("https://codeload.github.com/Viy1204/recruiting-copilot/zip/" + lock["commit"],
                                     headers={"User-Agent": "AI-HR-pinned-toolchain"})
    with urllib.request.urlopen(request, timeout=60) as response:
        archive = response.read(20_000_001)
    if len(archive) > 20_000_000 or hashlib.sha256(archive).hexdigest() != lock["archive_sha256"]:
        raise ToolchainError("upstream_archive_mismatch")
    files = {}
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        for entry in source.infolist():
            if entry.is_dir():
                continue
            parts = PurePosixPath(entry.filename).parts
            if len(parts) < 2 or ".." in parts or "\\" in entry.filename or ":" in entry.filename:
                raise ToolchainError("unsafe_archive_path")
            name = "/".join(parts[1:])
            if name not in lock["files"] or name in files or entry.file_size > 5_000_000:
                raise ToolchainError("unexpected_archive_entry")
            files[name] = source.read(entry)
            if hashlib.sha256(files[name]).hexdigest() != lock["files"][name]:
                raise ToolchainError("upstream_file_mismatch")
    if set(files) != set(lock["files"]):
        raise ToolchainError("incomplete_upstream_archive")
    directory.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="copilot-restore-", dir=directory.parent) as staging:
        snapshot = Path(staging) / "snapshot"
        for name, body in files.items():
            target = snapshot / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
        snapshot.rename(directory)
    return verify_copilot(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true", help="Explicit npm ci and verified upstream restore")
    args = parser.parse_args()
    try:
        if args.install:
            npm = shutil.which("npm.cmd") or shutil.which("npm")
            if not npm:
                raise ToolchainError("npm_missing")
            subprocess.run([npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund"],
                           cwd=ROOT / "tools/recruiting", check=True, timeout=300)
            restore_copilot()
        report = doctor()
        report["status"] = "passed"
    except (ToolchainError, OSError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        report = {"status": "failed", "error_code": str(error) if isinstance(error, ToolchainError) else type(error).__name__}
    directory = ROOT / "artifacts/recruiting"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "toolchain-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return int(report["status"] != "passed")


if __name__ == "__main__":
    raise SystemExit(main())
