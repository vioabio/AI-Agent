from __future__ import annotations

import hashlib
import json
import random
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, Mapping

from cozepy import Coze, TokenAuth
from cozepy.exception import CozeAPIError
from cozepy.request import SyncHTTPClient

from liepin_rpa.config import Settings
from liepin_rpa.logging_utils import get_logger

LOGGER = get_logger(__name__)
COZE_MAX_ATTEMPTS = 4
COZE_TRANSIENT_CACHE_TTL = timedelta(minutes=10)
COZE_RETRYABLE_CODES = {429, 5000, 5001, 5002, 5003, 5004, 5005}
COZE_NEEDS_REVIEW_CODES = {4000}
VALID_MATCH_RESULTS = {"relevant", "not_relevant", "needs_review"}
EXPECTED_SCORE_BREAKDOWN_KEYS = {
    "beauty_continuity",
    "industry_depth",
    "role_evidence",
}
COZE_RESUME_RECORD_FIELDS = (
    "id",
    "candidate_name",
    "source_platform",
    "search_keyword",
    "experience",
    "analysis_status",
)
COZE_FIELD_MAX_CHARS = {
    "candidate_name": 128,
    "source_platform": 64,
    "search_keyword": 512,
    "analysis_status": 64,
    "experience": 4000,
}
COZE_COMPACT_WHITESPACE_FIELDS = {"experience"}


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
        http_client=SyncHTTPClient(trust_env=False),
    )
    parameters = _build_coze_workflow_parameters(resume_record)
    request_fingerprint = hashlib.sha256(
        json.dumps(parameters, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()[:16]
    LOGGER.info(
        "Prepared Coze workflow parameters",
        extra={
            "event": "coze_parameters_prepared",
            "context": {
                "record_id": resume_record.get("id"),
                "candidate_name": resume_record.get("candidate_name"),
                "coze_request_fingerprint": request_fingerprint,
                **_summarize_coze_parameters(resume_record, parameters),
            },
        },
    )
    last_error: Exception | None = None
    for attempt in range(1, COZE_MAX_ATTEMPTS + 1):
        attempt_started_at = time.monotonic()
        try:
            workflow = coze.workflows.runs.create(
                workflow_id=settings.coze_workflow_id,
                parameters=parameters,
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
                            "coze_request_fingerprint": request_fingerprint,
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
                        "logid": exc.logid,
                        "debug_url": exc.debug_url,
                        "coze_request_fingerprint": request_fingerprint,
                        "attempt_elapsed_seconds": round(
                            time.monotonic() - attempt_started_at,
                            3,
                        ),
                    },
                },
            )
            should_retry_or_review = (
                exc.code in COZE_RETRYABLE_CODES or exc.code in COZE_NEEDS_REVIEW_CODES
            )
            if should_retry_or_review and attempt >= COZE_MAX_ATTEMPTS:
                return _build_needs_review_result(
                    raw_data="",
                    reason=(
                        "Coze API 连续返回异常，"
                        f"code={exc.code}, message={exc.msg}"
                    ),
                    source="api_error",
                )
            if not should_retry_or_review:
                raise
            time.sleep(min(2**attempt, 15) + random.uniform(0, 0.75))
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


def _build_coze_workflow_parameters(resume_record: dict[str, object]) -> dict[str, object]:
    normalized_record = {
        field_name: _coze_field_value(field_name, resume_record.get(field_name))
        for field_name in COZE_RESUME_RECORD_FIELDS
    }
    return {"resume_record": normalized_record}


def _coze_field_value(field_name: str, value: object) -> object:
    if field_name == "id":
        return _coze_number_value(value)
    normalized = _coze_string_value(value).replace("\x00", "").strip()
    if field_name in COZE_COMPACT_WHITESPACE_FIELDS:
        normalized = " ".join(normalized.split())
    max_chars = COZE_FIELD_MAX_CHARS.get(field_name)
    if max_chars is not None and len(normalized) > max_chars:
        return normalized[:max_chars].rstrip()
    return normalized


def _coze_number_value(value: object) -> int | float:
    if isinstance(value, bool) or value is None:
        return 0
    if isinstance(value, (int, float)):
        return value
    normalized = str(value).strip()
    try:
        return int(normalized)
    except ValueError:
        try:
            return float(normalized)
        except ValueError:
            return 0


def _coze_string_value(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _summarize_coze_parameters(
    original_record: dict[str, object],
    parameters: dict[str, object],
) -> dict[str, object]:
    resume_record = parameters.get("resume_record")
    if not isinstance(resume_record, dict):
        return {"coze_parameter_error": "resume_record is not an object"}
    field_lengths = {
        field_name: len(str(resume_record.get(field_name) or ""))
        for field_name in COZE_RESUME_RECORD_FIELDS
    }
    truncated_fields = []
    for field_name in COZE_RESUME_RECORD_FIELDS:
        max_chars = COZE_FIELD_MAX_CHARS.get(field_name)
        if max_chars is None:
            continue
        original_value = _coze_string_value(original_record.get(field_name)).replace("\x00", "").strip()
        if field_name in COZE_COMPACT_WHITESPACE_FIELDS:
            original_value = " ".join(original_value.split())
        if len(original_value) > max_chars:
            truncated_fields.append(field_name)
    return {
        "coze_field_lengths": field_lengths,
        "coze_truncated_fields": truncated_fields,
    }


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
    if score is None:
        errors.append("match_score must be an integer between 0 and 100")
    score_breakdown = payload.get("score_breakdown")
    if not isinstance(score_breakdown, dict):
        errors.append("score_breakdown must be an object")
    else:
        actual_keys = set(score_breakdown)
        if actual_keys != EXPECTED_SCORE_BREAKDOWN_KEYS:
            errors.append("score_breakdown must contain exactly the expected dimensions")
        normalized_breakdown = _normalize_score_breakdown(payload)
        if len(normalized_breakdown) != len(EXPECTED_SCORE_BREAKDOWN_KEYS):
            errors.append("score_breakdown values must be integers between 0 and 100")
        elif score is not None and sum(normalized_breakdown.values()) != score:
            errors.append("match_score must equal the sum of score_breakdown")
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


def is_transient_coze_api_review(result: CozeMatchResult | None) -> bool:
    return bool(
        result is not None
        and result.match_result == "needs_review"
        and "Coze API 临时异常" in (result.analysis_reason or "")
    )


def transient_coze_cache_retry_due(
    result: CozeMatchResult | None,
    cache_record: Mapping[str, object] | None,
    *,
    now: datetime | None = None,
    ttl: timedelta = COZE_TRANSIENT_CACHE_TTL,
) -> bool:
    """Return true when a cached transient API failure should be analyzed again."""
    if not is_transient_coze_api_review(result):
        return False
    if cache_record is None:
        return True
    cached_at = _coerce_cache_datetime(
        cache_record.get("updated_at") or cache_record.get("created_at")
    )
    if cached_at is None:
        return True
    resolved_now = _coerce_cache_datetime(now or datetime.now())
    if resolved_now is None:
        return True
    return resolved_now - cached_at >= ttl


def _coerce_cache_datetime(value: object) -> datetime | None:
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
