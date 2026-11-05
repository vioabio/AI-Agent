"""Durable shadow analysis queue; no SDK, device, settings, or HR cache writes.

All transitions lock one control row (MySQL) or BEGIN IMMEDIATE (SQLite).
Provider work runs outside transactions. Database UTC is the production clock.
Reservations are conservative and never refunded, including lost attempts.
"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
import json
import re

from sqlalchemy import func, insert, select, update

from liepin_rpa.analysis_contract import AnalysisContract, validate_analysis_payload
from liepin_rpa.analysis_evidence_store import _insert_once, persist_analysis_evidence, result_document, resume_snapshots
from liepin_rpa.analysis_job_schema import (
    analysis_jobs as jobs, job_attempts, job_batches, job_events, job_heads, job_memberships, queue_control,
    collection_receipts, request_plans, request_receipts, collected_inputs,
)
from liepin_rpa.queued_request_plan import QueuedRequestPlan, RequestOutcome, REQUEST_ERRORS
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.resume_evidence import build_snapshot

ACTIVE = ("pending", "running", "retry_wait")
RETRY_ERRORS = {"rate_limited", "timeout", "provider_unavailable", "worker_error"}
ERRORS = RETRY_ERRORS | {"invalid_output", "input_mismatch", "authentication", "cancelled", "budget_exhausted"} | REQUEST_ERRORS


class LeaseLost(RuntimeError):
    pass


class QueueFull(RuntimeError):
    pass


class InvalidJobOutput(ValueError):
    pass


class RequestDispatchError(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__("Request dispatch rejected")


def _identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", value):
        raise ValueError("Expected an opaque identifier of at most 64 characters")
    return value


def _positive(value):
    if type(value) is not int or not 1 <= value <= 1_000_000_000:
        raise ValueError("Expected a positive bounded integer")
    return value


def _nonnegative(value):
    if type(value) is not int or not 0 <= value <= 1_000_000_000:
        raise ValueError("Expected a nonnegative bounded integer")
    return value


@dataclass(frozen=True)
class QueueLimits:
    max_in_flight: int = 1
    max_queued: int = 100
    requests_per_minute: int = 20
    tokens_per_minute: int = 100000

    def __post_init__(self):
        for value in asdict(self).values():
            _positive(value)
        if self.max_in_flight > 4:
            raise ValueError("Initial worker concurrency is limited to 1..4")


@dataclass(frozen=True)
class JobBudget:
    max_attempts: int = 3
    reserve_tokens: int = 4000
    reserve_cost_micros: int = 1000
    max_tokens: int = 12000
    max_cost_micros: int = 3000

    def __post_init__(self):
        for key, value in asdict(self).items():
            (_nonnegative if "cost" in key else _positive)(value)
        if self.max_attempts > 10:
            raise ValueError("At most ten attempts are supported")


@dataclass(frozen=True)
class Lease:
    job_id: str
    worker_id: str
    token: int
    expires_at: datetime


@dataclass(frozen=True)
class JobInput:
    job_id: str
    candidate_fingerprint: str
    job_profile_id: str
    snapshot_id: str
    raw_text: str
    input_hash: str
    pipeline_fingerprint: str
    attempt: int
    deadline_at: datetime
    lease: Lease | None = None
    contract_json: str = ""
    request_plan: dict | None = None


class AnalysisJobQueue:
    def __init__(self, engine, *, binding_source="synthetic_fixture", lease_seconds=30, clock=None,
                 currency="USD", price_version="synthetic-fixed-v1"):
        if engine.dialect.name not in {"sqlite", "mysql"}:
            raise ValueError("Queue supports SQLite/MySQL only")
        if binding_source not in {"synthetic_fixture", "gateway_input"}:
            raise ValueError("Unverified input attribution is forbidden")
        if not 1 <= _positive(lease_seconds) <= 300:
            raise ValueError("Lease duration must be 1..300 seconds")
        self.engine, self.binding_source, self.lease_seconds, self.clock = engine, binding_source, lease_seconds, clock
        self.currency, self.price_version = _identifier(currency), _identifier(price_version)
        if len(currency) > 16:
            raise ValueError("Invalid currency identity")

    def _now(self, connection):
        value = self.clock() if self.clock else connection.scalar(select(
            func.utc_timestamp(6) if connection.dialect.name == "mysql" else func.current_timestamp()))
        if not isinstance(value, datetime):
            raise ValueError("Queue clock must return a datetime")
        return value.astimezone(timezone.utc).replace(tzinfo=None) if value.tzinfo else value

    @contextmanager
    def _transaction(self):
        with self.engine.connect() as connection:
            try:
                if connection.dialect.name == "sqlite":
                    connection.exec_driver_sql("BEGIN IMMEDIATE")
                else:
                    connection.begin()
                statement = select(queue_control).where(queue_control.c.control_id == "main")
                if connection.dialect.name == "mysql":
                    statement = statement.with_for_update()
                control = connection.execute(statement).mappings().one()
                if any(control[key] != getattr(self, key) for key in ("binding_source", "currency", "price_version")):
                    raise ValueError("Queue environment attribution mismatch")
                yield connection, self._now(connection), control
                connection.commit()
            except BaseException:
                connection.rollback()
                raise

    def configure(self, limits=QueueLimits()):
        """Provision one immutable limiter row, after explicit schema creation/migration."""
        if self.engine.dialect.name == "sqlite" and limits.max_in_flight != 1:
            raise ValueError("SQLite demos support a single worker only")
        with self.engine.begin() as connection:
            values = {"control_id": "main", "binding_source": self.binding_source,
                      "currency": self.currency, "price_version": self.price_version, **asdict(limits),
                      "window_start": self._now(connection).replace(second=0, microsecond=0),
                      "window_requests": 0, "window_tokens": 0}
            _insert_once(connection, queue_control, values, "control_id")
            current = connection.execute(select(queue_control)).mappings().one()
            if any(current[key] != values[key] for key in ("binding_source", "currency", "price_version", *asdict(limits))):
                raise ValueError("Existing queue limits are immutable; provision a separate queue")

    def create_batch(self, batch_id, *, max_requests=100, max_tokens=1000000, max_cost_micros=100000):
        _identifier(batch_id)
        values = {"batch_id": batch_id, "max_requests": _positive(max_requests),
                  "max_tokens": _positive(max_tokens), "max_cost_micros": _nonnegative(max_cost_micros)}
        with self._transaction() as (connection, now, _):
            _insert_once(connection, job_batches, {**values, "created_at": now, "collection_completed": False,
                         "charged_requests": 0, "charged_tokens": 0, "charged_cost_micros": 0}, "batch_id")
            batch = connection.execute(select(job_batches).where(job_batches.c.batch_id == batch_id)).mappings().one()
            if any(batch[key] != value for key, value in values.items()):
                raise ValueError("Existing batch budget cannot be replaced")

    def enqueue(self, *, batch_id, candidate_fingerprint, job_profile_id, raw_text, pipeline_fingerprint,
                contract, budget=JobBudget(), deadline_seconds=900, collection_receipt=None, request_plan=None, collected_input=None):
        for value in (batch_id, candidate_fingerprint, job_profile_id):
            _identifier(value)
        if not isinstance(pipeline_fingerprint, str) or not re.fullmatch(r"[a-f0-9]{64}", pipeline_fingerprint):
            raise ValueError("A complete pipeline fingerprint is required")
        if not 1 <= _positive(deadline_seconds) <= 86400:
            raise ValueError("Deadline must be 1..86400 seconds")
        # Validate even hand-built contracts before freezing them into task identity.
        if (set(contract.score_breakdown_limits) != {"industry_depth", "beauty_continuity", "role_evidence"}
                or any(type(v) is not int or v < 1 for v in contract.score_breakdown_limits.values())
                or sum(contract.score_breakdown_limits.values()) != 100
                or type(contract.relevant_score_min) is not int or not 0 <= contract.relevant_score_min <= 100):
            raise ValueError("Invalid frozen scoring contract")
        snapshot = build_snapshot(candidate_fingerprint, raw_text)
        if len(raw_text) > 500000:
            raise ValueError("Resume exceeds queue input limit")
        contract_json = json.dumps(asdict(contract), sort_keys=True, separators=(",", ":"))
        receipt_identity = None
        if collection_receipt is not None:
            expected = {"candidate_name", "source_type", "qualification_state", "qualification_hash", "intake_version"}
            if (not isinstance(collection_receipt, dict) or set(collection_receipt) != expected
                    or collection_receipt["source_type"] != ("synthetic" if self.binding_source == "synthetic_fixture" else "collected")
                    or collection_receipt["qualification_state"] != "passed"
                    or not isinstance(collection_receipt["candidate_name"], str)
                    or not 1 <= len(collection_receipt["candidate_name"].strip()) <= 128
                    or any(not isinstance(collection_receipt[k], str) or not re.fullmatch(r"[a-f0-9]{64}", collection_receipt[k])
                           for k in ("qualification_hash", "intake_version"))):
                raise ValueError("Invalid synthetic collection receipt")
            receipt_identity = {k: v for k, v in collection_receipt.items() if k != "candidate_name"}
        pipeline_record = {"inference": pipeline_fingerprint, "contract": json.loads(contract_json)}
        frozen_input = None
        if collected_input is not None:
            if (not isinstance(collected_input, dict) or set(collected_input) != {"record", "profile"}
                    or not isinstance(collected_input["record"], dict) or not isinstance(collected_input["profile"], dict)
                    or collected_input["record"].get("experience") != raw_text
                    or collected_input["profile"].get("id") != job_profile_id):
                raise ValueError("Collected facts must match the frozen input and job")
            frozen_input = json.dumps(collected_input, ensure_ascii=False, sort_keys=True, allow_nan=False)
            pipeline_record["collected_input_hash"] = content_hash(collected_input)
        if request_plan is not None:
            request_plan = QueuedRequestPlan.from_dict(asdict(request_plan))
            if ((request_plan.mode == "synthetic" and (self.binding_source != "synthetic_fixture" or self.engine.dialect.name != "sqlite"))
                    or (request_plan.mode == "configured" and self.binding_source != "gateway_input")
                    or budget.reserve_tokens != request_plan.input_reserve + request_plan.max_output_tokens
                    or budget.reserve_cost_micros != request_plan.estimate_cost(request_plan.input_reserve, request_plan.max_output_tokens)):
                raise ValueError("Request plan must match the synthetic attempt reservation")
            pipeline_record["request_plan_hash"] = request_plan.fingerprint
        if receipt_identity is not None:
            pipeline_record["collection"] = receipt_identity
        pipeline = content_hash(pipeline_record)
        scope_id = content_hash({"candidate": candidate_fingerprint, "job": job_profile_id})
        key = content_hash({"scope": scope_id, "snapshot": snapshot.snapshot_id, "pipeline": pipeline})
        with self._transaction() as (connection, now, control):
            if request_plan is not None and (request_plan.price_version, request_plan.currency) != (control["price_version"], control["currency"]):
                raise ValueError("Request plan price identity does not match the queue")
            batch = connection.execute(select(job_batches).where(job_batches.c.batch_id == batch_id)).mappings().one()
            existing = connection.execute(select(jobs).where(jobs.c.idempotency_key == key)).mappings().first()
            if existing:
                # Cross-batch reuse belongs to the first batch for charging; no extra call is created.
                member = connection.execute(select(job_memberships).where(
                    job_memberships.c.batch_id == batch_id, job_memberships.c.job_id == existing["job_id"])).first()
                if batch["collection_completed"] and not member:
                    raise ValueError("Collection is closed for this batch")
                self._membership(connection, batch_id, existing["job_id"])
                self._receipt(connection, existing["job_id"], collection_receipt)
                self._plan(connection, existing["job_id"], request_plan)
                self._collected(connection, existing["job_id"], frozen_input)
                return dict(existing)
            if batch["collection_completed"]:
                raise ValueError("Collection is closed for this batch")
            count = connection.scalar(select(func.count()).select_from(jobs).where(jobs.c.status.in_(ACTIVE)))
            replaceable = connection.scalar(select(func.count()).select_from(jobs).where(
                jobs.c.scope_id == scope_id, jobs.c.status.in_(("pending", "retry_wait"))))
            if count - replaceable >= control["max_queued"]:
                raise QueueFull("Queue capacity reached; pause collection and retry enqueue")
            _insert_once(connection, resume_snapshots, {**snapshot.__dict__, "created_at": now}, "snapshot_id")
            stored = connection.execute(select(resume_snapshots).where(
                resume_snapshots.c.snapshot_id == snapshot.snapshot_id)).mappings().one()
            if any(stored[k] != v for k, v in snapshot.__dict__.items()):
                raise ValueError("Snapshot integrity check failed")
            _insert_once(connection, job_heads, {"scope_id": scope_id, "candidate_fingerprint": candidate_fingerprint,
                         "job_profile_id": job_profile_id, "current_job_id": None, "published_run_id": None,
                         "generation": 0}, "scope_id")
            values = {"job_id": key, "idempotency_key": key, "scope_id": scope_id,
                      "snapshot_id": snapshot.snapshot_id, "pipeline_fingerprint": pipeline,
                      "contract_json": contract_json, "batch_id": batch_id, "status": "pending", "attempts": 0,
                      **asdict(budget), "charged_tokens": 0, "charged_cost_micros": 0,
                      "available_at": now, "deadline_at": now + timedelta(seconds=deadline_seconds),
                      "lease_owner": None, "lease_expires_at": None, "lease_token": 0,
                      "last_error_code": None, "retryable": False, "analysis_run_id": None,
                      "result_hash": None, "created_at": now, "updated_at": now}
            connection.execute(insert(jobs).values(**values))
            connection.execute(update(job_heads).where(job_heads.c.scope_id == scope_id).values(
                current_job_id=key, published_run_id=None, generation=job_heads.c.generation + 1))
            self._sweep(connection, now)
            self._membership(connection, batch_id, key)
            self._receipt(connection, key, collection_receipt)
            self._plan(connection, key, request_plan)
            self._collected(connection, key, frozen_input)
            self._event(connection, values, "enqueued", "collector", now)
            return values

    @staticmethod
    def _collected(connection, job_id, encoded):
        if encoded is None:
            return
        document = json.loads(encoded)
        values = {"job_id": job_id, "record_json": json.dumps(document["record"], ensure_ascii=False, sort_keys=True),
                  "profile_json": json.dumps(document["profile"], ensure_ascii=False, sort_keys=True),
                  "input_hash": content_hash(document), "strategy": document["record"].get("_analysis_strategy", "fulltext")}
        if values["strategy"] not in {"fulltext", "tools"}:
            raise ValueError("Unknown frozen analysis strategy")
        _insert_once(connection, collected_inputs, values, "job_id")
        stored = connection.execute(select(collected_inputs).where(collected_inputs.c.job_id == job_id)).mappings().one()
        if any(stored[key] != value for key, value in values.items()):
            raise ValueError("Collected facts cannot be replaced")

    @staticmethod
    def _plan(connection, job_id, plan):
        if plan is not None:
            encoded = json.dumps(asdict(plan), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            _insert_once(connection, request_plans, {"job_id": job_id, "plan_hash": plan.fingerprint,
                         "plan_json": encoded}, "job_id")
            stored = connection.execute(select(request_plans).where(request_plans.c.job_id == job_id)).mappings().one()
            if stored["plan_hash"] != plan.fingerprint or stored["plan_json"] != encoded:
                raise ValueError("Frozen request plan cannot be replaced")

    @staticmethod
    def _load_plan(connection, job_id):
        stored = connection.execute(select(request_plans).where(request_plans.c.job_id == job_id)).mappings().first()
        if stored is None:
            return None
        try:
            plan = QueuedRequestPlan.from_dict(json.loads(stored["plan_json"]))
            if plan.fingerprint != stored["plan_hash"]:
                raise ValueError("Frozen plan hash mismatch")
            return plan
        except (ValueError, TypeError):
            raise InvalidJobOutput("Frozen request plan integrity failed") from None

    @staticmethod
    def _receipt(connection, job_id, receipt):
        if receipt is not None:
            _insert_once(connection, collection_receipts, {"job_id": job_id, **receipt}, "job_id")
            stored = connection.execute(select(collection_receipts).where(collection_receipts.c.job_id == job_id)).mappings().one()
            if any(stored[k] != v for k, v in receipt.items() if k != "candidate_name"):
                raise ValueError("Collection receipt cannot be replaced")

    @staticmethod
    def _membership(connection, batch_id, job_id):
        if not connection.execute(select(job_memberships).where(
            job_memberships.c.batch_id == batch_id, job_memberships.c.job_id == job_id)).first():
            connection.execute(insert(job_memberships).values(batch_id=batch_id, job_id=job_id))

    @staticmethod
    def _event(connection, job, event_type, actor, now):
        import uuid
        connection.execute(insert(job_events).values(event_id=uuid.uuid4().hex, job_id=job["job_id"],
                           event_type=event_type, actor=actor, lease_token=job["lease_token"], created_at=now))

    @staticmethod
    def _backoff(job):
        jitter = int(content_hash({"job": job["job_id"], "attempt": job["attempts"]})[:4], 16) / 65535
        return min(60, 2 ** min(job["attempts"], 6)) + jitter

    def _sweep(self, connection, now):
        for row in connection.execute(select(jobs).where(jobs.c.status.in_(ACTIVE))).mappings().all():
            code = None
            if row["deadline_at"] <= now:
                code = "deadline_exceeded"
            elif row["status"] == "running" and row["lease_expires_at"] <= now:
                code = "lease_expired"
            elif row["status"] != "running":
                current = connection.scalar(select(job_heads.c.current_job_id).where(job_heads.c.scope_id == row["scope_id"]))
                if current != row["job_id"]:
                    connection.execute(update(jobs).where(jobs.c.job_id == row["job_id"]).values(status="superseded", updated_at=now))
                    self._event(connection, row, "superseded", "scheduler", now)
            if not code:
                continue
            available = now + timedelta(seconds=self._backoff(row))
            retry = code == "lease_expired" and row["attempts"] < row["max_attempts"] and available < row["deadline_at"]
            connection.execute(update(jobs).where(jobs.c.job_id == row["job_id"]).values(
                status="retry_wait" if retry else "failed", available_at=available,
                lease_expires_at=None, lease_owner=None, last_error_code=code, retryable=retry, updated_at=now))
            if row["status"] == "running":
                connection.execute(update(job_attempts).where(job_attempts.c.job_id == row["job_id"],
                    job_attempts.c.lease_token == row["lease_token"]).values(status="abandoned", error_code=code, finished_at=now))
            self._event(connection, row, code, "scheduler", now)

    def claim(self, worker_id, *, strategy=None):
        _identifier(worker_id)
        if strategy not in {None, "fulltext", "tools"}:
            raise ValueError("Unknown worker strategy")
        with self._transaction() as (connection, now, control):
            self._sweep(connection, now)
            running = connection.scalar(select(func.count()).select_from(jobs).where(jobs.c.status == "running"))
            if running >= control["max_in_flight"]:
                return None
            minute = now.replace(second=0, microsecond=0)
            if control["window_start"] < minute:
                connection.execute(update(queue_control).values(window_start=minute, window_requests=0, window_tokens=0))
                control = {**control, "window_start": minute, "window_requests": 0, "window_tokens": 0}
            rows = connection.execute(select(jobs).where(jobs.c.status.in_(("pending", "retry_wait")),
                        jobs.c.available_at <= now).order_by(jobs.c.created_at, jobs.c.job_id)).mappings().all()
            for row in rows:
                if strategy is not None:
                    stored_strategy = connection.scalar(select(collected_inputs.c.strategy).where(collected_inputs.c.job_id == row["job_id"]))
                    if (stored_strategy or "fulltext") != strategy:
                        continue
                batch = connection.execute(select(job_batches).where(job_batches.c.batch_id == row["batch_id"])).mappings().one()
                tokens, cost = row["reserve_tokens"], row["reserve_cost_micros"]
                budget_exhausted = (row["attempts"] >= row["max_attempts"]
                    or row["charged_tokens"] + tokens > row["max_tokens"]
                    or row["charged_cost_micros"] + cost > row["max_cost_micros"]
                    or batch["charged_requests"] >= batch["max_requests"]
                    or batch["charged_tokens"] + tokens > batch["max_tokens"]
                    or batch["charged_cost_micros"] + cost > batch["max_cost_micros"]
                    or tokens > control["tokens_per_minute"])
                if budget_exhausted:
                    connection.execute(update(jobs).where(jobs.c.job_id == row["job_id"]).values(
                        status="failed", last_error_code="budget_exhausted", retryable=False, updated_at=now))
                    self._event(connection, row, "budget_exhausted", "scheduler", now)
                    continue
                if (control["window_requests"] >= control["requests_per_minute"]
                        or control["window_tokens"] + tokens > control["tokens_per_minute"]):
                    connection.execute(update(jobs).where(jobs.c.job_id == row["job_id"]).values(
                        status="retry_wait", available_at=control["window_start"] + timedelta(minutes=1),
                        last_error_code="local_rate_limit", retryable=False, updated_at=now))
                    continue
                token = row["lease_token"] + 1
                expires = min(now + timedelta(seconds=self.lease_seconds), row["deadline_at"])
                connection.execute(update(jobs).where(jobs.c.job_id == row["job_id"], jobs.c.lease_token == row["lease_token"]).values(
                    status="running", attempts=row["attempts"] + 1, lease_token=token, lease_owner=worker_id,
                    lease_expires_at=expires, last_error_code=None, retryable=False,
                    charged_tokens=row["charged_tokens"] + tokens, charged_cost_micros=row["charged_cost_micros"] + cost,
                    updated_at=now))
                connection.execute(update(job_batches).where(job_batches.c.batch_id == row["batch_id"]).values(
                    charged_requests=batch["charged_requests"] + 1, charged_tokens=batch["charged_tokens"] + tokens,
                    charged_cost_micros=batch["charged_cost_micros"] + cost))
                connection.execute(update(queue_control).values(window_requests=control["window_requests"] + 1,
                                                               window_tokens=control["window_tokens"] + tokens))
                connection.execute(insert(job_attempts).values(
                    attempt_id=content_hash({"job": row["job_id"], "token": token}), job_id=row["job_id"],
                    lease_token=token, worker_id=worker_id, status="running", reserved_tokens=tokens,
                    reserved_cost_micros=cost, reported_tokens=None, reported_cost_micros=None,
                    started_at=now, finished_at=None, error_code=None))
                self._event(connection, {**row, "lease_token": token}, "claimed", worker_id, now)
                return Lease(row["job_id"], worker_id, token, expires)
            return None

    @staticmethod
    def _owned(connection, lease, now):
        row = connection.execute(select(jobs).where(jobs.c.job_id == lease.job_id)).mappings().one()
        if (row["status"] != "running" or row["lease_owner"] != lease.worker_id or row["lease_token"] != lease.token
                or row["lease_expires_at"] <= now or row["deadline_at"] <= now):
            raise LeaseLost("Worker lease is no longer valid")
        return row

    def heartbeat(self, lease):
        with self._transaction() as (connection, now, _):
            row = self._owned(connection, lease, now)
            expires = min(now + timedelta(seconds=self.lease_seconds), row["deadline_at"])
            connection.execute(update(jobs).where(jobs.c.job_id == lease.job_id).values(lease_expires_at=expires, updated_at=now))
            return Lease(lease.job_id, lease.worker_id, lease.token, expires)

    def read_input(self, lease):
        with self._transaction() as (connection, now, _):
            row = self._owned(connection, lease, now)
            head = connection.execute(select(job_heads).where(job_heads.c.scope_id == row["scope_id"])).mappings().one()
            snap = connection.execute(select(resume_snapshots).where(resume_snapshots.c.snapshot_id == row["snapshot_id"])).mappings().one()
            expected = build_snapshot(head["candidate_fingerprint"], snap["raw_text"])
            if any(snap[k] != v for k, v in expected.__dict__.items()):
                raise InvalidJobOutput("Snapshot integrity failed")
            return JobInput(row["job_id"], head["candidate_fingerprint"], head["job_profile_id"], row["snapshot_id"],
                            snap["raw_text"], snap["content_hash"], row["pipeline_fingerprint"], row["attempts"], row["deadline_at"],
                            lease, row["contract_json"], asdict(plan) if (plan := self._load_plan(connection, lease.job_id)) else None)

    def start_request(self, lease, *, plan_hash, request_hash):
        if any(not isinstance(v, str) or not re.fullmatch(r"[a-f0-9]{64}", v) for v in (plan_hash, request_hash)):
            raise ValueError("Request and plan hashes are required")
        with self._transaction() as (connection, now, control):
            row = self._owned(connection, lease, now)
            plan = self._load_plan(connection, lease.job_id)
            if plan is None or plan.fingerprint != plan_hash or (plan.price_version, plan.currency) != (control["price_version"], control["currency"]):
                raise RequestDispatchError("request_spec_mismatch")
            batch = connection.execute(select(job_batches).where(job_batches.c.batch_id == row["batch_id"])).mappings().one()
            if (row["charged_tokens"] > row["max_tokens"] or row["charged_cost_micros"] > row["max_cost_micros"]
                    or batch["charged_tokens"] > batch["max_tokens"] or batch["charged_cost_micros"] > batch["max_cost_micros"]):
                raise RequestDispatchError("budget_exhausted")
            attempt_id = content_hash({"job": lease.job_id, "token": lease.token})
            if connection.execute(select(request_receipts.c.attempt_id).where(request_receipts.c.attempt_id == attempt_id)).first():
                raise RequestDispatchError("request_already_started")
            connection.execute(insert(request_receipts).values(attempt_id=attempt_id, job_id=lease.job_id,
                plan_hash=plan_hash, request_hash=request_hash, state="started", started_at=now))
            return (row["deadline_at"] - now).total_seconds()

    def record_request_outcome(self, lease, outcome: RequestOutcome):
        """Late usage may update accounting; it can NEVER publish a task result."""
        if not isinstance(outcome, RequestOutcome):
            raise ValueError("A sanitized request outcome is required")
        digest = content_hash(asdict(outcome))
        with self._transaction() as (connection, now, _):
            attempt_id = content_hash({"job": lease.job_id, "token": lease.token})
            attempt = connection.execute(select(job_attempts).where(job_attempts.c.attempt_id == attempt_id)).mappings().one()
            if attempt["worker_id"] != lease.worker_id or attempt["lease_token"] != lease.token:
                raise LeaseLost("Request receipt belongs to another worker")
            receipt = connection.execute(select(request_receipts).where(request_receipts.c.attempt_id == attempt_id)).mappings().one()
            if receipt["outcome_hash"]:
                if receipt["outcome_hash"] != digest:
                    raise ValueError("Recorded request outcome cannot be replaced")
                return {key: receipt[key] for key in ("reported_tokens", "reported_cost_micros", "accounted_tokens", "accounted_cost_micros")}
            row = connection.execute(select(jobs).where(jobs.c.job_id == lease.job_id)).mappings().one()
            plan = self._load_plan(connection, lease.job_id)
            if plan is None or plan.fingerprint != receipt["plan_hash"]:
                raise ValueError("Request plan attribution mismatch")
            accounting = outcome.accounting(plan)
            connection.execute(update(request_receipts).where(request_receipts.c.attempt_id == attempt_id).values(
                **asdict(outcome), **accounting, outcome_hash=digest, completed_at=now))
            connection.execute(update(job_attempts).where(job_attempts.c.attempt_id == attempt_id).values(
                reported_tokens=accounting["reported_tokens"], reported_cost_micros=accounting["reported_cost_micros"]))
            self._charge_extra(connection, row, now, accounting["accounted_tokens"] - attempt["reserved_tokens"],
                               accounting["accounted_cost_micros"] - attempt["reserved_cost_micros"])
            return accounting

    def _settle(self, connection, row, now, *, status, error_code=None, reported_tokens=None, reported_cost_micros=None):
        if reported_tokens is not None:
            _nonnegative(reported_tokens)
        if reported_cost_micros is not None:
            _nonnegative(reported_cost_micros)
        receipt = connection.execute(select(request_receipts).where(
            request_receipts.c.attempt_id == content_hash({"job": row["job_id"], "token": row["lease_token"]}))).mappings().first()
        if receipt is not None and receipt["outcome_hash"]:
            if any(value is not None and value != receipt[key] for key, value in (
                ("reported_tokens", reported_tokens), ("reported_cost_micros", reported_cost_micros))):
                raise ValueError("Settlement disagrees with the request receipt")
            connection.execute(update(job_attempts).where(job_attempts.c.job_id == row["job_id"],
                job_attempts.c.lease_token == row["lease_token"]).values(status=status, error_code=error_code,
                    reported_tokens=receipt["reported_tokens"], reported_cost_micros=receipt["reported_cost_micros"], finished_at=now))
            return  # usage/overages already recorded exactly once, even for a late request
        if receipt is not None and status == "succeeded":
            raise InvalidJobOutput("Request outcome must be recorded before publishing")
        # Unknown/lost attempts retain full reservations. Overages stop subsequent dispatches.
        extra_tokens = max(0, (reported_tokens or 0) - row["reserve_tokens"])
        extra_cost = max(0, (reported_cost_micros or 0) - row["reserve_cost_micros"])
        connection.execute(update(job_attempts).where(job_attempts.c.job_id == row["job_id"],
            job_attempts.c.lease_token == row["lease_token"]).values(status=status, error_code=error_code,
                reported_tokens=reported_tokens, reported_cost_micros=reported_cost_micros, finished_at=now))
        self._charge_extra(connection, row, now, extra_tokens, extra_cost)

    @staticmethod
    def _charge_extra(connection, row, now, extra_tokens, extra_cost):
        if extra_tokens > 0 or extra_cost > 0:
            connection.execute(update(jobs).where(jobs.c.job_id == row["job_id"]).values(
                charged_tokens=jobs.c.charged_tokens + extra_tokens, charged_cost_micros=jobs.c.charged_cost_micros + extra_cost))
            connection.execute(update(job_batches).where(job_batches.c.batch_id == row["batch_id"]).values(
                charged_tokens=job_batches.c.charged_tokens + extra_tokens,
                charged_cost_micros=job_batches.c.charged_cost_micros + extra_cost))
            control = connection.execute(select(queue_control)).mappings().one()
            minute = now.replace(second=0, microsecond=0)
            reset = control["window_start"] < minute
            connection.execute(update(queue_control).values(window_start=minute if reset else control["window_start"],
                window_requests=0 if reset else control["window_requests"],
                window_tokens=(0 if reset else control["window_tokens"]) + extra_tokens))

    def complete(self, lease, *, payload, analyzed_text_hash, reported_tokens=None, reported_cost_micros=None):
        with self._transaction() as (connection, now, _):
            row = connection.execute(select(jobs).where(jobs.c.job_id == lease.job_id)).mappings().one()
            snapshot = connection.execute(select(resume_snapshots).where(resume_snapshots.c.snapshot_id == row["snapshot_id"])).mappings().one()
            if snapshot["content_hash"] != analyzed_text_hash:
                raise InvalidJobOutput("Analysis input does not match the queued snapshot")
            contract = AnalysisContract(**json.loads(row["contract_json"]))
            if validate_analysis_payload(payload, contract=contract) or any(
                not isinstance(s, str) or not s.strip() for s in payload.get("evidence_summary", [])):
                raise InvalidJobOutput("Output failed the frozen scoring contract")
            result = result_document(match_result=payload["match_result"], match_score=payload["match_score"],
                score_breakdown=payload["score_breakdown"], analysis_reason=payload["analysis_reason"],
                evidence_summary=payload["evidence_summary"], execution_status="succeeded")
            digest = content_hash(result)
            if row["status"] in {"succeeded", "superseded"} and row["analysis_run_id"]:
                if row["lease_owner"] != lease.worker_id or row["lease_token"] != lease.token:
                    raise LeaseLost("Completed by another lease")
                if row["result_hash"] != digest:
                    raise InvalidJobOutput("Completed result cannot be replaced")
                return row["status"]
            row = self._owned(connection, lease, now)
            head = connection.execute(select(job_heads).where(job_heads.c.scope_id == row["scope_id"])).mappings().one()
            run_id = content_hash({"job": row["job_id"], "token": lease.token})
            persist_analysis_evidence(connection, analysis_run_id=run_id, candidate_fingerprint=head["candidate_fingerprint"],
                job_profile_id=head["job_profile_id"], analysis_version=row["pipeline_fingerprint"], raw_text=snapshot["raw_text"],
                analyzed_text_hash=analyzed_text_hash, result=result, binding_source=self.binding_source)
            status = "succeeded" if head["current_job_id"] == row["job_id"] else "superseded"
            self._settle(connection, row, now, status="succeeded", reported_tokens=reported_tokens, reported_cost_micros=reported_cost_micros)
            connection.execute(update(jobs).where(jobs.c.job_id == row["job_id"]).values(status=status,
                lease_expires_at=None, analysis_run_id=run_id, result_hash=digest, updated_at=now))
            if status == "succeeded":
                connection.execute(update(job_heads).where(job_heads.c.scope_id == row["scope_id"],
                    job_heads.c.current_job_id == row["job_id"]).values(published_run_id=run_id))
            self._event(connection, row, status, lease.worker_id, now)
            return status

    def fail(self, lease, error_code, *, retry_after_seconds=0, reported_tokens=None, reported_cost_micros=None):
        if error_code not in ERRORS:
            raise ValueError("Only sanitized error codes may be stored")
        if type(retry_after_seconds) not in (int, float) or not 0 <= retry_after_seconds <= 3600:
            raise ValueError("Retry-After must be finite and 0..3600 seconds")
        with self._transaction() as (connection, now, _):
            row = self._owned(connection, lease, now)
            available = now + timedelta(seconds=max(self._backoff(row), retry_after_seconds))
            retry = error_code in RETRY_ERRORS and row["attempts"] < row["max_attempts"] and available < row["deadline_at"]
            self._settle(connection, row, now, status="failed", error_code=error_code,
                         reported_tokens=reported_tokens, reported_cost_micros=reported_cost_micros)
            connection.execute(update(jobs).where(jobs.c.job_id == row["job_id"]).values(
                status="retry_wait" if retry else "failed", available_at=available, lease_expires_at=None,
                lease_owner=None, last_error_code=error_code, retryable=retry, updated_at=now))
            self._event(connection, row, "retry_wait" if retry else "failed", lease.worker_id, now)

    def cancel(self, job_id, *, operator):
        _identifier(operator)
        with self._transaction() as (connection, now, _):
            row = connection.execute(select(jobs).where(jobs.c.job_id == job_id)).mappings().one()
            if row["status"] not in ACTIVE:
                raise ValueError("Only active tasks may be cancelled")
            if row["status"] == "running":
                self._settle(connection, row, now, status="abandoned", error_code="cancelled")
            connection.execute(update(jobs).where(jobs.c.job_id == job_id).values(
                status="cancelled", lease_owner=None, lease_expires_at=None, last_error_code="cancelled", retryable=False, updated_at=now))
            self._event(connection, row, "cancelled", operator, now)

    def retry(self, job_id, *, operator, expected_token=None):
        """Bring an eligible retry forward; never reset attempts, deadline or quotas."""
        _identifier(operator)
        with self._transaction() as (connection, now, _):
            row = connection.execute(select(jobs).where(jobs.c.job_id == job_id)).mappings().one()
            if expected_token is not None and row["lease_token"] != expected_token:
                raise ValueError("Task lease version has changed")
            if (row["status"] != "retry_wait" or not row["retryable"] or row["deadline_at"] <= now
                    or row["last_error_code"] == "rate_limited"):
                raise ValueError("Task is not eligible for operator retry")
            current = connection.scalar(select(job_heads.c.current_job_id).where(job_heads.c.scope_id == row["scope_id"]))
            if current != job_id:
                raise ValueError("A superseded task cannot be retried")
            batch = connection.execute(select(job_batches).where(job_batches.c.batch_id == row["batch_id"])).mappings().one()
            if (row["attempts"] >= row["max_attempts"]
                    or row["charged_tokens"] + row["reserve_tokens"] > row["max_tokens"]
                    or row["charged_cost_micros"] + row["reserve_cost_micros"] > row["max_cost_micros"]
                    or batch["charged_requests"] >= batch["max_requests"]
                    or batch["charged_tokens"] + row["reserve_tokens"] > batch["max_tokens"]
                    or batch["charged_cost_micros"] + row["reserve_cost_micros"] > batch["max_cost_micros"]):
                raise ValueError("Retry cannot exceed existing task/batch budgets")
            connection.execute(update(jobs).where(jobs.c.job_id == job_id).values(available_at=now, updated_at=now))
            self._event(connection, row, "operator_retry", operator, now)

    def close_collection(self, batch_id):
        with self._transaction() as (connection, _now, _):
            connection.execute(update(job_batches).where(job_batches.c.batch_id == batch_id).values(collection_completed=True))

    def status(self, batch_id):
        """Privacy-preserving status; collection completion and analysis completion differ."""
        with self._transaction() as (connection, now, _):
            batch = connection.execute(select(job_batches).where(job_batches.c.batch_id == batch_id)).mappings().one()
            rows = connection.execute(select(jobs).join(job_memberships, job_memberships.c.job_id == jobs.c.job_id)
                                      .where(job_memberships.c.batch_id == batch_id)).mappings().all()
            counts = {state: sum(r["status"] == state for r in rows)
                      for state in (*ACTIVE, "succeeded", "failed", "cancelled", "superseded")}
            active = sum(counts[state] for state in ACTIVE)
            return {"batch_id": batch_id, "collection_completed": batch["collection_completed"],
                    "analysis_pending": active, "analysis_completed": len(rows) - active,
                    "analysis_succeeded": counts["succeeded"], "all_succeeded": bool(rows) and counts["succeeded"] == len(rows),
                    "counts": counts, "charged_requests": batch["charged_requests"],
                    "charged_tokens": batch["charged_tokens"], "charged_cost_micros": batch["charged_cost_micros"],
                    "tasks": [{key: row[key] for key in ("job_id", "status", "attempts", "lease_token", "last_error_code", "retryable")}
                              for row in rows]}
