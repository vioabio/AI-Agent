from __future__ import annotations

import hashlib
import json
import random
import time
from typing import Any

from cozepy import Coze, TokenAuth
from cozepy.exception import CozeAPIError
from cozepy.request import SyncHTTPClient

from liepin_rpa.analysis_contract import (
    ANALYSIS_TRANSIENT_CACHE_TTL,
    DEFAULT_ANALYSIS_CONTRACT,
    EXPECTED_SCORE_BREAKDOWN_KEYS,
    AnalysisContract,
    ResumeAnalysisResult,
    analysis_result_from_payload,
    build_needs_review_result,
    is_transient_analysis_review,
    normalize_match_result,
    normalize_match_score,
    normalize_score_breakdown,
    normalize_string_field,
    normalize_string_list,
    parse_analysis_payload,
    transient_analysis_cache_retry_due,
    validate_analysis_payload,
)
from liepin_rpa.config import Settings
from liepin_rpa.logging_utils import get_logger

LOGGER = get_logger(__name__)
COZE_MAX_ATTEMPTS = 4
COZE_TRANSIENT_CACHE_TTL = ANALYSIS_TRANSIENT_CACHE_TTL
COZE_RETRYABLE_CODES = {429, 5000, 5001, 5002, 5003, 5004, 5005}
COZE_NEEDS_REVIEW_CODES = {4000}
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


CozeMatchResult = ResumeAnalysisResult


class CozeWorkflowExecutionFailure(RuntimeError):
    """One configured workflow exhausted its safe retry policy."""

    def __init__(
        self,
        reason: str,
        *,
        raw_data: str = "",
        source: str = "api_error",
    ) -> None:
        super().__init__(reason)
        self.raw_data = raw_data
        self.source = source


def analyze_resume_record_with_coze(
    settings: Settings,
    *,
    resume_record: dict[str, object],
    analysis_contract: AnalysisContract = DEFAULT_ANALYSIS_CONTRACT,
) -> CozeMatchResult:
    if not settings.coze_api_token:
        raise RuntimeError("COZE_API_TOKEN is not configured")
    workflow_ids = _configured_workflow_ids(settings)
    if not workflow_ids:
        raise RuntimeError("COZE_WORKFLOW_ID is not configured")

    LOGGER.info(
        "Starting Coze analysis",
        extra={
            "event": "coze_analysis_start",
            "context": {
                "record_id": resume_record.get("id"),
                "candidate_name": resume_record.get("candidate_name"),
                "workflow_id": workflow_ids[0],
                "backup_workflow_configured": len(workflow_ids) > 1,
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
    last_failure: CozeWorkflowExecutionFailure | None = None
    for workflow_index, workflow_id in enumerate(workflow_ids):
        workflow_role = "primary" if workflow_index == 0 else "backup"
        try:
            return _run_workflow_with_retries(
                coze=coze,
                workflow_id=workflow_id,
                workflow_role=workflow_role,
                resume_record=resume_record,
                parameters=parameters,
                request_fingerprint=request_fingerprint,
                analysis_contract=analysis_contract,
            )
        except CozeWorkflowExecutionFailure as exc:
            last_failure = exc
            if workflow_index + 1 < len(workflow_ids):
                LOGGER.warning(
                    "Primary Coze workflow failed; switching to configured backup",
                    extra={
                        "event": "coze_workflow_failover",
                        "context": {
                            "record_id": resume_record.get("id"),
                            "candidate_name": resume_record.get("candidate_name"),
                            "failed_workflow_id": workflow_id,
                            "backup_workflow_id": workflow_ids[workflow_index + 1],
                            "failure_reason": str(exc),
                            "failure_source": exc.source,
                            "coze_request_fingerprint": request_fingerprint,
                        },
                    },
                )
                continue
            LOGGER.error(
                "All configured Coze workflows failed",
                extra={
                    "event": "coze_all_workflows_failed",
                    "context": {
                        "record_id": resume_record.get("id"),
                        "candidate_name": resume_record.get("candidate_name"),
                        "workflow_id": workflow_id,
                        "failure_reason": str(exc),
                        "failure_source": exc.source,
                        "coze_request_fingerprint": request_fingerprint,
                    },
                },
            )

    if last_failure is None:  # pragma: no cover - guarded by workflow_ids
        raise RuntimeError("Coze workflow did not return a result")
    return _build_needs_review_result(
        raw_data=last_failure.raw_data,
        reason=str(last_failure),
        source=last_failure.source,
    )


def _configured_workflow_ids(settings: Settings) -> list[str]:
    primary = str(settings.coze_workflow_id or "").strip()
    backup = str(getattr(settings, "coze_backup_workflow_id", None) or "").strip()
    if not primary:
        return []
    if backup and backup != primary:
        return [primary, backup]
    return [primary]


def _run_workflow_with_retries(
    *,
    coze: Coze,
    workflow_id: str,
    workflow_role: str,
    resume_record: dict[str, object],
    parameters: dict[str, object],
    request_fingerprint: str,
    analysis_contract: AnalysisContract,
) -> CozeMatchResult:
    last_error: Exception | None = None
    for attempt in range(1, COZE_MAX_ATTEMPTS + 1):
        attempt_started_at = time.monotonic()
        try:
            workflow = coze.workflows.runs.create(
                workflow_id=workflow_id,
                parameters=parameters,
            )
            raw_data = workflow.data or ""
            parsed_payload = _parse_workflow_data(raw_data)
            validation_errors = _validate_coze_payload(
                parsed_payload,
                contract=analysis_contract,
            )
            if validation_errors:
                last_error = CozePayloadValidationError("; ".join(validation_errors))
                LOGGER.warning(
                    "Coze analysis payload validation failed",
                    extra={
                        "event": "coze_payload_validation_retry",
                        "context": {
                            "record_id": resume_record.get("id"),
                            "candidate_name": resume_record.get("candidate_name"),
                            "workflow_id": workflow_id,
                            "workflow_role": workflow_role,
                            "attempt": attempt,
                            "coze_request_fingerprint": request_fingerprint,
                            "validation_errors": validation_errors,
                        },
                    },
                )
                if attempt < COZE_MAX_ATTEMPTS:
                    time.sleep(attempt * 2)
                    continue
                raise CozeWorkflowExecutionFailure(
                    str(last_error),
                    raw_data=raw_data,
                    source="payload_validation",
                )
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
                        "workflow_id": workflow_id,
                        "workflow_role": workflow_role,
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
            if not should_retry_or_review or attempt >= COZE_MAX_ATTEMPTS:
                raise CozeWorkflowExecutionFailure(
                    "Coze API 连续返回异常，"
                    f"code={exc.code}, message={exc.msg}",
                    source="api_error",
                ) from exc
            time.sleep(min(2**attempt, 15) + random.uniform(0, 0.75))
    else:  # pragma: no cover - every terminal path raises or returns
        raise CozeWorkflowExecutionFailure(
            "Coze workflow did not return a result",
            source="api_error",
        ) from last_error

    result = analysis_result_from_payload(parsed_payload, raw_data=raw_data)
    LOGGER.info(
        "Completed Coze analysis",
        extra={
            "event": "coze_analysis_complete",
            "context": {
                "record_id": resume_record.get("id"),
                "candidate_name": resume_record.get("candidate_name"),
                "workflow_id": workflow_id,
                "workflow_role": workflow_role,
                "matched": result.matched,
                "match_result": result.match_result,
            },
        },
    )
    return result


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
        original_value = (
            _coze_string_value(original_record.get(field_name))
            .replace("\x00", "")
            .strip()
        )
        if field_name in COZE_COMPACT_WHITESPACE_FIELDS:
            original_value = " ".join(original_value.split())
        if len(original_value) > max_chars:
            truncated_fields.append(field_name)
    return {
        "coze_field_lengths": field_lengths,
        "coze_truncated_fields": truncated_fields,
    }


_parse_workflow_data = parse_analysis_payload


class CozePayloadValidationError(ValueError):
    """Raised when Coze returns syntactically valid but unsafe analysis JSON."""


_validate_coze_payload = validate_analysis_payload


def _build_needs_review_result(
    *,
    raw_data: str,
    reason: str,
    source: str = "payload_validation",
) -> CozeMatchResult:
    return build_needs_review_result(
        raw_data=raw_data,
        reason=reason,
        provider_label="Coze",
        source=source,
    )


is_transient_coze_api_review = is_transient_analysis_review


transient_coze_cache_retry_due = transient_analysis_cache_retry_due
_normalize_match_result = normalize_match_result
_normalize_match_score = normalize_match_score
_normalize_score_breakdown = normalize_score_breakdown
_normalize_string_field = normalize_string_field
_normalize_string_list = normalize_string_list
