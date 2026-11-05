"""Immutable, synthetic-only request plans and body-free request receipts."""
from dataclasses import asdict, dataclass
import json
from math import isfinite
from pathlib import Path
import re

from importlib.metadata import version
from liepin_rpa.analysis_versioning import content_hash

REQUEST_ERRORS = {"request_spec_mismatch", "input_budget_exceeded", "model_mismatch", "provider_rejected",
                  "quota_exhausted", "retry_delay_exceeded", "usage_invalid", "model_refusal", "request_already_started"}


def _integer(value, low=0, high=1_000_000_000):
    if type(value) is not int or not low <= value <= high:
        raise ValueError("Invalid bounded request count or price")


def implementation_hash():
    return content_hash({name: Path(__file__).with_name(name).read_text(encoding="utf-8")
                         for name in ("queued_request_plan.py", "queued_openai.py", "analysis_jobs.py",
                                      "analysis_worker.py", "analysis_contract.py")})


@dataclass(frozen=True)
class QueuedRequestPlan:
    model: str
    system_prompt: str
    input_reserve: int
    max_output_tokens: int
    input_micro_per_million: int
    output_micro_per_million: int
    sdk_version: str
    implementation_hash: str
    timeout_seconds: float = 5.0
    price_version: str = "synthetic-fixed-v1"
    currency: str = "USD"
    tokenizer_version: str = "synthetic-utf8-reservation-v1"
    mode: str = "synthetic"

    def __post_init__(self):
        if (self.mode, self.tokenizer_version) not in {("synthetic", "synthetic-utf8-reservation-v1"),
                                                      ("configured", "utf8-byte-reservation-v1")}:
            raise ValueError("Unsupported request accounting mode")
        for value in (self.model, self.sdk_version, self.price_version, self.currency):
            if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,64}", value):
                raise ValueError("Invalid request identity")
        if len(self.currency) > 16 or not re.fullmatch(r"[a-f0-9]{64}", self.implementation_hash):
            raise ValueError("Invalid implementation or currency identity")
        if not isinstance(self.system_prompt, str) or not 1 <= len(self.system_prompt.strip()) <= 64000:
            raise ValueError("A bounded, frozen system prompt is required")
        _integer(self.input_reserve, 1, 500000)
        _integer(self.max_output_tokens, 1, 32768)
        _integer(self.input_micro_per_million)
        _integer(self.output_micro_per_million)
        if type(self.timeout_seconds) not in (int, float) or not isfinite(self.timeout_seconds) or not 0.01 <= self.timeout_seconds <= 60:
            raise ValueError("Invalid per-request timeout")

    @classmethod
    def configured(cls, *, model, system_prompt, input_micro_per_million, output_micro_per_million,
                   price_version, input_reserve=30000, max_output_tokens=1024, timeout_seconds=30, currency="USD"):
        return cls(model=model, system_prompt=system_prompt, input_reserve=input_reserve,
            max_output_tokens=max_output_tokens, input_micro_per_million=input_micro_per_million,
            output_micro_per_million=output_micro_per_million, sdk_version=version("openai"),
            implementation_hash=implementation_hash(), price_version=price_version, timeout_seconds=timeout_seconds, currency=currency,
            tokenizer_version="utf8-byte-reservation-v1", mode="configured")

    @classmethod
    def synthetic(cls, *, model="fixture-chat", system_prompt="仅用于本地协议验证，返回六字段 JSON。", **kwargs):
        return cls(**{"model": model, "system_prompt": system_prompt, "input_reserve": 4096,
            "max_output_tokens": 256, "input_micro_per_million": 1000000, "output_micro_per_million": 2000000,
            "sdk_version": version("openai"), "implementation_hash": implementation_hash(), **kwargs})

    @classmethod
    def from_dict(cls, record):
        if not isinstance(record, dict) or set(record) != set(cls.__dataclass_fields__):
            raise ValueError("Request plan schema mismatch")
        return cls(**record)

    @property
    def fingerprint(self):
        return content_hash(asdict(self))

    def estimate_cost(self, input_tokens, output_tokens):
        _integer(input_tokens)
        _integer(output_tokens)
        return (input_tokens * self.input_micro_per_million + output_tokens * self.output_micro_per_million + 999999) // 1000000

    def budget_fields(self, *, max_attempts=3):
        _integer(max_attempts, 1, 10)
        tokens = self.input_reserve + self.max_output_tokens
        cost = self.estimate_cost(self.input_reserve, self.max_output_tokens)
        return {"reserve_tokens": tokens, "reserve_cost_micros": cost, "max_attempts": max_attempts,
                "max_tokens": tokens * max_attempts, "max_cost_micros": cost * max_attempts}

    def messages(self, raw_text, contract_json):
        return [{"role": "system", "content": self.system_prompt + "\n冻结的评分契约：" + contract_json},
                {"role": "user", "content": json.dumps({"experience": raw_text}, ensure_ascii=False, sort_keys=True)}]

    def synthetic_input_units(self, messages):
        # A fixture convention, NOT a real model tokenizer or a proven token bound.
        return sum(len(message["content"].encode("utf-8")) + 16 for message in messages)


@dataclass(frozen=True)
class RequestOutcome:
    state: str
    latency_ms: float
    http_status: int | None = None
    error_code: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    model_verified: bool = True
    response_id_hash: str | None = None

    def __post_init__(self):
        if self.state not in {"received", "http_error", "timeout", "connection_error", "invalid_response", "not_sent"}:
            raise ValueError("Invalid receipt state")
        if self.error_code not in REQUEST_ERRORS | {None, "timeout", "rate_limited", "provider_unavailable", "authentication", "invalid_output", "budget_exhausted"}:
            raise ValueError("Only sanitized error codes may enter receipts")
        if type(self.model_verified) is not bool or type(self.latency_ms) not in (int, float) or not isfinite(self.latency_ms) or self.latency_ms < 0:
            raise ValueError("Invalid receipt metadata")
        if self.http_status is not None:
            _integer(self.http_status, 100, 599)
        for value in (self.input_tokens, self.output_tokens):
            if value is not None:
                _integer(value)
        if self.response_id_hash is not None and not re.fullmatch(r"[a-f0-9]{64}", self.response_id_hash):
            raise ValueError("Response identifiers must be hashed")

    def accounting(self, plan):
        known = self.input_tokens is not None and self.output_tokens is not None
        tokens = self.input_tokens + self.output_tokens if known else None
        cost = plan.estimate_cost(self.input_tokens, self.output_tokens) if known and self.model_verified else None
        conservative_input = self.input_tokens if self.input_tokens is not None else plan.input_reserve
        conservative_output = self.output_tokens if self.output_tokens is not None else plan.max_output_tokens
        return {"reported_tokens": tokens, "reported_cost_micros": cost,
                "accounted_tokens": max(plan.input_reserve + plan.max_output_tokens, conservative_input + conservative_output),
                "accounted_cost_micros": max(plan.estimate_cost(plan.input_reserve, plan.max_output_tokens),
                    plan.estimate_cost(conservative_input, conservative_output) if self.model_verified else 0)}
