from __future__ import annotations

from pathlib import Path
import uuid

from liepin_rpa.resume_evidence import resume_text_hash

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
    text = resume_record.get("experience")
    input_hash = resume_text_hash(text) if isinstance(text, str) and text.strip() else None
    provider = settings.resume_analysis_provider.strip().lower()
    if provider == PROVIDER_COZE:
        from liepin_rpa.coze_workflow import (
            _build_coze_workflow_parameters,
            analyze_resume_record_with_coze,
        )

        # Coze compacts whitespace and truncates to 4000 characters. Do not claim
        # that an unchanged full-text snapshot was the actual analyzed input.
        submitted = _build_coze_workflow_parameters(resume_record)["resume_record"]["experience"]
        if submitted != text:
            input_hash = None

        result = analyze_resume_record_with_coze(
            settings,
            resume_record=resume_record,
            analysis_contract=analysis_contract,
        )
    elif provider == PROVIDER_OPENAI_COMPATIBLE:
        if prompt_path is None:
            raise RuntimeError("analysis_prompt_path is required for openai_compatible")
        system_prompt = prompt_path.read_text(encoding="utf-8")
        result = analyze_resume_record_with_openai_compatible(
            settings,
            resume_record=resume_record,
            system_prompt=system_prompt,
            analysis_contract=analysis_contract,
        )
    else:
        raise RuntimeError(f"Unsupported RESUME_ANALYSIS_PROVIDER: {provider}")
    result.analyzed_text_hash = input_hash
    result.analysis_run_id = uuid.uuid4().hex if input_hash is not None else None
    return result
