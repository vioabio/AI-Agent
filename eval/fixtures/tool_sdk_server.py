"""Local HTTP function-calling fixture. All texts, usage and responses are synthetic."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
import json
import time


class ToolSDKFixtureServer:
    def __init__(self, *, payload, quote):
        self.requests = []
        fixture = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                if self.path != "/v1/chat/completions" or not 0 < length <= 1000000:
                    self.send_error(400)
                    return
                if self.headers.get("Authorization") != "Bearer synthetic-fixture-only":
                    self.send_error(401)
                    return
                body = json.loads(self.rfile.read(length))
                messages = body["messages"]
                is_final = messages[-1]["role"] == "tool"
                fixture.requests.append({"has_tools": bool(body.get("tools")), "is_final": is_final,
                    "tool_id_bound": is_final and messages[-1]["tool_call_id"] == messages[-2]["tool_calls"][0]["id"]})
                if is_final:
                    text = json.loads(messages[1]["content"])["resume_text"]
                    start = text.index(quote)
                    message = {"role": "assistant", "content": json.dumps({"payload": payload, "citations": [
                        {"summary_index": 0, "start": start, "end": start + len(quote), "quote": quote}]}, ensure_ascii=False)}
                else:
                    message = {"role": "assistant", "content": None, "tool_calls": [{"id": "fixture-requirements",
                        "type": "function", "function": {"name": "get_job_requirements", "arguments": "{}"}}]}
                response = {"id": "fixture-tool-response", "object": "chat.completion", "created": int(time.time()),
                    "model": body["model"], "choices": [{"index": 0, "message": message,
                        "finish_reason": "stop" if is_final else "tool_calls"}],
                    "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}}
                data = json.dumps(response, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
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
