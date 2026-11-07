"""Local synthetic collection adapter; never opens a device or a live provider."""
from dataclasses import dataclass
from pathlib import Path

import yaml

from liepin_rpa.analysis_contract import analysis_contract_from_job_profile
from liepin_rpa.analysis_jobs import JobBudget
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.profile_collection.resume_schema import (
    evaluate_unified_bachelor_requirement, has_explicit_job_intent_city,
)

ROOT = Path(__file__).resolve().parents[2]
PROFILE_FILES = {
    "ai_development_engineer_v1": "ai_development_engineer.yaml",
    "fde_ai_deployment_engineer_v1": "fde_ai_deployment_engineer_draft.yaml",
    "daren_live_bd_v1": "daren_live_bd.yaml",
    "finance_shared_management_v1": "finance_shared_management.yaml",
}
INTAKE_VERSION = content_hash({"adapter": "synthetic-intake-v1",
    "adapter_source": Path(__file__).read_text(encoding="utf-8"),
    "qualification_source": (ROOT / "src/liepin_rpa/profile_collection/resume_schema.py").read_text(encoding="utf-8")})


@dataclass(frozen=True)
class IntakeDecision:
    state: str
    reason_code: str
    job_id: str | None = None


def evaluate_collection_rules(profile, resume_record):
    """Mandatory configured checks shared by intake and bounded tool runs."""
    if (not isinstance(resume_record, dict) or not isinstance(resume_record.get("experience"), str)
            or not resume_record["experience"].strip()):
        raise ValueError("Collected input must contain nonempty original text")
    sections = resume_record.get("sections") or {}
    if (not isinstance(sections, dict) or not isinstance(sections.get("求职意向", []), list)
            or any(not isinstance(line, str) for line in sections.get("求职意向", []))):
        return IntakeDecision("needs_review", "invalid_collected_sections")
    requirement = profile.get("education_requirement") or {}
    if requirement.get("require_unified_admission") is True and requirement.get("minimum_degree") == "本科":
        status = evaluate_unified_bachelor_requirement(resume_record.get("education_experiences"))
        if status != "qualified":
            return IntakeDecision("needs_review" if status == "unknown" else "ineligible", "education_" + status)
    city = profile.get("required_job_intent_city")
    if city and not has_explicit_job_intent_city(sections, city):
        return IntakeDecision("needs_review" if not sections.get("求职意向") else "ineligible", "intent_city_not_confirmed")
    return IntakeDecision("passed", "configured_rules_passed")


class CollectedAnalysisIntake:
    """Dispatch after the caller's full collection gates; freeze facts with the task."""

    def __init__(self, queue):
        self.queue = queue

    def submit(self, *, batch_id, candidate_fingerprint, candidate_name, job_profile_id,
               resume_record, pipeline_fingerprint, budget=JobBudget(), deadline_seconds=900, request_plan=None):
        if job_profile_id not in PROFILE_FILES:
            raise ValueError("Unknown job profile")
        profile = yaml.safe_load((ROOT / "config/job_profiles" / PROFILE_FILES[job_profile_id]).read_text(encoding="utf-8"))
        decision = evaluate_collection_rules(profile, resume_record)
        if decision.state != "passed":
            return decision
        education = resume_record.get("education_experiences")
        sections = resume_record.get("sections") or {}
        facts_hash = content_hash({"education": education, "sections": sections, "profile": profile})
        job = self.queue.enqueue(batch_id=batch_id, candidate_fingerprint=candidate_fingerprint,
            job_profile_id=job_profile_id, raw_text=resume_record.get("experience"),
            pipeline_fingerprint=pipeline_fingerprint, contract=analysis_contract_from_job_profile(profile),
            budget=budget, deadline_seconds=deadline_seconds, request_plan=request_plan, collection_receipt={
                "candidate_name": candidate_name,
                "source_type": "synthetic" if self.queue.binding_source == "synthetic_fixture" else "collected",
                "qualification_state": "passed", "qualification_hash": facts_hash, "intake_version": INTAKE_VERSION},
            collected_input={"record": resume_record, "profile": profile})
        return IntakeDecision("queued", "synthetic_shadow_only" if self.queue.binding_source == "synthetic_fixture"
                              else "queued_for_analysis", job["job_id"])


class SyntheticCollectionIntake(CollectedAnalysisIntake):
    def __init__(self, queue):
        if queue.binding_source != "synthetic_fixture" or queue.engine.dialect.name != "sqlite":
            raise ValueError("Synthetic intake requires an isolated SQLite fixture queue")
        super().__init__(queue)
