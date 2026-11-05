"""Bounded tool protocol with durable per-request reservation and response replay.

Private transcripts remain in the DB. A request that started without a stored
response is never silently sent again. This module does not create tables.
"""
from dataclasses import asdict, replace
from decimal import Decimal, ROUND_CEILING
import json
from pathlib import Path

from sqlalchemy import insert, select, update

from liepin_rpa.analysis_evidence_store import _insert_once
from liepin_rpa.analysis_job_schema import collected_inputs, tool_sessions, tool_rounds, analysis_jobs, job_batches, queue_control
from liepin_rpa.analysis_observability import BudgetExhausted, Reservation
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.bounded_tools.registry import BoundTools
from liepin_rpa.bounded_tools.runner import ModelReply, ToolCall, ToolsUnsupported, LoopPolicy, run_bounded_tools
from liepin_rpa.resume_evidence import build_snapshot


def _micros(price, first, second):
    return int((price.estimate(first, second) * Decimal(1_000_000)).to_integral_value(rounding=ROUND_CEILING))


class DurableToolBudget:
    def __init__(self, queue, lease, *, session, price, policy):
        self.queue, self.lease, self.price = queue, lease, price
        self.max_requests, self.requests, self._prepaid = policy.max_model_rounds, 0, False
        self._pending = {}
        encoded = json.dumps(session, sort_keys=True, ensure_ascii=False)
        with queue._transaction() as (connection, now, control):
            job = queue._owned(connection, lease, now)
            if (price.version, price.currency) != (control["price_version"], control["currency"]):
                raise ValueError("Tool session price differs from the queue")
            _insert_once(connection, tool_sessions, {"job_id": lease.job_id, "session_hash": content_hash(session),
                "config_json": encoded, "created_at": now}, "job_id")
            stored = connection.execute(select(tool_sessions).where(tool_sessions.c.job_id == lease.job_id)).mappings().one()
            if stored["config_json"] != encoded or stored["session_hash"] != content_hash(session):
                raise ValueError("Tool session identity changed")
            self.max_tokens = job["max_tokens"]
            self.max_cost = Decimal(job["max_cost_micros"]) / Decimal(1_000_000)

    def reserve(self, *, input_tokens, max_output_tokens, price):
        if price != self.price or self.requests >= self.max_requests:
            raise BudgetExhausted("Tool request bound reached")
        sequence = self.requests + 1
        round_id = content_hash({"job": self.lease.job_id, "sequence": sequence})
        with self.queue._transaction() as (connection, now, control):
            job = self.queue._owned(connection, self.lease, now)
            stored = connection.execute(select(tool_rounds).where(tool_rounds.c.round_id == round_id)).mappings().first()
            if stored and stored["state"] not in {"received", "error", "reserved"}:
                raise BudgetExhausted("An earlier request has an unknown outcome")
            if not stored:
                tokens, cost = input_tokens + max_output_tokens, _micros(price, input_tokens, max_output_tokens)
                # The first NEW request in this lease consumes its existing claim reservation.
                extra_tokens = max(0, tokens - job["reserve_tokens"]) if not self._prepaid else tokens
                extra_cost = max(0, cost - job["reserve_cost_micros"]) if not self._prepaid else cost
                extra_requests = int(self._prepaid)
                batch = connection.execute(select(job_batches).where(job_batches.c.batch_id == job["batch_id"])).mappings().one()
                minute = now.replace(second=0, microsecond=0)
                reset = control["window_start"] < minute
                window_requests = 0 if reset else control["window_requests"]
                window_tokens = 0 if reset else control["window_tokens"]
                if (job["charged_tokens"] + extra_tokens > job["max_tokens"]
                        or job["charged_cost_micros"] + extra_cost > job["max_cost_micros"]
                        or batch["charged_requests"] + extra_requests > batch["max_requests"]
                        or batch["charged_tokens"] + extra_tokens > batch["max_tokens"]
                        or batch["charged_cost_micros"] + extra_cost > batch["max_cost_micros"]
                        or window_requests + extra_requests > control["requests_per_minute"]
                        or window_tokens + extra_tokens > control["tokens_per_minute"]):
                    raise BudgetExhausted("Shared tool request budget unavailable")
                connection.execute(update(analysis_jobs).where(analysis_jobs.c.job_id == self.lease.job_id).values(
                    charged_tokens=job["charged_tokens"] + extra_tokens, charged_cost_micros=job["charged_cost_micros"] + extra_cost))
                connection.execute(update(job_batches).where(job_batches.c.batch_id == job["batch_id"]).values(
                    charged_requests=batch["charged_requests"] + extra_requests,
                    charged_tokens=batch["charged_tokens"] + extra_tokens, charged_cost_micros=batch["charged_cost_micros"] + extra_cost))
                connection.execute(update(queue_control).where(queue_control.c.control_id == "main").values(
                    window_start=minute if reset else control["window_start"], window_requests=window_requests + extra_requests,
                    window_tokens=window_tokens + extra_tokens))
                connection.execute(insert(tool_rounds).values(round_id=round_id, job_id=self.lease.job_id,
                    sequence=sequence, lease_token=self.lease.token, state="reserved",
                    reserved_tokens=tokens + max(0, job["reserve_tokens"] - tokens) if not self._prepaid else tokens,
                    reserved_cost_micros=cost + max(0, job["reserve_cost_micros"] - cost) if not self._prepaid else cost,
                    started_at=now))
                self._prepaid = True
        self.requests = sequence
        reservation = Reservation(sequence, input_tokens, max_output_tokens, price.estimate(input_tokens, max_output_tokens), price)
        self._pending[sequence] = (reservation, round_id)
        return reservation

    def settle(self, reservation, *, input_tokens, output_tokens, price):
        pending = self._pending.pop(reservation.sequence, None)
        if pending is None or pending[0] is not reservation or price != self.price:
            raise ValueError("Unknown tool reservation")
        known = input_tokens is not None and output_tokens is not None
        with self.queue._transaction() as (connection, now, _):
            row = connection.execute(select(tool_rounds).where(tool_rounds.c.round_id == pending[1])).mappings().one()
            job = connection.execute(select(analysis_jobs).where(analysis_jobs.c.job_id == self.lease.job_id)).mappings().one()
            actual_tokens = (input_tokens if input_tokens is not None else reservation.input_tokens) + (
                output_tokens if output_tokens is not None else reservation.max_output_tokens)
            cost = _micros(price, input_tokens, output_tokens) if known else None
            conservative_cost = _micros(price, input_tokens if input_tokens is not None else reservation.input_tokens,
                                        output_tokens if output_tokens is not None else reservation.max_output_tokens)
            # A replay has already settled this exact response; don't charge the overage twice.
            if row["finished_at"] is None and row["state"] in {"received", "error"}:
                self.queue._charge_extra(connection, job, now, max(0, actual_tokens - row["reserved_tokens"]),
                    max(0, conservative_cost - row["reserved_cost_micros"]))
                connection.execute(update(tool_rounds).where(tool_rounds.c.round_id == pending[1]).values(
                    input_tokens=input_tokens, output_tokens=output_tokens, reported_cost_micros=cost, finished_at=now))
                job = connection.execute(select(analysis_jobs).where(analysis_jobs.c.job_id == self.lease.job_id)).mappings().one()
        return {"input_tokens": input_tokens, "output_tokens": output_tokens,
                "estimated_cost": str(price.estimate(input_tokens, output_tokens)) if known else None,
                "usage_status": "reported" if known else "unknown",
                "budget_exceeded": job["charged_tokens"] > self.max_tokens
                    or job["charged_cost_micros"] > self.max_cost * Decimal(1_000_000)}

    def summary(self):
        with self.queue.engine.connect() as connection:
            job = connection.execute(select(analysis_jobs).where(analysis_jobs.c.job_id == self.lease.job_id)).mappings().one()
        return {"logical_rounds": self.requests, "max_requests": self.max_requests,
                "charged_tokens": job["charged_tokens"], "max_tokens": self.max_tokens,
                "conservative_cost_charge": str(Decimal(job["charged_cost_micros"]) / Decimal(1_000_000)),
                "max_cost": str(self.max_cost), "currency": self.price.currency,
                "pending_reservations": len(self._pending)}


class ReplayProvider:
    simulated = True

    def __init__(self, budget, provider, *, allow_configured=False):
        configured = getattr(provider, "execution_mode", None) == "configured"
        if (configured and (not allow_configured or budget.queue.binding_source != "gateway_input")) or (
                not configured and getattr(provider, "simulated", False) is not True):
            raise ValueError("Durable tools currently accept scripted/loopback fixtures only")
        self.budget, self.provider = budget, provider
        self.identity, self.tool_support = provider.identity, provider.tool_support
        self.replayed, self.dispatched = 0, 0

    def complete(self, request):
        sequence = self.budget.requests
        round_id = content_hash({"job": self.budget.lease.job_id, "sequence": sequence})
        digest = content_hash(asdict(request) | {"remaining_seconds": None})
        with self.budget.queue._transaction() as (connection, now, _):
            self.budget.queue._owned(connection, self.budget.lease, now)
            row = connection.execute(select(tool_rounds).where(tool_rounds.c.round_id == round_id)).mappings().one()
            if row["request_hash"] is not None and row["request_hash"] != digest:
                raise ValueError("Tool replay request changed")
            if row["state"] in {"received", "error"}:
                frame = json.loads(row["reply_json"])
                self.replayed += 1
            else:
                if row["state"] != "reserved":
                    raise ValueError("Tool request may already have been sent")
                connection.execute(update(tool_rounds).where(tool_rounds.c.round_id == round_id).values(
                    state="started", request_hash=digest, lease_token=self.budget.lease.token))
                frame = None
        if frame is None:
            self.dispatched += 1
            try:
                reply = self.provider.complete(request)
                if not isinstance(reply, ModelReply):
                    raise ValueError("Invalid tool response")
                record = asdict(reply)
                record["tool_calls"] = [call.model_dump() if isinstance(call, ToolCall) else call for call in reply.tool_calls]
                frame = {"reply": record}
            except ToolsUnsupported:
                frame = {"error": "unsupported"}
            except Exception:
                frame = {"error": "provider_error"}
            encoded = json.dumps(frame, ensure_ascii=False, sort_keys=True, allow_nan=False)
            if len(encoded) > 100000:
                frame, encoded = {"error": "provider_error"}, '{"error":"provider_error"}'
            # Late responses can be retained/accounted, but completion is fenced by queue.complete.
            with self.budget.queue._transaction() as (connection, now, _):
                row = connection.execute(select(tool_rounds).where(tool_rounds.c.round_id == round_id)).mappings().one()
                if row["state"] != "started" or row["lease_token"] != self.budget.lease.token:
                    raise ValueError("Tool response owner changed")
                connection.execute(update(tool_rounds).where(tool_rounds.c.round_id == round_id).values(
                    state="error" if "error" in frame else "received", reply_json=encoded))
        if frame.get("error") == "unsupported":
            raise ToolsUnsupported()
        if "error" in frame:
            raise RuntimeError("Sanitized tool provider error")
        record = frame["reply"]
        record["tool_calls"] = tuple(ToolCall.model_validate(call) for call in record["tool_calls"])
        return ModelReply(**record)


def run_durable_tools(queue, item, provider, *, price, as_of_month, policy=LoopPolicy(), allow_configured=False):
    if item.lease is None:
        raise ValueError("A current worker lease is required")
    with queue.engine.connect() as connection:
        stored = connection.execute(select(collected_inputs).where(collected_inputs.c.job_id == item.job_id)).mappings().one()
    record, profile = json.loads(stored["record_json"]), json.loads(stored["profile_json"])
    if queue.binding_source == "gateway_input" and record.get("_analysis_strategy") != "tools":
        raise ValueError("The task was not frozen for tool analysis")
    if content_hash({"record": record, "profile": profile}) != stored["input_hash"]:
        raise ValueError("Collected tool input integrity failed")
    scope = BoundTools(snapshot=build_snapshot(item.candidate_fingerprint, item.raw_text), job_profile_id=item.job_profile_id,
                       collected_record=record, as_of_month=as_of_month)
    from liepin_rpa.analysis_intake import PROFILE_FILES, ROOT
    import yaml
    if profile != yaml.safe_load((ROOT / "config/job_profiles" / PROFILE_FILES[item.job_profile_id]).read_text(encoding="utf-8")):
        raise ValueError("Frozen tool profile changed")
    session = {"scope": scope.version, "policy": asdict(policy), "price": {k: str(v) for k, v in asdict(price).items()},
               "provider": provider.identity, "capability": provider.tool_support,
               "implementation": content_hash(Path(__file__).read_text(encoding="utf-8"))}
    budget = DurableToolBudget(queue, item.lease, session=session, price=price, policy=policy)
    replay = ReplayProvider(budget, provider, allow_configured=allow_configured)
    result = run_bounded_tools(scope, replay, budget=budget, price=price, policy=policy)
    if getattr(provider, "execution_mode", None) == "configured":
        result = replace(result, real_model_calls=replay.dispatched, provider_mode="configured")
    return result, {"replayed_responses": replay.replayed, "new_requests": replay.dispatched}


class DurableToolWorkerAdapter:
    def __init__(self, queue, provider, *, price, as_of_month, policy=LoopPolicy(), allow_configured=False):
        self.queue, self.provider, self.price = queue, provider, price
        self.as_of_month, self.policy = as_of_month, policy
        self.allow_configured = allow_configured

    def __call__(self, item):
        from liepin_rpa.analysis_worker import WorkerFailure, WorkerOutput
        result, _ = run_durable_tools(self.queue, item, self.provider, price=self.price,
            as_of_month=self.as_of_month, policy=self.policy, allow_configured=self.allow_configured)
        if result.state != "succeeded":
            raise WorkerFailure("invalid_output")
        # The legacy persistence helper derives exact references. Ambiguous repeated
        # quotes cannot silently change the explicit tool citation's location.
        if any(item.raw_text.count(ref["quote"]) != 1 for ref in result.citations):
            raise WorkerFailure("invalid_output")
        return WorkerOutput(result.payload, item.input_hash)
