from __future__ import annotations

import random
import time
from xml.etree import ElementTree

from liepin_rpa.device.client import DeviceClient
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector
from liepin_rpa.task_generation.models import SearchTaskSeed
from liepin_rpa.ui_controls import (
    find_clickable_labeled_center,
    has_result_filter_bar,
    find_search_box,
    find_search_submit,
    node_label,
    parse_bounds,
)

SEARCH_INTERRUPTION_DISMISS_LABELS = (
    "暂不开启",
    "以后再说",
    "我知道了",
    "稍后再说",
    "暂不",
    "关闭",
    "跳过",
)
LOGGER = get_logger(__name__)
UNKNOWN_RESET_PROBE_LIMIT = 3
UNKNOWN_NAVIGATION_PROBE_LIMIT = 4


class SearchFlowError(RuntimeError):
    """Raised when the search flow cannot continue safely."""


class SearchPageNavigator:
    def __init__(
        self,
        device: DeviceClient,
        detector=None,
        settle_seconds: float = 3.0,
    ) -> None:
        self.device = device
        self.detector = detector or build_page_detector()
        self.settle_seconds = settle_seconds

    def navigate_to_search_results(self, seed: SearchTaskSeed) -> PageState:
        self._log(
            "search_seed_navigation_start",
            "Starting navigation for search seed",
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
        )
        self._reset_to_search_surface()
        unknown_probe_count = 0
        for _ in range(10):
            hierarchy_text = self.device.dump_hierarchy_text()
            detection = self.detector.detect_from_xml_text(hierarchy_text)
            root = self._parse_xml_text(hierarchy_text)
            self._log(
                "search_page_detected",
                "Detected page while navigating to search results",
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
                page_state=detection.state.value,
            )

            if self.dismiss_interruption_if_present(root):
                self._wait(1.5)
                continue
            if detection.state == PageState.CANDIDATE_PROFILE:
                self.device.press_back()
                self._wait()
                continue
            if self._looks_like_loading_result_context(root):
                self._wait()
                continue
            if detection.state == PageState.UNKNOWN:
                unknown_probe_count += 1
                if unknown_probe_count >= UNKNOWN_NAVIGATION_PROBE_LIMIT:
                    break
                self._wait()
                continue
            if detection.state in (PageState.RISK_CONTROL, PageState.NETWORK_ERROR):
                raise SearchFlowError(
                    "Unable to reach search result page because a blocking page appeared, "
                    f"current state: {detection.state.value}"
                )
            if detection.state in (
                PageState.HOME_RECOMMEND,
                PageState.RESULT_LIST,
                PageState.EMPTY_RESULT,
            ):
                unknown_probe_count = 0
                self.tap_search_entry(root)
                self._wait()
                continue
            if detection.state in (PageState.SEARCH_ENTRY, PageState.SEARCH_CONDITION):
                unknown_probe_count = 0
                self.submit_exact_search(seed, root)
                continue
            raise SearchFlowError(
                "Expected to start from home, result list, empty-result page, or search page, "
                f"got: {detection.state.value}"
            )

        hierarchy_text = self.device.dump_hierarchy_text()
        detection = self.detector.detect_from_xml_text(hierarchy_text)
        if detection.state in (PageState.RESULT_LIST, PageState.EMPTY_RESULT):
            self._log(
                "search_seed_navigation_complete",
                "Reached search result context",
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
                page_state=detection.state.value,
            )
            return detection.state
        raise SearchFlowError(
            f"Unable to reach search result page for keyword `{seed.effective_query}`. "
            f"Current state: {detection.state.value}"
        )

    def _reset_to_search_surface(self) -> None:
        unknown_probe_count = 0
        for _ in range(10):
            hierarchy_text = self.device.dump_hierarchy_text()
            detection = self.detector.detect_from_xml_text(hierarchy_text)
            root = self._parse_xml_text(hierarchy_text)
            self._log(
                "search_surface_reset_probe",
                "Checking page before resetting to search surface",
                page_state=detection.state.value,
            )

            if self.dismiss_interruption_if_present(root):
                self._wait(1.0)
                continue
            if detection.state == PageState.CANDIDATE_PROFILE:
                self._log(
                    "search_surface_reset_back",
                    "Returning from candidate profile to continue search reset",
                    page_state=detection.state.value,
                )
                self.device.press_back()
                self._wait()
                continue
            if detection.state in (PageState.RESULT_LIST, PageState.EMPTY_RESULT):
                self._log(
                    "search_surface_reset_back",
                    "Returning from result page to continue search reset",
                    page_state=detection.state.value,
                )
                self.device.press_back()
                self._wait()
                continue
            if detection.state in (PageState.HOME_RECOMMEND, PageState.SEARCH_ENTRY, PageState.SEARCH_CONDITION):
                self._log(
                    "search_surface_ready",
                    "Search surface is ready for a new keyword",
                    page_state=detection.state.value,
                )
                return
            if self._looks_like_loading_result_context(root):
                self._wait()
                continue
            if detection.state == PageState.UNKNOWN:
                unknown_probe_count += 1
                if unknown_probe_count >= UNKNOWN_RESET_PROBE_LIMIT:
                    raise SearchFlowError(
                        "Unable to reset to search surface before next keyword, "
                        "current state: unknown"
                    )
                self._wait()
                continue
            if detection.state in (PageState.RISK_CONTROL, PageState.NETWORK_ERROR):
                raise SearchFlowError(
                    "Unable to reset to search surface before next keyword, "
                    f"current state: {detection.state.value}"
                )
            raise SearchFlowError(
                "Unable to reset to search surface before next keyword, "
                f"current state: {detection.state.value}"
            )

        raise SearchFlowError("Unable to reset to search surface before next keyword")

    def ensure_result_context(self, seed: SearchTaskSeed) -> PageState:
        last_search_condition_count = 0
        for _ in range(7):
            hierarchy_text = self.device.dump_hierarchy_text()
            detection = self.detector.detect_from_xml_text(hierarchy_text)
            root = self._parse_xml_text(hierarchy_text)
            self._log(
                "result_context_probe",
                "Ensuring result context before filtering",
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
                page_state=detection.state.value,
            )

            if self.dismiss_interruption_if_present(root):
                self._wait()
                continue
            if detection.state in (PageState.RESULT_LIST, PageState.EMPTY_RESULT):
                return detection.state
            if detection.state == PageState.CANDIDATE_PROFILE:
                self.device.press_back()
                self._wait()
                continue
            if self._looks_like_loading_result_context(root):
                self._wait()
                continue
            if detection.state in (PageState.HOME_RECOMMEND, PageState.SEARCH_ENTRY):
                self.tap_search_entry(root)
                self._wait()
                continue
            if detection.state == PageState.SEARCH_CONDITION:
                last_search_condition_count += 1
                if last_search_condition_count >= 2:
                    self._log(
                        "result_context_search_condition_backtrack",
                        "Search condition page persisted, backing out once before resubmitting search",
                        brand_keyword=seed.brand_keyword,
                        role_keyword=seed.role_keyword,
                        page_state=detection.state.value,
                    )
                    self.device.press_back()
                    self._wait()
                    continue
                self.submit_exact_search(seed, root)
                continue
            raise SearchFlowError(
                "Expected to start from result list, candidate profile, home, or search page, "
                f"got: {detection.state.value}"
            )

        raise SearchFlowError("Unable to recover back to result context before filtering")

    def dismiss_interruption_if_present(self, root: ElementTree.Element) -> bool:
        center = find_clickable_labeled_center(root, SEARCH_INTERRUPTION_DISMISS_LABELS)
        if center is not None:
            self._log(
                "search_interruption_dismiss",
                "Dismissed interruption dialog during search flow",
            )
            self.device.tap(*center)
            return True

        # Liepin's resume-share bottom sheet is rendered as an accessibility
        # overlay.  Its nodes are sometimes non-clickable, so the page
        # detector reports UNKNOWN and the normal interruption selector cannot
        # close it.  Prefer the bottom-most Cancel node and fall back to the
        # stable bottom-sheet action position when the node has no bounds.
        labels = [node_label(node) for node in root.iter("node")]
        share_markers = ("温馨提示", "复制链接", "招聘目的")
        if "取消" not in labels or not any(marker in " ".join(labels) for marker in share_markers):
            return False
        cancel_nodes = []
        for node in root.iter("node"):
            if node_label(node) != "取消":
                continue
            bounds = node.attrib.get("bounds", "")
            try:
                parsed = parse_bounds(bounds)
            except (TypeError, ValueError):
                parsed = None
            if parsed is not None:
                cancel_nodes.append(parsed)
        if cancel_nodes:
            cancel_nodes.sort(key=lambda item: item.y1, reverse=True)
            center = cancel_nodes[0].center
        else:
            try:
                info = self.device.device_info()
                width = int(info.get("displayWidth") or info.get("width") or 1080)
                height = int(info.get("displayHeight") or info.get("height") or 2388)
            except Exception:
                width, height = 1080, 2388
            center = (width // 2, int(height * 0.90))
        self._log(
            "share_sheet_dismiss",
            "Dismissed Liepin resume-share bottom sheet before continuing search",
        )
        self.device.tap(*center)
        return True

    def tap_search_entry(self, root: ElementTree.Element) -> None:
        center = find_search_box(root)
        if center is None:
            raise SearchFlowError("Search box control not found on current page")
        self._log(
            "search_entry_tap",
            "Tapped search entry control",
            tap_x=center[0],
            tap_y=center[1],
        )
        self.device.tap(*center)

    def submit_exact_search(
        self,
        seed: SearchTaskSeed,
        root: ElementTree.Element,
    ) -> None:
        query_text = seed.effective_query
        search_box_center = find_search_box(root)
        if search_box_center is None:
            raise SearchFlowError("Search box control not found on search page")

        self._log(
            "search_query_submit_start",
            "Submitting exact search query",
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            query_text=query_text,
        )
        self.device.tap(*search_box_center)
        self._wait(1.0)
        self.device.send_keys(query_text, clear=True)
        self._wait(1.0)

        refreshed_root = self._parse_xml_text(self.device.dump_hierarchy_text())
        submit_center = find_search_submit(refreshed_root)
        if submit_center is None:
            raise SearchFlowError("Search submit control not found on search page")

        self._log(
            "search_submit_tap",
            "Tapped search submit control",
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            tap_x=submit_center[0],
            tap_y=submit_center[1],
        )
        self.device.tap(*submit_center)
        self._wait(3.5)

    @staticmethod
    def _parse_xml_text(xml_text: str) -> ElementTree.Element:
        return ElementTree.fromstring(xml_text)

    @staticmethod
    def _looks_like_loading_result_context(root: ElementTree.Element) -> bool:
        return has_result_filter_bar(root) and find_search_box(root) is not None

    def _wait(self, seconds: float | None = None) -> None:
        base_seconds = random.uniform(self.settle_seconds, 5.0)
        target_seconds = base_seconds if seconds is None else max(base_seconds, seconds)
        time.sleep(target_seconds)

    @staticmethod
    def _log(event: str, message: str, **context: object) -> None:
        LOGGER.info(
            message,
            extra={
                "event": event,
                "context": context,
            },
        )
