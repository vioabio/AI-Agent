from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict


class DashboardResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    actionable: int
    pending_review: int
    recommended_contact: int
    contacted: int
    interviewing: int
    rejected: int
    hired: int


class LiveReportResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    ai_relevant: int
    ai_needs_review: int
    ai_not_relevant: int
    actionable_pending: int
    hr_status_counts: dict[str, int]
    feedback_candidates_in_window: int
    generated_at: datetime


class BatchResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    batch_id: str
    started_at: datetime
    ended_at: datetime
    label: str


class BatchListResponse(BaseModel):
    items: list[BatchResponse]
