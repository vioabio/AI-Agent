"""Versioned observations. A list summary never becomes a full resume implicitly."""
from datetime import datetime, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from liepin_rpa.analysis_versioning import content_hash


class SourceObservation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: Literal["sourcing-observation-v1"] = "sourcing-observation-v1"
    mode: Literal["fixture", "live"]
    event_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.:-]+$")
    run_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.:-]+$")
    platform: Literal["liepin", "boss"]
    account_alias: str = Field(min_length=1, max_length=32, pattern=r"^[a-z0-9][a-z0-9_-]*$")
    job_profile_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.:-]+$")
    platform_candidate_id: str | None = Field(default=None, min_length=1, max_length=255)
    platform_resume_id: str | None = Field(default=None, min_length=1, max_length=255)
    requested_ref: str | None = Field(default=None, min_length=1, max_length=255)
    candidate_name: str = Field(min_length=1, max_length=255)
    captured_at: datetime
    cli_version: str = Field(min_length=1, max_length=64)
    adapter_version: str = Field(min_length=1, max_length=64)
    content_kind: Literal["api_text", "list_summary", "image_only"]
    completeness: Literal["full", "partial", "unknown"]
    resume_text: str = Field(default="", max_length=200000)

    @field_validator("platform_candidate_id", "platform_resume_id", "requested_ref", "candidate_name")
    @classmethod
    def nonblank_reference(cls, value):
        if value is not None and (not value.strip() or value != value.strip() or any(ord(c) < 32 for c in value)):
            raise ValueError("Expected an opaque nonblank reference or display name")
        return value

    @field_validator("captured_at")
    @classmethod
    def utc_time(cls, value):
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("Capture timestamp requires a timezone")
        return value.astimezone(timezone.utc)

    @model_validator(mode="after")
    def truthful_content(self):
        if self.content_kind != "api_text" and self.completeness == "full":
            raise ValueError("Only explicit source text can be marked full")
        if self.content_kind == "image_only" and self.resume_text:
            raise ValueError("Untranscribed images contain no resume text")
        if self.completeness == "full" and not self.resume_text.strip():
            raise ValueError("Full resume text must not be empty")
        return self

    @property
    def identity_kind(self):
        if self.platform_candidate_id:
            return "candidate_id"
        if self.platform_resume_id:
            return "resume_id"
        return "observation_only"

    @property
    def identity_key(self):
        # Account and fixture/live namespaces are deliberately conservative.
        reference = self.platform_candidate_id or self.platform_resume_id or [self.run_id, self.event_id]
        return content_hash(["source-identity-v1", self.mode, self.platform, self.account_alias,
                             self.identity_kind, reference])

    @property
    def event_key(self):
        return content_hash(["source-event-v1", self.mode, self.run_id, self.platform, self.account_alias, self.event_id])

    @property
    def analysis_readiness(self):
        if self.identity_kind == "observation_only":
            return "identity_unverified"
        return "text_ready" if self.content_kind == "api_text" and self.completeness == "full" else "insufficient_content"

    def document(self):
        return self.model_dump(mode="json")


def parse_observation(body: str | bytes):
    if not isinstance(body, (str, bytes)) or len(body.encode("utf-8") if isinstance(body, str) else body) > 1_000_000:
        raise ValueError("Observation exceeds bounded input size")
    return SourceObservation.model_validate_json(body)
