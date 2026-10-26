from __future__ import annotations

from pathlib import Path

from liepin_rpa.analysis_contract import (
    DEFAULT_ANALYSIS_CONTRACT,
    AnalysisContract,
    ResumeAnalysisResult,
)
from liepin_rpa.config import Settings
from liepin_rpa.openai_compatible_analysis import (
    analyze_resume_record_with_openai_compatible,
)

PROVIDER_COZE = "coze"
PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"


def analyze_resume_record(
    settings: Settings,
    *,
    resume_record: dict[str, object],
    prompt_path: Path | None,
    analysis_contract: AnalysisContract = DEFAULT_ANALYSIS_CONTRACT,
) -> ResumeAnalysisResult:
    provider = settings.resume_analysis_provider.strip().lower()
    if provider == PROVIDER_COZE:
        from liepin_rpa.coze_workflow import analyze_resume_record_with_coze

        return analyze_resume_record_with_coze(
            settings,
            resume_record=resume_record,
            analysis_contract=analysis_contract,
        )
    if provider == PROVIDER_OPENAI_COMPATIBLE:
        if prompt_path is None:
            raise RuntimeError("analysis_prompt_path is required for openai_compatible")
        system_prompt = prompt_path.read_text(encoding="utf-8")
        return analyze_resume_record_with_openai_compatible(
            settings,
            resume_record=resume_record,
            system_prompt=system_prompt,
            analysis_contract=analysis_contract,
        )
    raise RuntimeError(f"Unsupported RESUME_ANALYSIS_PROVIDER: {provider}")
