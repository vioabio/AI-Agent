"""One SDK request per lease; separate loopback fixture and explicit HTTPS clients."""
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from math import isfinite
import re
import time
from urllib.parse import urlsplit

import httpx
import openai
from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI

from liepin_rpa.analysis_contract import parse_analysis_payload
from liepin_rpa.analysis_jobs import RequestDispatchError
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.analysis_worker import WorkerFailure, WorkerOutput
from liepin_rpa.queued_request_plan import QueuedRequestPlan, RequestOutcome, implementation_hash


def fixture_endpoint(base_url):
    url = urlsplit(base_url)
    if (url.scheme != "http" or url.hostname not in {"127.0.0.1", "::1"} or url.port is None
            or url.path != "/v1/" or url.query or url.fragment or url.username or url.password):
        raise ValueError("Only explicit numeric-loopback HTTP /v1/ fixtures are allowed")
    return url


class _FixtureOpenAI(OpenAI):
    @property
    def default_headers(self):
        # Do not inherit SDK custom-header/organization/key environment values.
        return {"Accept": "application/json", "Content-Type": "application/json",
                "Authorization": "Bearer synthetic-fixture-only", "User-Agent": "synthetic-queue-sdk"}


def _usage(completion):
    usage = getattr(completion, "usage", None)
    if usage is None:
        return None, None, False
    values = [getattr(usage, name, None) for name in ("prompt_tokens", "completion_tokens", "total_tokens")]
    if any(value is not None and (type(value) is not int or not 0 <= value <= 1_000_000_000) for value in values):
        return None, None, True
    first, second, total = values
    if first is not None and second is not None and total is not None and first + second != total:
        return None, None, True
    return first, second, False


def _retry_delay(headers):
    value = headers.get("retry-after-ms")
    factor = 0.001
    if value is None:
        value, factor = headers.get("retry-after"), 1.0
    if value is None:
        return 0.0
    try:
        delay = float(value) * factor
    except ValueError:
        try:
            parsed = parsedate_to_datetime(value)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            delay = max(0.0, (parsed - datetime.now(timezone.utc)).total_seconds())
        except (ValueError, TypeError, OverflowError):
            return 0.0
    if not isfinite(delay) and re.fullmatch(r"\d+(?:\.\d+)?", value.strip()):
        return 3601.0  # A valid enormous numeric wait must never become a short retry.
    return delay if isfinite(delay) and delay >= 0 else 0.0


class QueuedOpenAIFixtureAdapter:
    def __init__(self, queue, *, base_url):
        if queue.binding_source != "synthetic_fixture" or queue.engine.dialect.name != "sqlite":
            raise ValueError("This SDK adapter is restricted to synthetic SQLite queues")
        endpoint = fixture_endpoint(base_url)
        self.queue, self.base_url = queue, base_url

        def guard(request):
            actual = urlsplit(str(request.url))
            if ((actual.scheme, actual.hostname, actual.port) != (endpoint.scheme, endpoint.hostname, endpoint.port)
                    or actual.path != "/v1/chat/completions" or actual.query or request.method != "POST"):
                raise ValueError("Request left the bound fixture endpoint")

        http_client = httpx.Client(trust_env=False, follow_redirects=False, event_hooks={"request": [guard]})
        self.client = _FixtureOpenAI(api_key="synthetic-fixture-only", admin_api_key="synthetic-admin-unused",
            organization="synthetic-org", project="synthetic-project", webhook_secret="synthetic-webhook-unused",
            base_url=base_url, http_client=http_client, max_retries=0, timeout=5.0)

    def close(self):
        self.client.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def __call__(self, item):
        try:
            plan = QueuedRequestPlan.from_dict(item.request_plan)
        except (ValueError, TypeError):
            raise WorkerFailure("request_spec_mismatch") from None
        if (item.lease is None or plan.sdk_version != openai.__version__ or plan.implementation_hash != implementation_hash()
                or self.client.max_retries != 0 or str(self.client.base_url) != self.base_url):
            raise WorkerFailure("request_spec_mismatch")
        messages = plan.messages(item.raw_text, item.contract_json)
        if plan.synthetic_input_units(messages) > plan.input_reserve:
            raise WorkerFailure("input_budget_exceeded")
        body = {"model": plan.model, "messages": messages, "response_format": {"type": "json_object"},
                "max_completion_tokens": plan.max_output_tokens, "temperature": 0, "store": False,
                "metadata": {"queue_job": item.job_id, "queue_attempt": str(item.attempt)}}
        try:
            remaining = self.queue.start_request(item.lease, plan_hash=plan.fingerprint, request_hash=content_hash(body))
        except RequestDispatchError as error:
            raise WorkerFailure(error.code) from None
        started = time.monotonic()
        outcome, completion, error_code, delay = None, None, None, 0.0
        try:
            completion = self.client.chat.completions.create(**body, timeout=min(plan.timeout_seconds, remaining))
        except APITimeoutError:
            error_code, state, status = "timeout", "timeout", None
        except APIConnectionError:
            error_code, state, status = "provider_unavailable", "connection_error", None
        except APIStatusError as error:
            status, state = error.status_code, "http_error"
            error_code = ("authentication" if status in (401, 403) else "rate_limited" if status == 429 else
                          "timeout" if status == 408 else "provider_unavailable" if status == 409 or status >= 500 else "provider_rejected")
            if status == 429:
                try:
                    code = error.response.json().get("error", {}).get("code")
                except (ValueError, AttributeError):
                    code = None
                if isinstance(code, str) and code in {"insufficient_quota", "billing_hard_limit_reached", "billing_not_active"}:
                    error_code = "quota_exhausted"
            if error_code in {"rate_limited", "timeout", "provider_unavailable"}:
                delay = _retry_delay(error.response.headers)
                if delay > 3600:
                    error_code, delay = "retry_delay_exceeded", 0.0
        except Exception:
            error_code, state, status = "provider_rejected", "invalid_response", None
        elapsed = round((time.monotonic() - started) * 1000, 3)
        if completion is not None:
            first, second, invalid_usage = _usage(completion)
            same_model = getattr(completion, "model", None) == plan.model
            error_code = "model_mismatch" if not same_model else "usage_invalid" if invalid_usage else None
            if time.monotonic() - started >= remaining:
                error_code = "timeout"
            request_id = getattr(completion, "_request_id", None)
            outcome = RequestOutcome("received", elapsed, http_status=200, input_tokens=first, output_tokens=second,
                error_code=error_code, model_verified=same_model,
                response_id_hash=content_hash(request_id) if isinstance(request_id, str) else None)
        else:
            outcome = RequestOutcome(state, elapsed, http_status=status, error_code=error_code, model_verified=False)
        accounting = self.queue.record_request_outcome(item.lease, outcome)
        if error_code:
            raise WorkerFailure(error_code, retry_after_seconds=delay)
        choices = getattr(completion, "choices", None)
        if not isinstance(choices, list) or len(choices) != 1:
            raise WorkerFailure("invalid_output")
        message = getattr(choices[0], "message", None)
        if getattr(message, "refusal", None):
            raise WorkerFailure("model_refusal")
        content = getattr(message, "content", None)
        if getattr(choices[0], "finish_reason", None) != "stop" or not isinstance(content, str):
            raise WorkerFailure("invalid_output")
        payload = parse_analysis_payload(content)
        return WorkerOutput(payload, item.input_hash, accounting["reported_tokens"], accounting["reported_cost_micros"])


class _ConfiguredOpenAI(openai.OpenAI):
    @property
    def default_headers(self):
        # Explicit credentials only; never inherit OPENAI_CUSTOM_HEADERS/org/project.
        return {"Accept": "application/json", "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}", "User-Agent": "queued-resume-analysis"}


class QueuedOpenAIAdapter(QueuedOpenAIFixtureAdapter):
    """Configured HTTPS provider, one request per lease. Construction never dispatches."""
    def __init__(self, queue, *, base_url, api_key, transport=None):
        endpoint = urlsplit(base_url)
        if (queue.binding_source != "gateway_input" or endpoint.scheme != "https" or not endpoint.hostname
                or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment
                or not endpoint.path.endswith("/v1/") or not isinstance(api_key, str) or not api_key.strip()):
            raise ValueError("Configured queue requires an explicit HTTPS /v1/ endpoint and credential")
        self.queue, self.base_url = queue, base_url
        def guard(request):
            actual = urlsplit(str(request.url))
            if ((actual.scheme, actual.hostname, actual.port) != (endpoint.scheme, endpoint.hostname, endpoint.port)
                    or actual.path != endpoint.path + "chat/completions" or actual.query or request.method != "POST"):
                raise ValueError("Configured request left its bound endpoint")
        client = httpx.Client(trust_env=False, follow_redirects=False, transport=transport, event_hooks={"request": [guard]})
        self.client = _ConfiguredOpenAI(api_key=api_key, admin_api_key="unused", organization="unused", project="unused",
            webhook_secret="unused", base_url=base_url, http_client=client, max_retries=0, timeout=30)
