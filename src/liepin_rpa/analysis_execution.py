"""Execution state is separate from the model's business tri-state.

Text matching is restricted to this legacy adapter until persisted execution
metadata is introduced. New adapter results always supply an explicit state.
"""
from __future__ import annotations

import re
from enum import StrEnum

from sqlalchemy import and_, func, or_


class AnalysisExecutionStatus(StrEnum):
    SUCCEEDED = "succeeded"
    TRANSIENT_ERROR = "transient_error"
    INVALID_OUTPUT = "invalid_output"
    BLOCKED = "blocked"
    LEGACY_UNKNOWN = "legacy_unknown"


LEGACY_API_FAILURE_MARKERS = ("Coze API", "API 临时异常", "API 连续返回异常")
LEGACY_INVALID_OUTPUT_MARKERS = ("输出结构校验失败",)
LEGACY_FAILURE_MARKERS = LEGACY_API_FAILURE_MARKERS + LEGACY_INVALID_OUTPUT_MARKERS


def execution_status_from_legacy_reason(reason: object) -> AnalysisExecutionStatus | None:
    value = str(reason or "")
    if any(marker in value for marker in LEGACY_API_FAILURE_MARKERS):
        return AnalysisExecutionStatus.TRANSIENT_ERROR
    if any(marker in value for marker in LEGACY_INVALID_OUTPUT_MARKERS):
        return AnalysisExecutionStatus.INVALID_OUTPUT
    return None


def has_analysis_execution_failure(
    reason: object = None, *, execution_status: object = None
) -> bool:
    # Unknown explicit values fail closed. Do not let a status typo authorize action.
    if execution_status is not None:
        return execution_status != AnalysisExecutionStatus.SUCCEEDED
    return execution_status_from_legacy_reason(reason) is not None


def is_transient_execution_failure(
    reason: object = None, *, execution_status: object = None
) -> bool:
    if execution_status is not None:
        return execution_status == AnalysisExecutionStatus.TRANSIENT_ERROR
    return execution_status_from_legacy_reason(reason) == AnalysisExecutionStatus.TRANSIENT_ERROR


def legacy_analysis_success_condition(reason_column):
    """SQLAlchemy equivalent of the legacy Python adapter, including NULL."""
    reason = func.coalesce(reason_column, "")
    return and_(*(~reason.contains(marker) for marker in LEGACY_FAILURE_MARKERS))


def analysis_success_condition(reason_column, status_column):
    return or_(
        status_column == AnalysisExecutionStatus.SUCCEEDED,
        and_(status_column.is_(None), legacy_analysis_success_condition(reason_column)),
    )


def legacy_analysis_success_sql(reason_column: str, status_column: str | None = None) -> str:
    """Only static, validated identifiers may be used by legacy raw-SQL readers."""
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*", reason_column):
        raise ValueError("Expected a qualified SQL column identifier")
    legacy = "(" + " AND ".join(
        f"COALESCE({reason_column}, '') NOT LIKE '%{marker}%'"
        for marker in LEGACY_FAILURE_MARKERS
    ) + ")"
    if status_column is None:
        return legacy
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*", status_column):
        raise ValueError("Expected a qualified SQL status column identifier")
    return f"({status_column} = 'succeeded' OR ({status_column} IS NULL AND {legacy}))"
