from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from apps.hr_review_api.schemas.candidates import AnalysisSnapshotResponse, EvidenceReferenceResponse


class AnalysisTaskResponse(BaseModel):
    job_id: str
    candidate_fingerprint: str
    candidate_name: str
    job_profile_id: str
    batch_id: str
    collection_completed: bool
    status: Literal["pending", "running", "retry_wait", "succeeded", "failed", "cancelled", "superseded"]
    attempts: int
    max_attempts: int
    lease_token: int
    last_error_code: str | None
    source_type: Literal["synthetic"]
    available_at: datetime
    deadline_at: datetime
    can_retry: bool


class AnalysisTaskPageResponse(BaseModel):
    enabled: bool
    items: list[AnalysisTaskResponse]
    total: int
    page: int
    page_size: int


class ShadowResultResponse(BaseModel):
    beauty_relevant: bool
    match_result: Literal["relevant", "needs_review", "not_relevant"]
    match_score: int
    score_breakdown: dict[str, int]
    analysis_reason: str
    evidence_summary: list[str]


class ShadowRequestReceiptResponse(BaseModel):
    state: Literal["started", "received", "http_error", "timeout", "connection_error", "invalid_response", "not_sent", "unknown"]
    started_at: datetime
    completed_at: datetime | None
    http_status: int | None
    error_code: str | None
    latency_ms: float | None
    input_tokens: int | None
    output_tokens: int | None
    reported_cost_micros: int | None


class ShadowAttemptResponse(BaseModel):
    sequence: int
    status: Literal["running", "succeeded", "failed", "abandoned", "unknown"]
    started_at: datetime
    finished_at: datetime | None
    error_code: str | None
    reserved_tokens: int
    reserved_cost_micros: int
    reported_tokens: int | None
    reported_cost_micros: int | None
    request: ShadowRequestReceiptResponse | None


class ShadowExecutionResponse(BaseModel):
    mode: Literal["synthetic_only"]
    currency: str
    price_version: str
    charged_tokens: int
    max_tokens: int
    charged_cost_micros: int
    max_cost_micros: int
    history_truncated: bool
    attempts: list[ShadowAttemptResponse]
    tool_rounds: list["ShadowToolRoundResponse"] = Field(default_factory=list)


class ShadowToolRoundResponse(BaseModel):
    sequence: int
    attempt_sequence: int | None
    state: Literal["reserved", "started", "received", "error", "unknown"]
    reserved_tokens: int
    reserved_cost_micros: int
    input_tokens: int | None
    output_tokens: int | None
    reported_cost_micros: int | None
    started_at: datetime
    finished_at: datetime | None


class AnalysisTaskDetailResponse(BaseModel):
    task: AnalysisTaskResponse
    snapshot: AnalysisSnapshotResponse | None
    result: ShadowResultResponse | None
    evidence_refs: list[EvidenceReferenceResponse]
    result_status: Literal["pending", "verified", "invalid"]
    execution: ShadowExecutionResponse | None = None


class RetryAnalysisTaskRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    lease_token: int = Field(ge=0, strict=True)
