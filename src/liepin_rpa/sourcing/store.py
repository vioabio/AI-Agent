"""Transactional observation ledger. No model dispatch, browser or HR writes."""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import re

from sqlalchemy import select

from liepin_rpa.analysis_evidence_store import _insert_once
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.sourcing.contracts import SourceObservation, parse_observation
from liepin_rpa.sourcing.schema import (
    sourcing_runs as runs, sourcing_platform_runs as platforms,
    candidate_source_identities as identities, sourcing_observations as observations,
)


class SourceConflict(ValueError):
    """Stable identifiers cannot be reused with different immutable contents."""


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", value):
        raise ValueError("Invalid sourcing identifier")
    return value


@dataclass(frozen=True)
class PlatformScope:
    platform: str
    account_alias: str
    platform_job_ref: str | None = None

    def __post_init__(self):
        if (self.platform not in {"liepin", "boss"} or not isinstance(self.account_alias, str)
                or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,31}", self.account_alias)):
            raise ValueError("Invalid platform/account scope")
        ref = self.platform_job_ref
        if ref is not None and (not isinstance(ref, str) or not 1 <= len(ref) <= 255 or ref != ref.strip()):
            raise ValueError("Invalid platform job reference")


@dataclass(frozen=True)
class ObservationReceipt:
    event_key: str
    candidate_fingerprint: str
    state: str
    analysis_readiness: str


class SourceStore:
    def __init__(self, engine, *, mode="fixture"):
        if mode not in {"fixture", "live"} or engine.dialect.name not in {"sqlite", "mysql"}:
            raise ValueError("Unsupported sourcing binding")
        if mode == "fixture" and engine.dialect.name != "sqlite":
            raise ValueError("Fixture observations require an isolated SQLite database")
        self.engine, self.mode = engine, mode

    @contextmanager
    def _transaction(self):
        with self.engine.connect() as connection:
            try:
                if connection.dialect.name == "sqlite":
                    connection.exec_driver_sql("BEGIN IMMEDIATE")
                else:
                    connection.begin()
                yield connection
                connection.commit()
            except BaseException:
                connection.rollback()
                raise

    def create_run(self, *, run_id, job_profile_id, scopes, max_requests, max_tokens, max_cost_micros):
        identifier(run_id)
        identifier(job_profile_id)
        scopes = tuple(scopes)
        if (not 1 <= len(scopes) <= 2 or any(not isinstance(scope, PlatformScope) for scope in scopes)
                or len({scope.platform for scope in scopes}) != len(scopes)):
            raise ValueError("One explicit account per platform, at most two platforms")
        for number, minimum in ((max_requests, 1), (max_tokens, 1), (max_cost_micros, 0)):
            if type(number) is not int or not minimum <= number <= 1_000_000_000:
                raise ValueError("Invalid parent budget")
        scope_values = sorted([dict(platform=s.platform, account_alias=s.account_alias,
                                   platform_job_ref=s.platform_job_ref) for s in scopes], key=lambda s: s["platform"])
        config = dict(mode=self.mode, job_profile_id=job_profile_id, scopes=scope_values,
                      max_requests=max_requests, max_tokens=max_tokens, max_cost_micros=max_cost_micros)
        # This reserves one future queue batch identity; it does NOT create a queue or multiply its budget.
        batch_id = content_hash(["sourcing-parent-batch-v1", self.mode, run_id])
        with self._transaction() as connection:
            now = datetime.now(timezone.utc).replace(tzinfo=None)
            _insert_once(connection, runs, dict(run_id=run_id, mode=self.mode, job_profile_id=job_profile_id,
                analysis_batch_id=batch_id, config_hash=content_hash(config), max_requests=max_requests,
                max_tokens=max_tokens, max_cost_micros=max_cost_micros, created_at=now), "run_id")
            row = connection.execute(select(runs).where(runs.c.run_id == run_id).with_for_update()).mappings().one()
            if row["config_hash"] != content_hash(config):
                raise SourceConflict("run_configuration_conflict")
            for scope in scope_values:
                key = content_hash([run_id, scope["platform"], scope["account_alias"]])
                _insert_once(connection, platforms, dict(platform_run_id=key, run_id=run_id,
                    **scope, created_at=now), "platform_run_id")
        return batch_id

    def append(self, observation: SourceObservation):
        if not isinstance(observation, SourceObservation):
            raise ValueError("Expected a validated source observation")
        if observation.mode != self.mode:
            raise ValueError("Sourcing mode attribution mismatch")
        # Revalidate even objects made with Pydantic's trusted construction/copy APIs.
        event = parse_observation(observation.model_dump_json())
        document = event.document()
        body = json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        if len(body.encode("utf-8")) > 1_000_000:
            raise ValueError("Observation exceeds bounded input size")
        digest = content_hash(document)
        with self._transaction() as connection:
            run = connection.execute(select(runs).where(runs.c.run_id == event.run_id).with_for_update()).mappings().one_or_none()
            if not run or run["mode"] != self.mode or run["job_profile_id"] != event.job_profile_id:
                raise ValueError("Observation does not match a configured run")
            platform_run = connection.execute(select(platforms).where(platforms.c.run_id == event.run_id,
                platforms.c.platform == event.platform, platforms.c.account_alias == event.account_alias)).mappings().one_or_none()
            if not platform_run:
                raise ValueError("Observation account/platform not allowed for this run")
            prior = connection.execute(select(observations).where(observations.c.event_key == event.event_key)).mappings().one_or_none()
            if prior and prior["payload_hash"] != digest:
                raise SourceConflict("event_payload_conflict")
            if prior:
                return ObservationReceipt(event.event_key, event.identity_key, "replayed", prior["analysis_readiness"])
            now = datetime.now(timezone.utc).replace(tzinfo=None)
            _insert_once(connection, identities, dict(identity_key=event.identity_key, candidate_fingerprint=event.identity_key,
                mode=self.mode, platform=event.platform, account_alias=event.account_alias, identity_kind=event.identity_kind,
                source_ref=event.platform_candidate_id or event.platform_resume_id, created_at=now), "identity_key")
            _insert_once(connection, observations, dict(event_key=event.event_key, platform_run_id=platform_run["platform_run_id"],
                identity_key=event.identity_key, event_id=event.event_id, payload_hash=digest, payload_json=body,
                content_hash=content_hash(event.resume_text) if event.resume_text else None,
                analysis_readiness=event.analysis_readiness, captured_at=event.captured_at.replace(tzinfo=None), created_at=now), "event_key")
        return ObservationReceipt(event.event_key, event.identity_key, "stored", event.analysis_readiness)
