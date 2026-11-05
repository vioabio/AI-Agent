from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
from openai import APIStatusError

from liepin_rpa.analysis_contract import (
    analysis_contract_from_job_profile,
    validate_analysis_payload,
)
from liepin_rpa.resume_evidence import resume_text_hash
from liepin_rpa.analysis_gateway import analyze_resume_record
from liepin_rpa.config import Settings
from liepin_rpa.search_execution.result_filter import ResultPageFilter
from liepin_rpa.task_generation.config_loader import load_job_profile
import liepin_rpa.analysis_gateway as gateway_module
import liepin_rpa.openai_compatible_analysis as llm_module


VALID_PAYLOAD = """{
  "beauty_relevant": true,
  "match_result": "relevant",
  "match_score": 86,
  "score_breakdown": {
    "industry_depth": 31,
    "beauty_continuity": 22,
    "role_evidence": 33
  },
  "analysis_reason": "【A档】证据充分。",
  "evidence_summary": ["明确负责多主体核算。"]
}"""

DAREN_PAYLOAD = """{
  "beauty_relevant": true,
  "match_result": "relevant",
  "match_score": 86,
  "score_breakdown": {
    "industry_depth": 20,
    "beauty_continuity": 12,
    "role_evidence": 54
  },
  "analysis_reason": "达人营销负责人证据充分。",
  "evidence_summary": ["明确负责达人矩阵和经营结果。"]
}"""


class FakeCompletions:
    def __init__(self, contents: list[str]) -> None:
        self.contents = contents
        self.calls: list[dict[str, object]] = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        content = self.contents.pop(0)
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=content))]
        )


class FakeOpenAI:
    completions: FakeCompletions

    def __init__(self, **_kwargs) -> None:
        self.chat = SimpleNamespace(completions=self.completions)


def _settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "_env_file": None,
        "RESUME_ANALYSIS_PROVIDER": "openai_compatible",
        "LLM_API_KEY": "test-key",
        "LLM_BASE_URL": "https://example.invalid/v1",
        "LLM_MODEL": "test-model",
        "LLM_MAX_ATTEMPTS": 4,
    }
    values.update(overrides)
    return Settings(**values)


def test_openai_compatible_analysis_returns_validated_result(monkeypatch) -> None:
    completions = FakeCompletions([VALID_PAYLOAD])
    FakeOpenAI.completions = completions
    monkeypatch.setattr(llm_module, "OpenAI", FakeOpenAI)

    result = llm_module.analyze_resume_record_with_openai_compatible(
        _settings(),
        resume_record={"candidate_name": "测试候选人", "experience": "多主体核算"},
        system_prompt="只输出JSON",
    )

    assert result.match_result == "relevant"
    assert result.match_score == 86
    assert completions.calls[0]["response_format"] == {"type": "json_object"}


def test_gateway_binds_the_original_input_before_provider_execution(monkeypatch, tmp_path):
    prompt = tmp_path / "prompt.txt"
    prompt.write_text("JSON", encoding="utf-8")
    original = "  原始简历🙂\r\n唯一证据\n"

    def fake(settings, *, resume_record, system_prompt, analysis_contract):
        resume_record["experience"] = "修改过的副本"
        return llm_module.analysis_result_from_payload(llm_module.parse_analysis_payload(VALID_PAYLOAD), raw_data=VALID_PAYLOAD)

    monkeypatch.setattr(gateway_module, "analyze_resume_record_with_openai_compatible", fake)
    result = analyze_resume_record(_settings(), resume_record={"experience": original}, prompt_path=prompt)
    assert result.analyzed_text_hash == resume_text_hash(original)
    assert len(result.analysis_run_id) == 32
    assert set(result.parsed_payload) == {
        "beauty_relevant", "match_result", "match_score", "score_breakdown", "analysis_reason", "evidence_summary"
    }


@pytest.mark.parametrize("text,should_bind", [("简短且未变换的正文", True), ("前段\n\n后段", False), ("长" * 4001, False)])
def test_gateway_does_not_claim_full_input_provenance_after_coze_transform(monkeypatch, text, should_bind):
    import liepin_rpa.coze_workflow as coze

    def fake(*args, **kwargs):
        return llm_module.analysis_result_from_payload(llm_module.parse_analysis_payload(VALID_PAYLOAD), raw_data=VALID_PAYLOAD)

    monkeypatch.setattr(coze, "analyze_resume_record_with_coze", fake)
    result = analyze_resume_record(_settings(RESUME_ANALYSIS_PROVIDER="coze"),
                                   resume_record={"experience": text}, prompt_path=None)
    assert (result.analyzed_text_hash is not None) == should_bind
    assert (result.analysis_run_id is not None) == should_bind


@pytest.mark.parametrize("late_success", [False, True])
def test_total_deadline_prevents_additional_calls_and_discards_late_success(monkeypatch, late_success):
    clock = {"now": 0.0}
    completions = FakeCompletions([VALID_PAYLOAD if late_success else "invalid"])
    original_create = completions.create

    def create(**kwargs):
        response = original_create(**kwargs)
        clock["now"] += 4.0 if late_success else 2.0
        return response

    completions.create = create
    FakeOpenAI.completions = completions
    monkeypatch.setattr(llm_module, "OpenAI", FakeOpenAI)
    monkeypatch.setattr(llm_module.time, "monotonic", lambda: clock["now"])
    monkeypatch.setattr(llm_module.time, "sleep", lambda _: pytest.fail("Must not wait beyond budget"))
    result = llm_module.analyze_resume_record_with_openai_compatible(
        _settings(LLM_TOTAL_TIMEOUT_SECONDS=3), resume_record={}, system_prompt="JSON"
    )
    assert len(completions.calls) == 1
    assert completions.calls[0]["timeout"] == 3
    assert result.error_code == "deadline_exceeded"
    assert result.match_result == "needs_review"


def test_ordinary_auth_failure_can_fail_fast_without_logging_provider_body(monkeypatch):
    calls = []

    def create(**kwargs):
        calls.append(kwargs)
        request = httpx.Request("POST", "https://example.invalid/v1/chat/completions")
        raise APIStatusError("sensitive upstream body", response=httpx.Response(401, request=request), body=None)

    FakeOpenAI.completions = SimpleNamespace(create=create)
    monkeypatch.setattr(llm_module, "OpenAI", FakeOpenAI)
    result = llm_module.analyze_resume_record_with_openai_compatible(
        _settings(LLM_RETRY_AUTH_ERRORS=False), resume_record={}, system_prompt="JSON"
    )
    assert len(calls) == 1
    assert result.execution_status == "blocked"
    assert not result.retryable
    assert result.error_code == "http_401"
    assert "sensitive" not in result.analysis_reason


def test_invalid_payload_retries_then_safely_returns_needs_review(monkeypatch) -> None:
    completions = FakeCompletions(["not-json"] * 4)
    FakeOpenAI.completions = completions
    monkeypatch.setattr(llm_module, "OpenAI", FakeOpenAI)
    monkeypatch.setattr(llm_module.time, "sleep", lambda _seconds: None)

    result = llm_module.analyze_resume_record_with_openai_compatible(
        _settings(),
        resume_record={"candidate_name": "测试候选人", "experience": "简历"},
        system_prompt="只输出JSON",
    )

    assert len(completions.calls) == 4
    assert result.match_result == "needs_review"
    assert "模型输出结构校验失败" in (result.analysis_reason or "")


def test_openai_analysis_uses_daren_score_limits(monkeypatch) -> None:
    daren_profile = load_job_profile(Path("config/job_profiles/daren_live_bd.yaml"))
    daren_contract = analysis_contract_from_job_profile(daren_profile)
    payload = llm_module.parse_analysis_payload(DAREN_PAYLOAD)

    assert validate_analysis_payload(payload)
    assert validate_analysis_payload(payload, contract=daren_contract) == []

    completions = FakeCompletions([DAREN_PAYLOAD])
    FakeOpenAI.completions = completions
    monkeypatch.setattr(llm_module, "OpenAI", FakeOpenAI)

    result = llm_module.analyze_resume_record_with_openai_compatible(
        _settings(),
        resume_record={"candidate_name": "达人候选人", "experience": "达人矩阵负责人"},
        system_prompt="只输出JSON",
        analysis_contract=daren_contract,
    )

    assert result.match_result == "relevant"
    assert result.match_score == 86
    assert result.score_breakdown["role_evidence"] == 54


def test_job_profiles_define_distinct_score_contracts() -> None:
    daren = analysis_contract_from_job_profile(
        load_job_profile(Path("config/job_profiles/daren_live_bd.yaml"))
    )
    finance = analysis_contract_from_job_profile(
        load_job_profile(Path("config/job_profiles/finance_shared_management.yaml"))
    )
    fde = analysis_contract_from_job_profile(
        load_job_profile(Path("config/job_profiles/fde_ai_deployment_engineer_draft.yaml"))
    )
    ai_development = analysis_contract_from_job_profile(
        load_job_profile(Path("config/job_profiles/ai_development_engineer.yaml"))
    )

    assert daren.relevant_score_min == 80
    assert daren.score_breakdown_limits == {
        "industry_depth": 25,
        "beauty_continuity": 15,
        "role_evidence": 60,
    }
    assert finance.relevant_score_min == 80
    assert finance.score_breakdown_limits == {
        "industry_depth": 35,
        "beauty_continuity": 25,
        "role_evidence": 40,
    }
    assert fde.relevant_score_min == 80
    assert fde.score_breakdown_limits == {
        "industry_depth": 35,
        "beauty_continuity": 25,
        "role_evidence": 40,
    }
    assert ai_development.relevant_score_min == 80
    assert ai_development.score_breakdown_limits == {
        "industry_depth": 30,
        "beauty_continuity": 20,
        "role_evidence": 50,
    }


def test_fde_profile_forces_openai_provider_and_own_analysis_version() -> None:
    filter_instance = ResultPageFilter.__new__(ResultPageFilter)
    filter_instance.selection_mode = "jd_smart"
    filter_instance.job_profile = load_job_profile(
        Path("config/job_profiles/fde_ai_deployment_engineer_draft.yaml")
    )

    resolved = filter_instance._analysis_settings(
        _settings(
            RESUME_ANALYSIS_PROVIDER="coze",
            LLM_FDE_ANALYSIS_VERSION="fde-v1",
        )
    )

    assert resolved.resume_analysis_provider == "openai_compatible"
    assert resolved.coze_analysis_version == "fde-v1"
    assert resolved.coze_workflow_id is None


def test_ai_development_profile_forces_own_openai_analysis_version() -> None:
    filter_instance = ResultPageFilter.__new__(ResultPageFilter)
    filter_instance.selection_mode = "jd_smart"
    filter_instance.job_profile = load_job_profile(
        Path("config/job_profiles/ai_development_engineer.yaml")
    )

    resolved = filter_instance._analysis_settings(
        _settings(
            RESUME_ANALYSIS_PROVIDER="coze",
            LLM_AI_DEVELOPMENT_ANALYSIS_VERSION="ai-development-v1",
        )
    )

    assert resolved.resume_analysis_provider == "openai_compatible"
    assert resolved.coze_analysis_version == "ai-development-v1"
    assert resolved.coze_workflow_id is None


def test_gateway_loads_local_prompt_for_openai_provider(monkeypatch, tmp_path: Path) -> None:
    prompt_path = tmp_path / "prompt.txt"
    prompt_path.write_text("财务岗位提示词", encoding="utf-8")
    captured: dict[str, object] = {}

    def fake_analyze(settings, *, resume_record, system_prompt, analysis_contract):
        captured.update(
            settings=settings,
            resume_record=resume_record,
            system_prompt=system_prompt,
            analysis_contract=analysis_contract,
        )
        return SimpleNamespace(match_result="needs_review")

    monkeypatch.setattr(
        gateway_module,
        "analyze_resume_record_with_openai_compatible",
        fake_analyze,
    )

    result = analyze_resume_record(
        _settings(),
        resume_record={"education": "本科"},
        prompt_path=prompt_path,
    )

    assert result.match_result == "needs_review"
    assert captured["system_prompt"] == "财务岗位提示词"
    assert captured["resume_record"] == {"education": "本科"}


def test_analysis_record_includes_collected_age_and_education() -> None:
    record = ResultPageFilter._build_analysis_resume_record(
        candidate_name="测试候选人",
        search_keyword="集团核算经理",
        experience="负责多主体核算",
        age=33,
        education="本科，会计学",
    )

    assert record["age"] == 33
    assert record["education"] == "本科，会计学"
