"""Explicit current-result projection into HR machine fields; never sends messages."""
import json

from sqlalchemy import func, insert, select, update

from liepin_rpa.analysis_contract import AnalysisContract, validate_analysis_payload
from liepin_rpa.analysis_evidence_store import analysis_evidence_runs, read_bound_evidence
from liepin_rpa.analysis_job_schema import analysis_jobs, job_heads
from liepin_rpa.db import resume_analysis_cache, resume_experience_records, resume_source_locators, build_experience_hash, EXPERIENCE_HASH_VERSION
from liepin_rpa.analysis_versioning import content_hash


def _insert(connection, table, values):
    # Legacy SQLite test/demo tables use BIGINT rather than INTEGER primary keys.
    if connection.dialect.name == "sqlite":
        values = {**values, "id": (connection.scalar(select(func.max(table.c.id))) or 0) + 1}
    connection.execute(insert(table).values(**values))


def project_current_result(queue, job_id):
    """Retryable/idempotent transaction, serialized against enqueue/complete by the queue control lock."""
    with queue._transaction() as (connection, now, _):
        job = connection.execute(select(analysis_jobs).where(analysis_jobs.c.job_id == job_id)).mappings().one()
        head = connection.execute(select(job_heads).where(job_heads.c.scope_id == job["scope_id"])).mappings().one()
        if (job["status"] != "succeeded" or head["current_job_id"] != job_id
                or head["published_run_id"] != job["analysis_run_id"]):
            return "not_current"
        run = connection.execute(select(analysis_evidence_runs).where(
            analysis_evidence_runs.c.analysis_run_id == job["analysis_run_id"])).mappings().one()
        result = json.loads(run["result_json"])
        payload = {key: value for key, value in result.items() if key != "execution_status"}
        payload["beauty_relevant"] = payload.get("match_result") == "relevant"
        if (run["binding_source"] != queue.binding_source or content_hash(result) != job["result_hash"]
                or validate_analysis_payload(payload, contract=AnalysisContract(**json.loads(job["contract_json"])))):
            raise ValueError("Projection result integrity failed")
        evidence = read_bound_evidence(connection, analysis_run_id=job["analysis_run_id"],
            candidate_fingerprint=head["candidate_fingerprint"], job_profile_id=head["job_profile_id"],
            analysis_version=job["pipeline_fingerprint"], result=result)
        if evidence.status != "verified" or evidence.snapshot.snapshot_id != job["snapshot_id"]:
            raise ValueError("Projection evidence integrity failed")
        # Projection cannot manufacture permissions or a completed collection batch.
        locator = connection.execute(select(resume_source_locators.c.id, resume_source_locators.c.candidate_name).where(
            resume_source_locators.c.candidate_fingerprint == head["candidate_fingerprint"],
            resume_source_locators.c.job_profile_id == head["job_profile_id"],
            resume_source_locators.c.search_batch_id == job["batch_id"])).first()
        if locator is None:
            raise ValueError("Projection requires the original collected source locator")
        experience_hash = build_experience_hash(evidence.snapshot.raw_text)
        existing = connection.execute(select(resume_analysis_cache).where(
            resume_analysis_cache.c.candidate_fingerprint == head["candidate_fingerprint"],
            resume_analysis_cache.c.experience_hash == experience_hash,
            resume_analysis_cache.c.analysis_version == job["pipeline_fingerprint"])).mappings().first()
        if existing:
            if existing["analysis_run_id"] != job["analysis_run_id"]:
                raise ValueError("Projection cannot replace another run")
            return "already_projected"
        status = {"relevant": "匹配", "needs_review": "待复核", "not_relevant": "不匹配"}[result["match_result"]]
        _insert(connection, resume_analysis_cache, {
            "candidate_fingerprint": head["candidate_fingerprint"], "experience_hash": experience_hash,
            "experience": evidence.snapshot.raw_text, "analysis_version": job["pipeline_fingerprint"],
            "analysis_status": status, "match_result": result["match_result"], "match_score": result["match_score"],
            "score_breakdown_json": json.dumps(result["score_breakdown"], ensure_ascii=False),
            "analysis_reason": result["analysis_reason"], "evidence_summary_json": json.dumps(result["evidence_summary"], ensure_ascii=False),
            "execution_status": "succeeded", "retryable": False, "analysis_run_id": job["analysis_run_id"],
            "analyzed_text_hash": evidence.snapshot.content_hash, "created_at": now, "updated_at": now})
        # Existing human status, feedback, revision, assignment and suppression are untouched.
        row = connection.execute(select(resume_experience_records.c.id).where(
            resume_experience_records.c.candidate_fingerprint == head["candidate_fingerprint"])).first()
        machine = {"experience": evidence.snapshot.raw_text, "experience_hash": experience_hash,
                   "experience_hash_version": EXPERIENCE_HASH_VERSION, "analysis_status": status,
                   "match_score": result["match_score"], "updated_at": now}
        if row:
            connection.execute(update(resume_experience_records).where(resume_experience_records.c.id == row.id).values(**machine))
        else:
            _insert(connection, resume_experience_records, {**machine, "candidate_fingerprint": head["candidate_fingerprint"],
                "candidate_name": locator.candidate_name, "created_at": now})
        return "projected"
