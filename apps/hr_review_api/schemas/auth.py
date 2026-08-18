from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from apps.hr_review_api.domain.access_policy import UserRole


class LoginRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    username: str = Field(min_length=1, max_length=128)
    password: str = Field(min_length=8, max_length=256)


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    display_name: str
    role: UserRole
    allowed_job_profile_ids: frozenset[str]
    can_view_sensitive: bool


class LoginResponse(BaseModel):
    user: UserResponse
    expires_at: datetime
