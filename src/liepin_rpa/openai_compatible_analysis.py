from __future__ import annotations

import hashlib
import json
import random
import time

from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI

from liepin_rpa.analysis_contract import (
    DEFAULT_ANALYSIS_CONTRACT,
    AnalysisContract,
    ResumeAnalysisResult,
    analysis_result_from_payload,
    build_needs_review_result,
    parse_analysis_payload,
    validate_analysis_payload,
)
from liepin_rpa.config import Settings
from liepin_rpa.logging_utils import get_logger

LOGGER = get_logger(__name__)
# The gateway can briefly reject an otherwise valid key while rotating the
# upstream session.  Treat 401 like the other transient statuses so one
# blip does not turn into a failed keyword and trip the workflow circuit
# breaker; a persistent 401 still ends in the normal needs_review result.
RETRYABLE_STATUS_CODES = {401, 408, 409, 429}


def analyze_resume_record_with_openai_compatible(
    settings: Settings,
    *,
    resume_record: dict[str, object],
    system_prompt: str,
    analysis_contract: AnalysisContract = DEFAULT_ANALYSIS_CONTRACT,
) -> ResumeAnalysisResult:
    api_key = settings.llm_api_key.get_secret_value() if settings.llm_api_key else ""
    if not api_key:
        raise RuntimeError("LLM_API_KEY is not configured")
    if not settings.llm_model:
        raise RuntimeError("LLM_MODEL is not configured")
    if not system_prompt.strip():
        raise RuntimeError("Resume analysis system prompt is empty")

    request_json = json.dumps(resume_record, ensure_ascii=False, sort_keys=True)
    request_fingerprint = hashlib.sha256(request_json.encode("utf-8")).hexdigest()[:16]
    client = OpenAI(
        api_key=api_key,
        base_url=settings.llm_base_url.rstrip("/") + "/",
        timeout=settings.llm_timeout_seconds,
        max_retries=0,
    )
    last_error: Exception | None = None
    last_raw_data = ""
    deadline = time.monotonic() + settings.llm_total_timeout_seconds

    for attempt in range(1, settings.llm_max_attempts + 1):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            last_error = TimeoutError("Analysis total time budget exhausted")
            break
        try:
            completion = client.chat.completions.create(
                model=settings.llm_model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": request_json},
                ],
                response_format={"type": "json_object"},
                timeout=min(settings.llm_timeout_seconds, remaining),
            )
            if time.monotonic() >= deadline:
                last_error = TimeoutError("Analysis response arrived after the total deadline")
                break
            last_raw_data = completion.choices[0].message.content or ""
            payload = parse_analysis_payload(last_raw_data)
            validation_errors = validate_analysis_payload(
                payload,
                contract=analysis_contract,
            )
            if not validation_errors:
                result = analysis_result_from_payload(payload, raw_data=last_raw_data)
                LOGGER.info(
                    "Completed OpenAI-compatible resume analysis",
                    extra={
                        "event": "llm_analysis_complete",
                        "context": {
                            "model": settings.llm_model,
                            "attempt": attempt,
                            "request_fingerprint": request_fingerprint,
                            "match_result": result.match_result,
                        },
                    },
                )
                return result
            last_error = ValueError("; ".join(validation_errors))
            _log_retry(
                settings=settings,
                attempt=attempt,
                request_fingerprint=request_fingerprint,
                reason="payload_validation",
                detail=str(last_error),
            )
        except (APIConnectionError, APITimeoutError) as exc:
            last_error = exc
            _log_retry(
                settings=settings,
                attempt=attempt,
                request_fingerprint=request_fingerprint,
                reason="transport_error",
                detail=type(exc).__name__,
            )
        except APIStatusError as exc:
            if not _is_retryable_status(exc.status_code, retry_auth_errors=settings.llm_retry_auth_errors):
                result = build_needs_review_result(
                    raw_data=last_raw_data, reason=f"HTTP {exc.status_code}",
                    provider_label="模型", source="api_rejected",
                )
                result.error_code = f"http_{exc.status_code}"
                return result
            last_error = exc
            _log_retry(
                settings=settings,
                attempt=attempt,
                request_fingerprint=request_fingerprint,
                reason="api_error",
                detail=f"HTTP {exc.status_code}",
            )

        if attempt < settings.llm_max_attempts:
            delay = min(2**attempt, 15) + random.uniform(0, 0.75)
            if delay >= deadline - time.monotonic():
                last_error = TimeoutError("Analysis budget cannot accommodate another retry")
                break
            time.sleep(delay)

    source = "api_error" if isinstance(
        last_error,
        (APIConnectionError, APITimeoutError, APIStatusError, TimeoutError),
    ) else "payload_validation"
    result = build_needs_review_result(
        raw_data=last_raw_data,
        reason=(
            f"HTTP {last_error.status_code}" if isinstance(last_error, APIStatusError)
            else type(last_error).__name__ if isinstance(last_error, (APIConnectionError, APITimeoutError))
            else str(last_error or "model returned no usable result")
        ),
        provider_label="模型",
        source=source,
    )
    if isinstance(last_error, TimeoutError):
        result.error_code = "deadline_exceeded"
    return result


def _is_retryable_status(status_code: int, *, retry_auth_errors: bool = True) -> bool:
    if status_code == 401:
        return retry_auth_errors
    return status_code in RETRYABLE_STATUS_CODES or status_code >= 500


def _log_retry(
    *,
    settings: Settings,
    attempt: int,
    request_fingerprint: str,
    reason: str,
    detail: str,
) -> None:
    LOGGER.warning(
        "OpenAI-compatible resume analysis attempt failed",
        extra={
            "event": "llm_analysis_retry",
            "context": {
                "model": settings.llm_model,
                "attempt": attempt,
                "max_attempts": settings.llm_max_attempts,
                "request_fingerprint": request_fingerprint,
                "reason": reason,
                "detail": detail,
            },
        },
    )
