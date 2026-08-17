from pathlib import Path

import pytest

from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector

FIXTURES_DIR = Path(__file__).parents[1] / "fixtures"

@pytest.mark.parametrize(
    ("sample_path", "expected_state"),
    [
        ("result_list.xml", PageState.RESULT_LIST),
        ("search_entry.xml", PageState.SEARCH_ENTRY),
        ("empty_result.xml", PageState.EMPTY_RESULT),
        ("load_failed_result.xml", PageState.EMPTY_RESULT),
        ("candidate_profile.xml", PageState.CANDIDATE_PROFILE),
    ],
)
def test_page_detector_matches_saved_samples(sample_path: str, expected_state: PageState) -> None:
    detector = build_page_detector()
    result = detector.detect_from_xml(FIXTURES_DIR / sample_path)

    assert result.state == expected_state
