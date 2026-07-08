from __future__ import annotations

from dataclasses import dataclass

from liepin_rpa.page_detection.enums import PageState


@dataclass(slots=True)
class SelectorCandidate:
    attributes: dict[str, str]


@dataclass(slots=True)
class SignatureRule:
    name: str
    all_of: list[SelectorCandidate]
    any_of: list[SelectorCandidate]


@dataclass(slots=True)
class DetectionResult:
    state: PageState
    matched_signature: str | None
