"""Authorized, read-only shadow projections. Never writes the legacy HR cache."""
from dataclasses import asdict
from datetime import timezone
import json

from sqlalchemy import func, inspect, select

from apps.hr_review_api.domain.access_policy import UserRole, can_view_all_jobs
from apps.hr_review_api.domain.errors import AnalysisTaskConflictError, AnalysisTaskNotFoundError, AuthorizationError
from liepin_rpa.analysis_contract import AnalysisContract, validate_analysis_payload
from liepin_rpa.analysis_evidence_store import analysis_evidence_runs, read_bound_evidence, resume_snapshots
from liepin_rpa.analysis_intake import PROFILE_FILES
from liepin_rpa.analysis_job_schema import (
    analysis_jobs as jobs, collection_receipts, job_attempts, job_batches, job_heads, queue_metadata, request_receipts, tool_rounds,
)
from liepin_rpa.analysis_jobs import AnalysisJobQueue, ERRORS
from liepin_rpa.resume_evidence import build_snapshot

RETRY_ROLES = {UserRole.ADMIN, UserRole.RECRUITING_LEAD}
PUBLIC_ERROR_CODES = ERRORS | {"deadline_exceeded", "lease_expired", "local_rate_limit"}


def _error_code(value):
    return value if value is None or value in PUBLIC_ERROR_CODES else "unknown_error"


def _utc(value):
    return value.replace(tzinfo=timezone.utc) if value is not None else None


class AnalysisTaskService:
    def __init__(self, engine, *, enabled=False):
        self.engine, self.enabled = engine, enabled
        self.queue = None
        if enabled:
            if engine.dialect.name != "sqlite":
                raise ValueError("Synthetic shadow task views require isolated SQLite")
            if not set(queue_metadata.tables) <= set(inspect(engine).get_table_names()):
                raise ValueError("Shadow task schema is missing; explicitly migrate to 20261105_08")
            self.queue = AnalysisJobQueue(engine)
            # Verify immutable synthetic environment and price identity; do not provision or migrate.
            with self.queue._transaction():
                pass

    @staticmethod
    def _base(user):
        allowed = frozenset(PROFILE_FILES) if can_view_all_jobs(user.role) else user.allowed_job_profile_ids
        return (select(jobs, job_heads.c.candidate_fingerprint, job_heads.c.job_profile_id,
                       job_heads.c.published_run_id, collection_receipts.c.candidate_name,
                       collection_receipts.c.source_type, job_batches.c.collection_completed,
                       job_batches.c.max_requests.label("batch_max_requests"),
                       job_batches.c.charged_requests.label("batch_charged_requests"),
                       job_batches.c.max_tokens.label("batch_max_tokens"),
                       job_batches.c.charged_tokens.label("batch_charged_tokens"),
                       job_batches.c.max_cost_micros.label("batch_max_cost_micros"),
                       job_batches.c.charged_cost_micros.label("batch_charged_cost_micros"))
                .select_from(jobs.join(job_heads, job_heads.c.current_job_id == jobs.c.job_id)
                    .join(collection_receipts, collection_receipts.c.job_id == jobs.c.job_id)
                    .join(job_batches, job_batches.c.batch_id == jobs.c.batch_id))
                .where(job_heads.c.scope_id == jobs.c.scope_id, job_heads.c.job_profile_id.in_(allowed),
                       collection_receipts.c.source_type == "synthetic",
                       collection_receipts.c.qualification_state == "passed"))

    @staticmethod
    def _summary(row, user, now):
        within_budget = (row["attempts"] < row["max_attempts"]
            and row["charged_tokens"] + row["reserve_tokens"] <= row["max_tokens"]
            and row["charged_cost_micros"] + row["reserve_cost_micros"] <= row["max_cost_micros"]
            and row["batch_charged_requests"] < row["batch_max_requests"]
            and row["batch_charged_tokens"] + row["reserve_tokens"] <= row["batch_max_tokens"]
            and row["batch_charged_cost_micros"] + row["reserve_cost_micros"] <= row["batch_max_cost_micros"])
        return {**{k: row[k] for k in ("job_id", "candidate_fingerprint", "candidate_name", "job_profile_id",
                                      "batch_id", "collection_completed", "status", "attempts", "max_attempts",
                                      "lease_token", "last_error_code", "source_type")},
                "available_at": row["available_at"].replace(tzinfo=timezone.utc),
                "deadline_at": row["deadline_at"].replace(tzinfo=timezone.utc),
                "can_retry": user.role in RETRY_ROLES and within_budget and row["status"] == "retry_wait"
                    and row["retryable"] and row["last_error_code"] != "rate_limited" and row["deadline_at"] > now}

    def list_tasks(self, *, user, page=1, page_size=20):
        if not self.enabled:
            return {"enabled": False, "items": [], "total": 0, "page": page, "page_size": page_size}
        with self.engine.connect() as connection:
            base = self._base(user)
            total = connection.scalar(select(func.count()).select_from(base.subquery()))
            rows = connection.execute(base.order_by(jobs.c.updated_at.desc(), jobs.c.job_id)
                .offset((page - 1) * page_size).limit(page_size)).mappings().all()
            now = self.queue._now(connection)
            return {"enabled": True, "total": total, "page": page, "page_size": page_size,
                    "items": [self._summary(row, user, now) for row in rows]}

    def _authorized(self, connection, user, job_id):
        row = connection.execute(self._base(user).where(jobs.c.job_id == job_id)).mappings().first()
        if row is None:
            raise AnalysisTaskNotFoundError("影子任务不存在、版本已更新或无岗位访问权限")
        return row

    def get_task(self, *, user, job_id):
        if not self.enabled:
            raise AnalysisTaskNotFoundError("影子任务未启用或不存在")
        with self.engine.connect() as connection:
            row = self._authorized(connection, user, job_id)
            summary = self._summary(row, user, self.queue._now(connection))
            response = {"task": summary, "snapshot": None, "result": None, "evidence_refs": [], "result_status": "pending",
                        "execution": self._execution(connection, row)}
            stored = connection.execute(select(resume_snapshots).where(resume_snapshots.c.snapshot_id == row["snapshot_id"])).mappings().first()
            if stored is None:
                return {**response, "result_status": "invalid"}
            try:
                snapshot = build_snapshot(row["candidate_fingerprint"], stored["raw_text"])
            except ValueError:
                return {**response, "result_status": "invalid"}
            if any(stored[k] != v for k, v in snapshot.__dict__.items()):
                return {**response, "result_status": "invalid"}
            response["snapshot"] = {**asdict(snapshot), "is_current": True}
            if row["status"] != "succeeded":
                return response
            response["result_status"] = "invalid"
            if not row["analysis_run_id"] or row["published_run_id"] != row["analysis_run_id"]:
                return response
            run = connection.execute(select(analysis_evidence_runs).where(
                analysis_evidence_runs.c.analysis_run_id == row["analysis_run_id"])).mappings().first()
            if run is None or run["binding_source"] != "synthetic_fixture":
                return response
            try:
                result = json.loads(run["result_json"])
                payload = {k: v for k, v in result.items() if k != "execution_status"}
                payload["beauty_relevant"] = payload.get("match_result") == "relevant"
                if (validate_analysis_payload(payload, contract=AnalysisContract(**json.loads(row["contract_json"])))
                        or any(not isinstance(value, str) for value in payload["evidence_summary"])):
                    return response
                bundle = read_bound_evidence(connection, analysis_run_id=row["analysis_run_id"],
                    candidate_fingerprint=row["candidate_fingerprint"], job_profile_id=row["job_profile_id"],
                    analysis_version=row["pipeline_fingerprint"], result=result)
                if bundle.status != "verified" or bundle.snapshot != snapshot or run["result_hash"] != row["result_hash"]:
                    return response
            except (ValueError, TypeError, AttributeError, KeyError):
                return response
            response.update(result_status="verified", result=payload, evidence_refs=[asdict(ref) for ref in bundle.references])
            return response

    def _execution(self, connection, row):
        """Only called after _authorized; no prompts, raw errors, actors or batch-wide charges."""
        attempts = connection.execute(select(job_attempts).where(job_attempts.c.job_id == row["job_id"])
            .order_by(job_attempts.c.started_at, job_attempts.c.lease_token).limit(11)).mappings().all()
        ids = [attempt["attempt_id"] for attempt in attempts[:10]]
        receipts = {item["attempt_id"]: item for item in connection.execute(select(request_receipts).where(
            request_receipts.c.job_id == row["job_id"], request_receipts.c.attempt_id.in_(ids))).mappings()} if ids else {}
        history = []
        for sequence, attempt in enumerate(attempts[:10], 1):
            receipt = receipts.get(attempt["attempt_id"])
            request = None
            if receipt is not None:
                request = {key: receipt[key] for key in ("http_status", "latency_ms", "input_tokens", "output_tokens",
                                                        "reported_cost_micros")}
                state = receipt["state"]
                request.update(state=state if state in {"started", "received", "http_error", "timeout", "connection_error",
                    "invalid_response", "not_sent"} else "unknown", error_code=_error_code(receipt["error_code"]),
                    started_at=_utc(receipt["started_at"]), completed_at=_utc(receipt["completed_at"]))
            state = attempt["status"]
            history.append({key: attempt[key] for key in ("reserved_tokens", "reserved_cost_micros", "reported_tokens",
                                                         "reported_cost_micros")} | {
                "sequence": sequence, "status": state if state in {"running", "succeeded", "failed", "abandoned"} else "unknown",
                "started_at": _utc(attempt["started_at"]), "finished_at": _utc(attempt["finished_at"]),
                "error_code": _error_code(attempt["error_code"]), "request": request})
        rounds = connection.execute(select(tool_rounds).where(tool_rounds.c.job_id == row["job_id"])
            .order_by(tool_rounds.c.sequence).limit(11)).mappings().all()
        attempt_sequences = {attempt["lease_token"]: index for index, attempt in enumerate(attempts[:10], 1)}
        public_rounds = [{key: item[key] for key in ("sequence", "reserved_tokens", "reserved_cost_micros",
            "input_tokens", "output_tokens", "reported_cost_micros")} | {
            "attempt_sequence": attempt_sequences.get(item["lease_token"]),
            "state": item["state"] if item["state"] in {"reserved", "started", "received", "error"} else "unknown",
            "started_at": _utc(item["started_at"]), "finished_at": _utc(item["finished_at"])} for item in rounds[:10]]
        return {key: row[key] for key in ("charged_tokens", "max_tokens", "charged_cost_micros", "max_cost_micros")} | {
            "mode": "synthetic_only", "currency": self.queue.currency, "price_version": self.queue.price_version,
            "history_truncated": len(attempts) > 10 or len(rounds) > 10, "attempts": history, "tool_rounds": public_rounds}

    def retry(self, *, user, job_id, expected_token):
        if not self.enabled:
            raise AnalysisTaskNotFoundError("影子任务未启用或不存在")
        # Check scope before role to avoid confirming the existence of inaccessible tasks.
        with self.engine.connect() as connection:
            row = self._authorized(connection, user, job_id)
            if user.role not in RETRY_ROLES:
                raise AuthorizationError("只有招聘负责人或管理员可调度影子重试")
            if not self._summary(row, user, self.queue._now(connection))["can_retry"]:
                raise AnalysisTaskConflictError("任务已变化或不能重试，请刷新列表")
        try:
            self.queue.retry(job_id, operator=f"hr-user:{user.id}", expected_token=expected_token)
        except ValueError:
            raise AnalysisTaskConflictError("任务版本或状态已变化，请刷新列表") from None
        return self.get_task(user=user, job_id=job_id)
