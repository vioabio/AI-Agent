"""Start the synthetic integrated demo in a fresh process and verify real HTTP endpoints."""
import argparse
import json
import hashlib
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import traceback
import uuid

import httpx

ROOT = Path(__file__).resolve().parents[1]


def _publish_port(directory, port):
    # A reader must never observe an existing but still empty/partial port file.
    temporary = directory / "port.tmp"
    temporary.write_text(str(port), encoding="ascii")
    temporary.replace(directory / "port.txt")


def serve(directory):
    import uvicorn
    from scripts.run_integrated_demo import build_integrated_demo_app
    app = build_integrated_demo_app(demo_dir=directory / "demo")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        _publish_port(directory, listener.getsockname()[1])
        uvicorn.Server(uvicorn.Config(app, log_level="warning")).run(sockets=[listener])


def exercise(directory):
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=False)
    # Child receives no inherited application credentials, provider URLs or proxy settings.
    allowed = {"systemroot", "windir", "path", "pathext", "temp", "tmp", "home", "userprofile", "lang"}
    environment = {key: value for key, value in os.environ.items() if key.lower() in allowed}
    environment["PYTHONIOENCODING"] = "utf-8"
    report = {"mode": "fresh_process_synthetic_http_only", "real_model_calls": 0,
        "production_database_connected": False, "quality_claim_allowed": False, "release_approved": False}
    with (directory / "server.log").open("w", encoding="utf-8") as log:
        child = subprocess.Popen([sys.executable, "-m", "eval.clean_environment_acceptance", "--serve",
            "--directory", str(directory)], cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 120
            while not (directory / "port.txt").exists():
                if child.poll() is not None:
                    raise RuntimeError("Synthetic demo process failed at startup")
                if time.monotonic() >= deadline:
                    raise TimeoutError("Synthetic demo startup timed out")
                time.sleep(0.1)
            port = int((directory / "port.txt").read_text(encoding="ascii"))
            with httpx.Client(base_url=f"http://127.0.0.1:{port}", trust_env=False, timeout=20) as client:
                while True:
                    try:
                        ready = client.get("/openapi.json")
                        ready.raise_for_status()
                        break
                    except httpx.TransportError:
                        if child.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError("Synthetic demo did not become HTTP ready") from None
                        time.sleep(0.1)
                assert client.get("/api/v1/me").status_code == 401
                assert client.post("/api/v1/auth/login", json={"username": "demo", "password": "DemoReview2026!"}).status_code == 200
                assert client.get("/api/v1/me").status_code == 200
                queue = client.get("/api/v1/review-queue")
                assert queue.status_code == 200 and queue.json()["items"]
                response = client.get("/api/v1/analysis-tasks")
                response.raise_for_status()
                task = next(item for item in response.json()["items"]
                            if item["candidate_fingerprint"] == "remaining-browser-person")
                response = client.get(f"/api/v1/analysis-tasks/{task['job_id']}")
                response.raise_for_status()
                rounds = response.json()["execution"]["tool_rounds"]
                assert len(rounds) == 2 and all(item["state"] == "received" for item in rounds)
                detail = client.get("/api/v1/candidates/remaining-browser-person")
                detail.raise_for_status()
                from eval.remaining_acceptance import QUOTE
                assert detail.json()["evidence_refs"][0]["quote"] == QUOTE
                assert detail.json()["evidence_status"] == "verified"
                assert client.post("/api/v1/auth/logout", headers={"X-CSRF-Token": client.cookies["hr_ai_demo_csrf"]}).status_code == 200
                assert client.get("/api/v1/me").status_code == 401
                report.update(status="passed", login_and_logout_verified=True, review_queue_nonempty=True,
                    durable_tool_rounds=2, hr_projection_and_evidence_verified=True, app_credentials_inherited=False,
                    python_version=sys.version.split()[0])
        finally:
            child.terminate()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=10)
            report["server_stopped"] = child.poll() is not None
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serve", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--directory", type=Path)
    args = parser.parse_args()
    if args.serve:
        serve(args.directory)
        return 0
    directory = args.directory or ROOT / "artifacts/environment-acceptance" / f"clean-{uuid.uuid4().hex[:12]}"
    try:
        report = exercise(directory)
    except Exception as error:
        report = {"status": "failed", "error_type": type(error).__name__, "release_approved": False,
            "failure_frames": [{"file": Path(frame.filename).name, "line": frame.lineno, "function": frame.name}
                               for frame in traceback.extract_tb(error.__traceback__)]}
    report["harness_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    (directory / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Fresh process acceptance {report['status']}: {directory / 'report.json'}")
    return int(report["status"] != "passed")


if __name__ == "__main__":
    raise SystemExit(main())
