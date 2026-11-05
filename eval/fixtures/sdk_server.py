"""Ephemeral numeric-loopback Chat Completions fixture; no real model inference."""
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from threading import Lock, Thread
import time

PAYLOAD = {"beauty_relevant": True, "match_result": "relevant", "match_score": 90,
           "score_breakdown": {"industry_depth": 30, "beauty_continuity": 20, "role_evidence": 40},
           "analysis_reason": "固定合成协议夹具，未调用真实模型", "evidence_summary": ["独立开发 RAG 服务。"]}


class SDKFixtureServer:
    def __init__(self):
        self.requests, self.counts, self.lock = [], Counter(), Lock()
        self.on_request = None
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_):
                pass  # Never log request bodies, credentials or error text.

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 1000000:
                    self.respond(413, {"error": {"message": "fixture request limit"}})
                    return
                body = json.loads(self.rfile.read(length))
                model, metadata = body.get("model"), body.get("metadata", {})
                job_id = metadata.get("queue_job", "unknown")
                with fixture.lock:
                    fixture.counts[job_id] += 1
                    number = fixture.counts[job_id]
                    fixture.requests.append({"job_id": job_id, "attempt": metadata.get("queue_attempt"), "model": model,
                        "max_output_tokens": body.get("max_completion_tokens"),
                        "custom_header_seen": "X-Fixture-Private" in self.headers})
                if fixture.on_request:
                    fixture.on_request(body)
                if self.path != "/v1/chat/completions" or self.headers.get("Authorization") != "Bearer synthetic-fixture-only":
                    self.respond(401, {"error": {"message": "fixture authentication"}})
                    return
                if model == "fixture-slow":
                    time.sleep(0.2)
                errors = {"fixture-auth": (401, {}), "fixture-quota": (429, {}),
                          "fixture-long-retry": (429, {"Retry-After": "7200"}),
                          "fixture-redirect": (302, {"Location": "http://192.0.2.1/never-follow"})}
                if model == "fixture-rate-once" and number == 1:
                    errors[model] = (429, {"Retry-After": "65"})
                if model == "fixture-503-once" and number == 1:
                    errors[model] = (503, {"Retry-After": "2"})
                if model in errors:
                    status, headers = errors[model]
                    self.respond(status, {"error": {"message": "PRIVATE-UPSTREAM-ERROR", "type": "fixture_error",
                        "code": "insufficient_quota" if model == "fixture-quota" else "fixture_status"}}, headers)
                    return
                usage = {"prompt_tokens": 321, "completion_tokens": 64, "total_tokens": 385}
                if model == "fixture-partial-usage":
                    usage = {"prompt_tokens": 10000}
                elif model == "fixture-negative-usage":
                    usage["prompt_tokens"] = -1
                elif model == "fixture-bad-usage-total":
                    usage["total_tokens"] = 999
                elif model == "fixture-overage":
                    usage = {"prompt_tokens": 10000, "completion_tokens": 64, "total_tokens": 10064}
                payload = {**PAYLOAD, "match_score": 1} if model == "fixture-bad-score" else PAYLOAD
                result = {"id": "fixture-response", "object": "chat.completion", "created": int(time.time()),
                    "model": "PRIVATE-OTHER-MODEL" if model == "fixture-model-mismatch" else model,
                    "choices": [{"index": 0, "message": {"role": "assistant",
                        "content": "not json" if model == "fixture-bad-json" else json.dumps(payload, ensure_ascii=False)},
                        "finish_reason": "length" if model == "fixture-length" else "stop"}], "usage": usage}
                if model == "fixture-refusal":
                    result["choices"][0]["message"].update(content=None, refusal="private fixture refusal")
                if model == "fixture-missing-usage":
                    result.pop("usage")
                self.respond(200, result, {"x-request-id": "PRIVATE-UPSTREAM-ID"})

            def respond(self, status, body, headers=None):
                encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(encoded)))
                    for key, value in (headers or {}).items():
                        self.send_header(key, value)
                    self.end_headers()
                    self.wfile.write(encoded)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # Expected for the timeout fixture.

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = Thread(target=lambda: self.server.serve_forever(poll_interval=0.05), daemon=True)
        self.base_url = f"http://127.0.0.1:{self.server.server_port}/v1/"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
