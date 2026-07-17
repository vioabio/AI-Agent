from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any

from cozepy import Coze, TokenAuth
from cozepy.exception import CozeAPIError

from liepin_rpa.config import Settings
from liepin_rpa.logging_utils import get_logger

LOGGER = get_logger(__name__)
COZE_MAX_ATTEMPTS = 3
COZE_RETRYABLE_CODES = {5000}
VALID_MATCH_RESULTS = {"relevant", "not_relevant", "needs_review"}


@dataclass(slots=True)
class CozeMatchResult:
    matched: bool
    match_result: str | None
    match_score: int | None
    score_breakdown: dict[str, int]
    analysis_reason: str | None
    evidence_summary: list[str]
    raw_data: str
    parsed_payload: dict[str, Any]


def analyze_resume_record_with_coze(
    settings: Settings,
    *,
    resume_record: dict[str, object],
) -> CozeMatchResult:
    if not settings.coze_api_token:
        raise RuntimeError("COZE_API_TOKEN is not configured")
    if not settings.coze_workflow_id:
        raise RuntimeError("COZE_WORKFLOW_ID is not configured")

    LOGGER.info(
        "Starting Coze analysis",
        extra={
            "event": "coze_analysis_start",
            "context": {
                "record_id": resume_record.get("id"),
                "candidate_name": resume_record.get("candidate_name"),
                "workflow_id": settings.coze_workflow_id,
            },
        },
    )
    coze = Coze(
        auth=TokenAuth(token=settings.coze_api_token),
        base_url=settings.coze_api_base_url,
    )
    last_error: Exception | None = None
    for attempt in range(1, COZE_MAX_ATTEMPTS + 1):
        try:
            workflow = coze.workflows.runs.create(
                workflow_id=settings.coze_workflow_id,
                parameters={"resume_record": resume_record},
            )
            raw_data = workflow.data or ""
            parsed_payload = _parse_workflow_data(raw_data)
            validation_errors = _validate_coze_payload(parsed_payload)
            if validation_errors:
                last_error = CozePayloadValidationError("; ".join(validation_errors))
                LOGGER.warning(
                    "Coze analysis payload validation failed",
                    extra={
                        "event": "coze_payload_validation_retry",
                        "context": {
                            "record_id": resume_record.get("id"),
                            "candidate_name": resume_record.get("candidate_name"),
                            "attempt": attempt,
                            "validation_errors": validation_errors,
                        },
                    },
                )
                if attempt < COZE_MAX_ATTEMPTS:
                    time.sleep(attempt * 2)
                    continue
                return _build_needs_review_result(raw_data=raw_data, reason=str(last_error))
            break
        except CozeAPIError as exc:
            last_error = exc
            LOGGER.warning(
                "Coze analysis request failed",
                extra={
                    "event": "coze_analysis_retry",
                    "context": {
                        "record_id": resume_record.get("id"),
                        "candidate_name": resume_record.get("candidate_name"),
                        "attempt": attempt,
                        "code": exc.code,
                        "message": exc.msg,
                    },
                },
            )
            if exc.code in COZE_RETRYABLE_CODES and attempt >= COZE_MAX_ATTEMPTS:
                return _build_needs_review_result(
                    raw_data="",
                    reason=(
                        "Coze API 连续返回临时内部错误，"
                        f"code={exc.code}, message={exc.msg}"
                    ),
                    source="api_error",
                )
            if exc.code not in COZE_RETRYABLE_CODES:
                raise
            time.sleep(attempt * 2)
    else:
        raise RuntimeError("Coze workflow did not return a result") from last_error

    match_result = _normalize_match_result(parsed_payload)
    match_score = _normalize_match_score(parsed_payload)
    score_breakdown = _normalize_score_breakdown(parsed_payload)
    analysis_reason = _normalize_string_field(parsed_payload, "analysis_reason")
    evidence_summary = _normalize_string_list(parsed_payload, "evidence_summary")
    matched = bool(parsed_payload.get("beauty_relevant") is True or match_result == "relevant")
    LOGGER.info(
        "Completed Coze analysis",
        extra={
            "event": "coze_analysis_complete",
            "context": {
                "record_id": resume_record.get("id"),
                "candidate_name": resume_record.get("candidate_name"),
                "matched": matched,
                "match_result": match_result,
            },
        },
    )
    return CozeMatchResult(
        matched=matched,
        match_result=match_result,
        match_score=match_score,
        score_breakdown=score_breakdown,
        analysis_reason=analysis_reason,
        evidence_summary=evidence_summary,
        raw_data=raw_data,
        parsed_payload=parsed_payload,
    )


def _parse_workflow_data(raw_data: str) -> dict[str, Any]:
    if not raw_data:
        return {}
    try:
        payload = json.loads(raw_data)
    except json.JSONDecodeError:
        return {}
    if isinstance(payload, dict):
        return payload
    return {}


class CozePayloadValidationError(ValueError):
    """Raised when Coze returns syntactically valid but unsafe analysis JSON."""


def _validate_coze_payload(payload: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if not payload:
        return ["payload is empty or not a JSON object"]
    if not isinstance(payload.get("beauty_relevant"), bool):
        errors.append("beauty_relevant must be boolean")
    match_result = _normalize_match_result(payload)
    if match_result not in VALID_MATCH_RESULTS:
        errors.append("match_result must be relevant, not_relevant, or needs_review")
    if not _normalize_string_field(payload, "analysis_reason"):
        errors.append("analysis_reason must be a non-empty string")
    if not isinstance(payload.get("evidence_summary"), list):
        errors.append("evidence_summary must be an array")
    score = _normalize_match_score(payload)
    if score is not None and not 0 <= score <= 100:
        errors.append("match_score must be between 0 and 100")
    score_breakdown = payload.get("score_breakdown")
    if score_breakdown is not None and not isinstance(score_breakdown, dict):
        errors.append("score_breakdown must be an object when present")
    return errors


def _build_needs_review_result(
    *,
    raw_data: str,
    reason: str,
    source: str = "payload_validation",
) -> CozeMatchResult:
    analysis_reason = f"需要人工复核：Coze 输出结构校验失败，系统未使用该结果直接排序。{reason}"
    if source == "api_error":
        analysis_reason = f"需要人工复核：Coze API 临时异常，系统未进行自动匹配判断。{reason}"
    payload: dict[str, Any] = {
        "beauty_relevant": False,
        "match_result": "needs_review",
        "match_score": None,
        "score_breakdown": {},
        "analysis_reason": analysis_reason,
        "evidence_summary": [],
    }
    return CozeMatchResult(
        matched=False,
        match_result="needs_review",
        match_score=None,
        score_breakdown={},
        analysis_reason=analysis_reason,
        evidence_summary=[],
        raw_data=raw_data,
        parsed_payload=payload,
    )


def _normalize_match_result(payload: dict[str, Any]) -> str | None:
    value = payload.get("match_result")
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized or None


def _normalize_match_score(payload: dict[str, Any]) -> int | None:
    value = payload.get("match_score")
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if 0 <= value <= 100 else None
    if isinstance(value, float) and value.is_integer():
        normalized = int(value)
        return normalized if 0 <= normalized <= 100 else None
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.isdigit():
            normalized = int(stripped)
            return normalized if 0 <= normalized <= 100 else None
    return None


def _normalize_score_breakdown(payload: dict[str, Any]) -> dict[str, int]:
    value = payload.get("score_breakdown")
    if not isinstance(value, dict):
        return {}
    result: dict[str, int] = {}
    for key, raw in value.items():
        if not isinstance(key, str):
            continue
        normalized = _normalize_match_score({"match_score": raw})
        if normalized is not None:
            result[key] = normalized
    return result


def _normalize_string_field(payload: dict[str, Any], field_name: str) -> str | None:
    value = payload.get(field_name)
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized or None


def _normalize_string_list(payload: dict[str, Any], field_name: str) -> list[str]:
    value = payload.get(field_name)
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for item in value:
        if isinstance(item, str):
            normalized = item.strip()
            if normalized:
                result.append(normalized)
    return result
