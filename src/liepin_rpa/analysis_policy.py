from __future__ import annotations

from liepin_rpa.config import Settings

DAREN_JOB_PROFILE_ID = "daren_live_bd_v1"
FINANCE_JOB_PROFILE_ID = "finance_shared_management_v1"
FDE_JOB_PROFILE_ID = "fde_ai_deployment_engineer_v1"
AI_DEVELOPMENT_JOB_PROFILE_ID = "ai_development_engineer_v1"


def current_analysis_version(settings: Settings, job_profile_id: str) -> str:
    """Return the active scoring policy version for one job."""

    if job_profile_id == FDE_JOB_PROFILE_ID:
        return settings.llm_fde_analysis_version
    if job_profile_id == AI_DEVELOPMENT_JOB_PROFILE_ID:
        return settings.llm_ai_development_analysis_version
    provider = settings.resume_analysis_provider.strip().lower()
    if provider == "openai_compatible":
        if job_profile_id == FINANCE_JOB_PROFILE_ID:
            return settings.llm_finance_analysis_version
        return settings.llm_jd_analysis_version
    if provider == "coze":
        return (
            settings.coze_finance_analysis_version
            if job_profile_id == FINANCE_JOB_PROFILE_ID
            else settings.coze_jd_analysis_version
        )
    raise RuntimeError(f"Unsupported resume analysis provider: {provider}")
