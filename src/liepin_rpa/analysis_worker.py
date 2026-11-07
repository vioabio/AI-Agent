"""Bounded synchronous callback runner. No built-in live provider or device path."""
from __future__ import annotations

from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from dataclasses import dataclass
import time

from liepin_rpa.analysis_jobs import ERRORS, InvalidJobOutput, LeaseLost


@dataclass(frozen=True)
class WorkerOutput:
    payload: dict
    analyzed_text_hash: str
    reported_tokens: int | None = None
    reported_cost_micros: int | None = None

    def __post_init__(self):
        if not isinstance(self.payload, dict) or not isinstance(self.analyzed_text_hash, str):
            raise InvalidJobOutput("Invalid result envelope")
        for value in (self.reported_tokens, self.reported_cost_micros):
            if value is not None and (type(value) is not int or not 0 <= value <= 1_000_000_000):
                raise InvalidJobOutput("Invalid usage report")


class WorkerFailure(Exception):
    def __init__(self, code, *, retry_after_seconds=0):
        if code not in ERRORS or type(retry_after_seconds) not in (int, float) or not 0 <= retry_after_seconds <= 3600:
            raise ValueError("Invalid sanitized worker failure")
        self.code, self.retry_after_seconds = code, retry_after_seconds
        super().__init__("Sanitized analysis attempt failure")


class AnalysisWorker:
    def __init__(self, queue, analyze, *, worker_id="offline-worker", concurrency=1, project_to_hr=False, strategy=None):
        if type(concurrency) is not int or not 1 <= concurrency <= 4:
            raise ValueError("Worker concurrency must be 1..4")
        if queue.engine.dialect.name == "sqlite" and concurrency != 1:
            raise ValueError("SQLite supports only a single demo worker")
        self.queue, self.analyze, self.worker_id, self.concurrency = queue, analyze, worker_id, concurrency
        self.project_to_hr = project_to_hr
        self.strategy = strategy

    def run_ready(self, *, max_dispatches=100, dispatch_horizon_seconds=30):
        """Drain currently eligible work, without sleeping through retry schedules.

        Horizon stops dispatch, not running threads. Callback MUST use bounded SDK
        timeouts; Python cannot stop an already-running external request.
        """
        if type(max_dispatches) is not int or not 1 <= max_dispatches <= 10000:
            raise ValueError("Invalid dispatch count")
        if not isinstance(dispatch_horizon_seconds, (int, float)) or not 0 < dispatch_horizon_seconds <= 3600:
            raise ValueError("Invalid dispatch horizon")
        stats = {"dispatched": 0, "succeeded": 0, "superseded": 0, "failed_attempts": 0, "lease_lost": 0}
        if self.project_to_hr:
            stats.update(projected=0, projection_failed=0)
        started, active = time.monotonic(), {}
        heartbeat_period = min(1.0, self.queue.lease_seconds / 3)

        def invoke(lease):
            return self.analyze(self.queue.read_input(lease))

        with ThreadPoolExecutor(max_workers=self.concurrency, thread_name_prefix="resume-analysis") as pool:
            while True:
                while (len(active) < self.concurrency and stats["dispatched"] < max_dispatches
                       and time.monotonic() - started < dispatch_horizon_seconds):
                    lease = self.queue.claim(self.worker_id, strategy=self.strategy) if self.strategy else self.queue.claim(self.worker_id)
                    if lease is None:
                        break
                    stats["dispatched"] += 1
                    active[pool.submit(invoke, lease)] = (lease, time.monotonic(), False)
                if not active:
                    break
                done, _ = wait(active, timeout=heartbeat_period, return_when=FIRST_COMPLETED)
                for future in done:
                    lease, _beat, lost = active.pop(future)
                    if lost:
                        stats["lease_lost"] += 1
                        continue
                    usage = {}
                    try:
                        output = future.result()
                        if not isinstance(output, WorkerOutput):
                            raise InvalidJobOutput("Callback returned an invalid envelope")
                        usage = {"reported_tokens": output.reported_tokens, "reported_cost_micros": output.reported_cost_micros}
                        state = self.queue.complete(lease, payload=output.payload,
                            analyzed_text_hash=output.analyzed_text_hash, reported_tokens=output.reported_tokens,
                            reported_cost_micros=output.reported_cost_micros)
                        stats[state] += 1
                        if self.project_to_hr and state == "succeeded":
                            from liepin_rpa.analysis_projection import project_current_result
                            try:
                                project_current_result(self.queue, lease.job_id)
                                stats["projected"] += 1
                            except Exception:
                                # The completed result is durable; projection can be retried separately.
                                stats["projection_failed"] += 1
                    except LeaseLost:
                        stats["lease_lost"] += 1
                    except Exception as error:
                        code = "invalid_output" if isinstance(error, InvalidJobOutput) else (
                            error.code if isinstance(error, WorkerFailure) else "worker_error")
                        delay = error.retry_after_seconds if isinstance(error, WorkerFailure) else 0
                        try:
                            self.queue.fail(lease, code, retry_after_seconds=delay, **usage)
                            stats["failed_attempts"] += 1
                        except LeaseLost:
                            stats["lease_lost"] += 1
                for future, (lease, beat, lost) in list(active.items()):
                    if not lost and time.monotonic() - beat >= heartbeat_period:
                        try:
                            active[future] = (self.queue.heartbeat(lease), time.monotonic(), False)
                        except LeaseLost:
                            # Keep the physical slot until the callback really returns.
                            active[future] = (lease, beat, True)
        return stats
