"""Single-worker request reservations and privacy-preserving call events.

These primitives are initially exercised by the synthetic runner. Production
provider adapters and the legacy database cache are deliberately unchanged.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from decimal import Decimal
import json
from pathlib import Path


def _count(value: object) -> int:
    if type(value) is not int or value < 0:
        raise ValueError("Token/request counts must be nonnegative integers")
    return value


@dataclass(frozen=True)
class Price:
    version: str
    input_per_million: Decimal
    output_per_million: Decimal
    currency: str = "USD"

    def __post_init__(self):
        if not self.version or not self.currency:
            raise ValueError("Price version and currency are required")
        for amount in (self.input_per_million, self.output_per_million):
            if not amount.is_finite() or amount < 0:
                raise ValueError("Prices must be finite and nonnegative")

    def estimate(self, input_tokens: int, output_tokens: int) -> Decimal:
        return (
            _count(input_tokens) * self.input_per_million
            + _count(output_tokens) * self.output_per_million
        ) / Decimal(1_000_000)


class BudgetExhausted(RuntimeError):
    pass


@dataclass(frozen=True)
class Reservation:
    sequence: int
    input_tokens: int
    max_output_tokens: int
    max_cost: Decimal | None
    price: Price | None


class RequestBudget:
    """Reserve before dispatch; settle all attempts, including failures.

    Unknown usage charges the reservation conservatively. Unknown price prevents
    dispatch under a monetary cap. This is not a distributed rate limiter.
    """

    def __init__(self, *, max_requests: int, max_tokens: int, max_cost: Decimal | None = None):
        self.max_requests, self.max_tokens = _count(max_requests), _count(max_tokens)
        if max_cost is not None and (not max_cost.is_finite() or max_cost < 0):
            raise ValueError("Cost cap must be finite and nonnegative")
        self.max_cost = max_cost
        self.requests = self.charged_tokens = self.unknown_usage_calls = 0
        self.currency: str | None = None
        self.charged_cost = Decimal(0)
        self._pending: dict[int, Reservation] = {}

    def reserve(self, *, input_tokens: int, max_output_tokens: int, price: Price | None) -> Reservation:
        input_tokens, max_output_tokens = _count(input_tokens), _count(max_output_tokens)
        tokens = input_tokens + max_output_tokens
        estimate = price.estimate(input_tokens, max_output_tokens) if price else None
        if price is not None and self.currency is not None and price.currency != self.currency:
            raise ValueError("One budget cannot mix price currencies")
        if self.requests >= self.max_requests or self.charged_tokens + tokens > self.max_tokens:
            raise BudgetExhausted("Request/token budget exhausted")
        if self.max_cost is not None and (
            estimate is None or self.charged_cost + estimate > self.max_cost
        ):
            raise BudgetExhausted("Cost budget unavailable or exhausted")
        self.requests += 1
        if price is not None:
            self.currency = price.currency
        self.charged_tokens += tokens
        self.charged_cost += estimate or Decimal(0)
        reservation = Reservation(self.requests, input_tokens, max_output_tokens, estimate, price)
        self._pending[reservation.sequence] = reservation
        return reservation

    def settle(self, reservation: Reservation, *, input_tokens: int | None,
               output_tokens: int | None, price: Price | None) -> dict:
        if self._pending.get(reservation.sequence) is not reservation:
            raise ValueError("Unknown/already settled budget reservation")
        if price != reservation.price:
            raise ValueError("Settlement must use the reserved price version")
        known = input_tokens is not None and output_tokens is not None
        if known:
            actual_tokens = _count(input_tokens) + _count(output_tokens)
            cost = price.estimate(input_tokens, output_tokens) if price else None
        else:
            if input_tokens is not None:
                _count(input_tokens)
            if output_tokens is not None:
                _count(output_tokens)
            actual_tokens = (
                input_tokens if input_tokens is not None else reservation.input_tokens
            ) + (output_tokens if output_tokens is not None else reservation.max_output_tokens)
            cost = None
            self.unknown_usage_calls += 1
        self._pending.pop(reservation.sequence)
        self.charged_tokens += actual_tokens - reservation.input_tokens - reservation.max_output_tokens
        # Keep the reserved charge for unknown usage, not a fabricated actual price.
        cost_charge = price.estimate(
            input_tokens if input_tokens is not None else reservation.input_tokens,
            output_tokens if output_tokens is not None else reservation.max_output_tokens,
        ) if price else None
        if cost_charge is not None and reservation.max_cost is not None:
            self.charged_cost += cost_charge - reservation.max_cost
        return {
            "input_tokens": input_tokens, "output_tokens": output_tokens,
            "estimated_cost": str(cost) if cost is not None else None,
            "usage_status": "reported" if known else "unknown",
            "budget_exceeded": self.charged_tokens > self.max_tokens or (
                self.max_cost is not None and self.charged_cost > self.max_cost
            ),
        }

    def summary(self) -> dict:
        return {
            "request_count": self.requests, "max_requests": self.max_requests,
            "charged_tokens": self.charged_tokens, "max_tokens": self.max_tokens,
            "conservative_cost_charge": str(self.charged_cost),
            "max_cost": str(self.max_cost) if self.max_cost is not None else None,
            "currency": self.currency,
            "unknown_usage_calls": self.unknown_usage_calls,
            "pending_reservations": len(self._pending),
        }


@dataclass(frozen=True)
class CallEvent:
    trace_id: str
    analysis_run_id: str
    sample_id: str
    attempt: int
    provider: str
    model: str
    pipeline_fingerprint: str
    execution_status: str
    error_code: str | None
    latency_ms: float
    input_tokens: int | None
    output_tokens: int | None
    estimated_cost: str | None
    price_version: str | None
    cache_hit: bool = False
    simulated: bool = True

    def as_dict(self) -> dict:
        # No arbitrary context/raw provider body fields can be accidentally serialized.
        return asdict(self)


def write_call_events(path: Path, events: list[CallEvent]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(event.as_dict(), ensure_ascii=False, allow_nan=False) + "\n" for event in events),
        encoding="utf-8",
    )


def summarize_call_events(events: list[CallEvent]) -> dict:
    def percentile(values, fraction):
        if not values:
            return None
        ordered = sorted(values)
        index = (len(ordered) - 1) * fraction
        low = int(index)
        return ordered[low] + (ordered[min(low + 1, len(ordered) - 1)] - ordered[low]) * (index - low)

    known_costs = [Decimal(e.estimated_cost) for e in events if e.estimated_cost is not None]
    per_trace = {}
    for event in events:
        per_trace[event.trace_id] = per_trace.get(event.trace_id, 0) + event.latency_ms
    return {
        "call_count": len(events), "retry_call_count": sum(e.attempt > 1 for e in events),
        "failed_call_count": sum(e.execution_status != "succeeded" for e in events),
        "unknown_usage_call_count": sum(e.input_tokens is None or e.output_tokens is None for e in events),
        "known_input_tokens": sum(e.input_tokens or 0 for e in events),
        "known_output_tokens": sum(e.output_tokens or 0 for e in events),
        "unknown_cost_call_count": len(events) - len(known_costs),
        "known_partial_estimated_cost": str(sum(known_costs, Decimal(0))),
        "estimated_total_cost": str(sum(known_costs, Decimal(0))) if len(known_costs) == len(events) else None,
        "call_latency_ms": {"p50": percentile([e.latency_ms for e in events], 0.5),
                            "p95": percentile([e.latency_ms for e in events], 0.95)},
        "per_trace_call_time_ms": {"p50": percentile(list(per_trace.values()), 0.5),
                                   "p95": percentile(list(per_trace.values()), 0.95)},
        "all_events_simulated": bool(events) and all(e.simulated for e in events),
    }
