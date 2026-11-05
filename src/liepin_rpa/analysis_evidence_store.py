"""Append-only application writes and fail-closed evidence reads.

All writes share the caller's transaction; importing this module never connects.
The UI only reads the run pointer from an already-authorized candidate row.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json

from sqlalchemy import Column, DateTime, ForeignKey, Integer, MetaData, String, Table, Text, select
from sqlalchemy.dialects.mysql import LONGTEXT, insert as mysql_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert

from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.resume_evidence import (
    EvidenceReference, ResumeSnapshot, build_exact_references, build_snapshot, valid_reference,
)

evidence_metadata = MetaData()
resume_snapshots = Table(
    "candidate_resume_snapshots", evidence_metadata,
    Column("snapshot_id", String(64), primary_key=True),
    Column("candidate_fingerprint", String(64), nullable=False, index=True),
    Column("content_hash", String(64), nullable=False),
    Column("normalization_version", String(64), nullable=False),
    Column("raw_text", Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
    Column("created_at", DateTime, nullable=False),
    mysql_charset="utf8mb4", mysql_engine="InnoDB",
)
analysis_evidence_runs = Table(
    "candidate_resume_analysis_runs", evidence_metadata,
    Column("analysis_run_id", String(64), primary_key=True),
    Column("candidate_fingerprint", String(64), nullable=False, index=True),
    Column("job_profile_id", String(64), nullable=False),
    Column("snapshot_id", String(64), ForeignKey(resume_snapshots.c.snapshot_id), nullable=False),
    Column("analysis_version", String(64), nullable=False),
    Column("result_hash", String(64), nullable=False),
    Column("result_json", Text().with_variant(LONGTEXT(), "mysql"), nullable=False),
    Column("binding_source", String(32), nullable=False),
    Column("created_at", DateTime, nullable=False),
    mysql_charset="utf8mb4", mysql_engine="InnoDB",
)
analysis_evidence_refs = Table(
    "candidate_resume_evidence_refs", evidence_metadata,
    Column("evidence_id", String(64), primary_key=True),
    Column("analysis_run_id", String(64), ForeignKey(analysis_evidence_runs.c.analysis_run_id), nullable=False, index=True),
    Column("snapshot_id", String(64), ForeignKey(resume_snapshots.c.snapshot_id), nullable=False),
    Column("chunk_id", String(64), nullable=False),
    Column("summary_index", Integer, nullable=False),
    Column("start", Integer, nullable=False),
    Column("end", Integer, nullable=False),
    Column("quote", Text, nullable=False),
    mysql_charset="utf8mb4", mysql_engine="InnoDB",
)


def _insert_once(connection, table, values, primary_key):
    if connection.dialect.name == "mysql":
        statement = mysql_insert(table).values(**values)
        statement = statement.on_duplicate_key_update({primary_key: statement.inserted[primary_key]})
    elif connection.dialect.name == "sqlite":
        statement = sqlite_insert(table).values(**values).on_conflict_do_nothing(index_elements=[primary_key])
    else:
        raise ValueError("Evidence storage supports MySQL/SQLite only")
    connection.execute(statement)


def result_document(*, match_result, match_score, score_breakdown, analysis_reason,
                    evidence_summary, execution_status) -> dict:
    return {
        "match_result": match_result, "match_score": match_score,
        "score_breakdown": dict(score_breakdown or {}), "analysis_reason": analysis_reason,
        "evidence_summary": list(evidence_summary or []),
        "execution_status": str(execution_status) if execution_status is not None else None,
    }


def persist_analysis_evidence(connection, *, analysis_run_id: str, candidate_fingerprint: str,
                              job_profile_id: str, analysis_version: str, raw_text: str,
                              analyzed_text_hash: str, result: dict,
                              binding_source: str = "gateway_input") -> str:
    if not analysis_run_id or len(analysis_run_id) > 64 or not job_profile_id:
        raise ValueError("Analysis and job identity are required")
    if binding_source not in {"gateway_input", "synthetic_fixture"}:
        raise ValueError("Cannot attribute an unverified legacy analysis to a new snapshot")
    snapshot = build_snapshot(candidate_fingerprint, raw_text)
    if snapshot.content_hash != analyzed_text_hash:
        raise ValueError("Analysis input and persisted resume text differ")
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    snapshot_values = {**snapshot.__dict__, "created_at": now}
    _insert_once(connection, resume_snapshots, snapshot_values, "snapshot_id")
    stored = connection.execute(select(resume_snapshots).where(
        resume_snapshots.c.snapshot_id == snapshot.snapshot_id
    )).mappings().one()
    if any(stored[key] != value for key, value in snapshot.__dict__.items()):
        raise ValueError("Existing snapshot is not immutable")
    encoded = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    run_values = {
        "analysis_run_id": analysis_run_id, "candidate_fingerprint": candidate_fingerprint,
        "job_profile_id": job_profile_id, "snapshot_id": snapshot.snapshot_id,
        "analysis_version": analysis_version, "result_hash": content_hash(result),
        "result_json": encoded, "binding_source": binding_source, "created_at": now,
    }
    _insert_once(connection, analysis_evidence_runs, run_values, "analysis_run_id")
    existing = connection.execute(select(analysis_evidence_runs).where(
        analysis_evidence_runs.c.analysis_run_id == analysis_run_id
    )).mappings().one()
    if any(existing[key] != value for key, value in run_values.items() if key != "created_at"):
        raise ValueError("Existing analysis run cannot be overwritten")
    summaries = result.get("evidence_summary", [])
    refs = build_exact_references(snapshot, analysis_run_id, summaries) if result.get("execution_status") == "succeeded" else ()
    for ref in refs:
        _insert_once(connection, analysis_evidence_refs,
                     {**ref.__dict__, "analysis_run_id": analysis_run_id}, "evidence_id")
    return analysis_run_id


@dataclass(frozen=True)
class EvidenceBundle:
    status: str
    analysis_run_id: str | None = None
    snapshot: ResumeSnapshot | None = None
    references: tuple[EvidenceReference, ...] = ()


def read_bound_evidence(connection, *, analysis_run_id: str | None, candidate_fingerprint: str,
                         job_profile_id: str, analysis_version: str, result: dict) -> EvidenceBundle:
    if not analysis_run_id:
        return EvidenceBundle("legacy")
    try:
        current_result_hash = content_hash(result)
    except (ValueError, TypeError):
        return EvidenceBundle("invalid")
    run = connection.execute(select(analysis_evidence_runs).where(
        analysis_evidence_runs.c.analysis_run_id == analysis_run_id,
        analysis_evidence_runs.c.candidate_fingerprint == candidate_fingerprint,
        analysis_evidence_runs.c.job_profile_id == job_profile_id,
        analysis_evidence_runs.c.analysis_version == analysis_version,
    )).mappings().first()
    if run is None or run["result_hash"] != current_result_hash or run["binding_source"] not in {"gateway_input", "synthetic_fixture"}:
        return EvidenceBundle("invalid")
    record = connection.execute(select(resume_snapshots).where(
        resume_snapshots.c.snapshot_id == run["snapshot_id"],
        resume_snapshots.c.candidate_fingerprint == candidate_fingerprint,
    )).mappings().first()
    if record is None:
        return EvidenceBundle("invalid")
    try:
        snapshot = build_snapshot(candidate_fingerprint, record["raw_text"])
        if any(record[key] != value for key, value in snapshot.__dict__.items()):
            return EvidenceBundle("invalid")
        stored_result = json.loads(run["result_json"])
        if content_hash(stored_result) != run["result_hash"]:
            return EvidenceBundle("invalid")
    except (ValueError, TypeError):
        return EvidenceBundle("invalid")
    records = connection.execute(select(analysis_evidence_refs).where(
        analysis_evidence_refs.c.analysis_run_id == analysis_run_id
    ).order_by(analysis_evidence_refs.c.summary_index)).mappings().all()
    refs = tuple(EvidenceReference(**{key: row[key] for key in EvidenceReference.__dataclass_fields__}) for row in records)
    if any(not valid_reference(snapshot, analysis_run_id, ref, result["evidence_summary"]) for ref in refs):
        return EvidenceBundle("invalid")
    expected = build_exact_references(snapshot, analysis_run_id, result["evidence_summary"]) if result.get("execution_status") == "succeeded" else ()
    if refs != expected:
        return EvidenceBundle("invalid")
    return EvidenceBundle("verified", analysis_run_id, snapshot, refs)
