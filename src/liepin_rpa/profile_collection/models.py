from __future__ import annotations

from dataclasses import dataclass


@dataclass(slots=True)
class ProfileCollectionIteration:
    iteration: int
    screenshot_path: str
    hierarchy_path: str
    detection_state: str
    matched_signature: str | None
    clicked_expand_labels: list[str]
    clicked_expand_bounds: list[str]
    swipe_performed: bool
    visible_text_count: int
    new_text_count: int
    stagnant_swipe_count: int
    visible_text_lines: list[str]


@dataclass(slots=True)
class ProfileCollectionReport:
    report_path: str
    debug_report_path: str
    raw_text_path: str
    markdown_path: str
    total_iterations: int
    total_swipes: int
    total_expand_clicks: int
    stagnant_swipe_limit: int
    reached_bottom: bool
    collected_text_lines: list[str]
    sections: dict[str, list[str]]
    iterations: list[ProfileCollectionIteration]
