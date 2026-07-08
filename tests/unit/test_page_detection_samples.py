from pathlib import Path

import pytest

from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector


@pytest.mark.parametrize(
    ("sample_path", "expected_state"),
    [
        ("outputs/navigation_signatures/01_current_page/hierarchy.xml", PageState.RESULT_LIST),
        ("outputs/navigation_signatures/02_search_entry/hierarchy.xml", PageState.SEARCH_ENTRY),
        ("outputs/navigation_signatures/03_result_list/hierarchy.xml", PageState.RESULT_LIST),
        ("outputs/control_explorer/page_04_empty_result/hierarchy.xml", PageState.EMPTY_RESULT),
        ("outputs/navigation_signatures/04_candidate_profile/hierarchy.xml", PageState.CANDIDATE_PROFILE),
    ],
)
def test_page_detector_matches_saved_samples(sample_path: str, expected_state: PageState) -> None:
    detector = build_page_detector()
    result = detector.detect_from_xml(Path(sample_path))

    assert result.state == expected_state
