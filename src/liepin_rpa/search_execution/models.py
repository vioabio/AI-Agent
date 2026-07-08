from __future__ import annotations

from dataclasses import dataclass


@dataclass(slots=True)
class SearchStepCapture:
    step_name: str
    state: str
    matched_signature: str | None
    package: str | None
    activity: str | None
    screenshot_path: str
    hierarchy_path: str
    signatures: list[str]


@dataclass(slots=True)
class SearchExecutionReport:
    query_text: str
    brand_keyword: str
    role_keyword: str
    report_path: str
    steps: list[SearchStepCapture]
