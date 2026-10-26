from __future__ import annotations

from pathlib import Path

from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.models import DetectionResult, SignatureRule
from liepin_rpa.page_detection.selectors import SelectorRepository
from liepin_rpa.page_detection.xml_utils import any_node_matches, load_xml_root, load_xml_root_from_text
from liepin_rpa.ui_controls import (
    is_candidate_profile,
    is_empty_result,
    is_home_recommend,
    is_result_list,
    is_search_condition,
    is_search_entry,
)


class PageDetector:
    def __init__(self, selector_repository: SelectorRepository) -> None:
        self.selector_repository = selector_repository
        self.selector_signature_order: list[tuple[PageState, str]] = [
            (PageState.RISK_CONTROL, "risk_control_page"),
            (PageState.LOGIN_REQUIRED, "login_required_page"),
            (PageState.NETWORK_ERROR, "network_error_page"),
            (PageState.CHAT, "chat_page"),
            (PageState.POSITION_FILTER, "position_filter_page"),
            (PageState.JOBS_DASHBOARD, "jobs_dashboard_page"),
        ]

    def detect_from_xml(self, xml_path: Path) -> DetectionResult:
        return self.detect_from_root(load_xml_root(xml_path))

    def detect_from_xml_text(self, xml_text: str) -> DetectionResult:
        return self.detect_from_root(load_xml_root_from_text(xml_text))

    def detect_from_root(self, root) -> DetectionResult:
        for state, signature_name in self.selector_signature_order:
            rule = self.selector_repository.get_signature(signature_name)
            if self._matches(root, rule):
                return DetectionResult(state=state, matched_signature=signature_name)

        if is_candidate_profile(root):
            return DetectionResult(
                state=PageState.CANDIDATE_PROFILE,
                matched_signature="candidate_profile_page_control",
            )
        if is_empty_result(root):
            return DetectionResult(
                state=PageState.EMPTY_RESULT,
                matched_signature="empty_result_page_control",
            )
        if is_result_list(root):
            return DetectionResult(
                state=PageState.RESULT_LIST,
                matched_signature="result_list_page_control",
            )
        if is_search_condition(root):
            return DetectionResult(
                state=PageState.SEARCH_CONDITION,
                matched_signature="search_condition_page_control",
            )
        if is_home_recommend(root):
            return DetectionResult(
                state=PageState.HOME_RECOMMEND,
                matched_signature="home_recommend_page_control",
            )
        if is_search_entry(root):
            return DetectionResult(
                state=PageState.SEARCH_ENTRY,
                matched_signature="search_entry_page_control",
            )

        return DetectionResult(state=PageState.UNKNOWN, matched_signature=None)

    @staticmethod
    def _matches(root, rule: SignatureRule) -> bool:
        all_ok = all(any_node_matches(root, candidate.attributes) for candidate in rule.all_of)
        if not all_ok:
            return False
        if not rule.any_of:
            return True
        return any(any_node_matches(root, candidate.attributes) for candidate in rule.any_of)
