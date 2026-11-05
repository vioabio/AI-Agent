from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping

from liepin_rpa.analysis_execution import (
    AnalysisExecutionStatus,
    is_transient_execution_failure,
)

from liepin_rpa.candidate_decision_rules import (
    MATCH_RESULT_NEEDS_REVIEW,
    VALID_MATCH_RESULTS,
    beauty_relevance_matches_result,
    is_relevant_match_result,
)

ANALYSIS_TRANSIENT_CACHE_TTL = timedelta(minutes=10)
EXPECTED_SCORE_BREAKDOWN_KEYS = {
    "beauty_continuity",
    "industry_depth",
    "role_evidence",
}
EXPECTED_ANALYSIS_KEYS = {
    "beauty_relevant",
    "match_result",
    "match_score",
    "score_breakdown",
    "analysis_reason",
    "evidence_summary",
}
SCORE_BREAKDOWN_LIMITS = {
    "industry_depth": 35,
    "beauty_continuity": 25,
    "role_evidence": 40,
}
RELEVANT_SCORE_MIN = 80


@dataclass(frozen=True, slots=True)
class AnalysisContract:
    relevant_score_min: int
    score_breakdown_limits: Mapping[str, int]


DEFAULT_ANALYSIS_CONTRACT = AnalysisContract(
    relevant_score_min=RELEVANT_SCORE_MIN,
    score_breakdown_limits=SCORE_BREAKDOWN_LIMITS,
)


@dataclass(slots=True)
class ResumeAnalysisResult:
    matched: bool
    match_result: str | None
    match_score: int | None
    score_breakdown: dict[str, int]
    analysis_reason: str | None
    evidence_summary: list[str]
    raw_data: str
    parsed_payload: dict[str, Any]
    execution_status: str | None = None
    error_code: str | None = None
    retryable: bool = False
    retry_after: datetime | None = None
    analysis_run_id: str | None = None
    analyzed_text_hash: str | None = None


def parse_analysis_payload(raw_data: str) -> dict[str, Any]:
    """解析模型返回的单个 JSON 对象，不接受夹带说明文字的响应。"""
    if not raw_data:
        return {}
    try:
        payload = json.loads(raw_data)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def analysis_contract_from_job_profile(
    job_profile: Mapping[str, object] | None,
) -> AnalysisContract:
    """从岗位配置读取评分契约；旧配置继续使用财务兼容默认值。"""
    if not job_profile:
        return DEFAULT_ANALYSIS_CONTRACT
    selection = job_profile.get("selection")
    if not isinstance(selection, Mapping):
        return DEFAULT_ANALYSIS_CONTRACT
    raw_limits = selection.get("score_breakdown_limits")
    if raw_limits is None:
        return DEFAULT_ANALYSIS_CONTRACT
    if not isinstance(raw_limits, Mapping):
        raise ValueError("selection.score_breakdown_limits must be an object")
    if set(raw_limits) != EXPECTED_SCORE_BREAKDOWN_KEYS:
        raise ValueError(
            "selection.score_breakdown_limits must contain exactly the expected dimensions"
        )
    limits = {key: raw_limits[key] for key in EXPECTED_SCORE_BREAKDOWN_KEYS}
    if any(
        isinstance(value, bool) or not isinstance(value, int) or value <= 0
        for value in limits.values()
    ):
        raise ValueError("selection.score_breakdown_limits values must be positive integers")
    if sum(limits.values()) != 100:
        raise ValueError("selection.score_breakdown_limits values must sum to 100")
    relevant_score_min = selection.get("relevant_score_min", RELEVANT_SCORE_MIN)
    if (
        isinstance(relevant_score_min, bool)
        or not isinstance(relevant_score_min, int)
        or not 0 <= relevant_score_min <= 100
    ):
        raise ValueError("selection.relevant_score_min must be an integer between 0 and 100")
    return AnalysisContract(
        relevant_score_min=relevant_score_min,
        score_breakdown_limits=limits,
    )


def validate_analysis_payload(
    payload: dict[str, Any],
    *,
    contract: AnalysisContract = DEFAULT_ANALYSIS_CONTRACT,
) -> list[str]:
    errors: list[str] = []
    if not payload:
        return ["payload is empty or not a JSON object"]
    if set(payload) != EXPECTED_ANALYSIS_KEYS:
        errors.append("payload must contain exactly the expected top-level fields")
    if not isinstance(payload.get("beauty_relevant"), bool):
        errors.append("beauty_relevant must be boolean")
    match_result = normalize_match_result(payload)
    if match_result not in VALID_MATCH_RESULTS:
        errors.append("match_result must be relevant, not_relevant, or needs_review")
    elif not beauty_relevance_matches_result(
        payload.get("beauty_relevant"),
        match_result,
    ):
        errors.append("beauty_relevant must be true exactly when match_result is relevant")
    if not normalize_string_field(payload, "analysis_reason"):
        errors.append("analysis_reason must be a non-empty string")
    if not isinstance(payload.get("evidence_summary"), list):
        errors.append("evidence_summary must be an array")
    score = normalize_match_score(payload)
    if score is None:
        errors.append("match_score must be an integer between 0 and 100")
    elif match_result == "relevant" and score < contract.relevant_score_min:
        errors.append(
            f"relevant match_score must be at least {contract.relevant_score_min}"
        )
    score_breakdown = payload.get("score_breakdown")
    if not isinstance(score_breakdown, dict):
        errors.append("score_breakdown must be an object")
    else:
        if set(score_breakdown) != EXPECTED_SCORE_BREAKDOWN_KEYS:
            errors.append("score_breakdown must contain exactly the expected dimensions")
        normalized_breakdown = normalize_score_breakdown(payload)
        if len(normalized_breakdown) != len(EXPECTED_SCORE_BREAKDOWN_KEYS):
            errors.append("score_breakdown values must be integers between 0 and 100")
        elif any(
            normalized_breakdown[key] > maximum
            for key, maximum in contract.score_breakdown_limits.items()
        ):
            errors.append("score_breakdown values exceed their dimension limits")
        elif score is not None and sum(normalized_breakdown.values()) != score:
            errors.append("match_score must equal the sum of score_breakdown")
    return errors


def analysis_result_from_payload(
    payload: dict[str, Any],
    *,
    raw_data: str,
) -> ResumeAnalysisResult:
    match_result = normalize_match_result(payload)
    return ResumeAnalysisResult(
        matched=is_relevant_match_result(match_result),
        match_result=match_result,
        match_score=normalize_match_score(payload),
        score_breakdown=normalize_score_breakdown(payload),
        analysis_reason=normalize_string_field(payload, "analysis_reason"),
        evidence_summary=normalize_string_list(payload, "evidence_summary"),
        raw_data=raw_data,
        parsed_payload=payload,
        execution_status=AnalysisExecutionStatus.SUCCEEDED,
    )


def build_needs_review_result(
    *,
    raw_data: str,
    reason: str,
    provider_label: str,
    source: str = "payload_validation",
) -> ResumeAnalysisResult:
    analysis_reason = (
        f"需要人工复核：{provider_label}输出结构校验失败，"
        f"系统未使用该结果直接排序。{reason}"
    )
    if source == "api_error":
        analysis_reason = (
            f"需要人工复核：{provider_label} API 临时异常，"
            f"系统未进行自动匹配判断。{reason}"
        )
    elif source == "api_rejected":
        analysis_reason = f"需要人工复核：{provider_label}请求被拒绝，系统未进行自动匹配判断。{reason}"
    payload: dict[str, Any] = {
        "beauty_relevant": False,
        "match_result": MATCH_RESULT_NEEDS_REVIEW,
        "match_score": None,
        "score_breakdown": {},
        "analysis_reason": analysis_reason,
        "evidence_summary": [],
    }
    return ResumeAnalysisResult(
        matched=False,
        match_result=MATCH_RESULT_NEEDS_REVIEW,
        match_score=None,
        score_breakdown={},
        analysis_reason=analysis_reason,
        evidence_summary=[],
        raw_data=raw_data,
        parsed_payload=payload,
        execution_status=(
            AnalysisExecutionStatus.TRANSIENT_ERROR
            if source == "api_error" else (
                AnalysisExecutionStatus.BLOCKED if source == "api_rejected"
                else AnalysisExecutionStatus.INVALID_OUTPUT
            )
        ),
        error_code=(
            "provider_error" if source == "api_error" else (
                "provider_rejected" if source == "api_rejected" else "invalid_payload"
            )
        ),
        retryable=source == "api_error",
        retry_after=(
            datetime.now(timezone.utc).replace(tzinfo=None) + ANALYSIS_TRANSIENT_CACHE_TTL
            if source == "api_error" else None
        ),
    )


def is_transient_analysis_review(result: ResumeAnalysisResult | None) -> bool:
    return bool(
        result is not None
        and result.match_result == MATCH_RESULT_NEEDS_REVIEW
        and is_transient_execution_failure(
            result.analysis_reason, execution_status=result.execution_status
        )
    )


def transient_analysis_cache_retry_due(
    result: ResumeAnalysisResult | None,
    cache_record: Mapping[str, object] | None,
    *,
    now: datetime | None = None,
    ttl: timedelta = ANALYSIS_TRANSIENT_CACHE_TTL,
) -> bool:
    if not is_transient_analysis_review(result):
        return False
    if cache_record is None:
        return True
    retry_after = coerce_retry_deadline(cache_record.get("retry_after"))
    if retry_after is not None:
        resolved_now = coerce_retry_deadline(now or datetime.now(timezone.utc))
        return resolved_now is not None and resolved_now >= retry_after
    cached_at = coerce_cache_datetime(
        cache_record.get("updated_at") or cache_record.get("created_at")
    )
    resolved_now = coerce_cache_datetime(now or datetime.now())
    if cached_at is None or resolved_now is None:
        return True
    return resolved_now - cached_at >= ttl


def coerce_retry_deadline(value: object) -> datetime | None:
    """New execution deadlines are UTC; legacy cache timestamps keep their policy."""
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is not None:
        value = value.astimezone(timezone.utc)
    return value.replace(tzinfo=None)


def coerce_cache_datetime(value: object) -> datetime | None:
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, str) and value.strip():
        try:
            parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        except ValueError:
            return None
    else:
        return None
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone().replace(tzinfo=None)
    return parsed


def normalize_match_result(payload: dict[str, Any]) -> str | None:
    value = payload.get("match_result")
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized or None


def normalize_match_score(payload: dict[str, Any]) -> int | None:
    value = payload.get("match_score")
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if 0 <= value <= 100 else None
    if isinstance(value, float) and value.is_integer():
        normalized = int(value)
        return normalized if 0 <= normalized <= 100 else None
    if isinstance(value, str) and value.strip().isdigit():
        normalized = int(value.strip())
        return normalized if 0 <= normalized <= 100 else None
    return None


def normalize_score_breakdown(payload: dict[str, Any]) -> dict[str, int]:
    value = payload.get("score_breakdown")
    if not isinstance(value, dict):
        return {}
    result: dict[str, int] = {}
    for key, raw in value.items():
        if not isinstance(key, str):
            continue
        normalized = normalize_match_score({"match_score": raw})
        if normalized is not None:
            result[key] = normalized
    return result


def normalize_string_field(payload: dict[str, Any], field_name: str) -> str | None:
    value = payload.get(field_name)
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized or None


def normalize_string_list(payload: dict[str, Any], field_name: str) -> list[str]:
    value = payload.get(field_name)
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for item in value:
        if isinstance(item, str) and item.strip():
            result.append(item.strip())
    return result
