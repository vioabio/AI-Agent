from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree

from liepin_rpa.device.client import DeviceClient
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector
from liepin_rpa.task_generation.models import SearchTaskSeed
from liepin_rpa.ui_controls import find_search_box, find_search_submit

RESULT_SWIPE_START_X = 540
RESULT_SWIPE_START_Y = 1960
RESULT_SWIPE_END_X = 540
RESULT_SWIPE_END_Y = 720
RESULT_SWIPE_DURATION = 0.30


@dataclass(slots=True)
class CandidateCard:
    fingerprint: str
    raw_text: str
    bounds: str


@dataclass(slots=True)
class FilterIteration:
    iteration: int
    visible_candidates: int
    newly_screened: int
    screened_total: int
    matched_candidate: str | None
    stagnant_swipes: int
    screenshot_path: str
    hierarchy_path: str


@dataclass(slots=True)
class FilterRunReport:
    brand_keyword: str
    role_keyword: str
    screened_candidates: int
    junior_college_rejected: int
    matched: bool
    matched_candidate: str | None
    end_reason: str
    iterations: list[FilterIteration]


class ResultFilterError(RuntimeError):
    """Raised when result-page filtering cannot continue safely."""


class ResultPageFilter:
    def __init__(
        self,
        device: DeviceClient,
        detector=None,
        settle_seconds: float = 1.4,
        max_stagnant_swipes: int = 3,
    ) -> None:
        self.device = device
        self.detector = detector or build_page_detector()
        self.settle_seconds = settle_seconds
        self.max_stagnant_swipes = max_stagnant_swipes

    def run_current_result_page(
        self,
        seed: SearchTaskSeed,
        output_dir: Path,
    ) -> FilterRunReport:
        output_dir.mkdir(parents=True, exist_ok=True)
        ready_state = self._ensure_result_list_ready(seed)
        if ready_state == PageState.EMPTY_RESULT:
            return self._write_report(
                output_dir=output_dir,
                seed=seed,
                screened_candidates=0,
                junior_college_rejected=0,
                matched=False,
                matched_candidate=None,
                end_reason="empty_result",
                iterations=[],
            )

        screened_fingerprints: set[str] = set()
        junior_college_rejected = 0
        stagnant_swipes = 0
        iterations: list[FilterIteration] = []
        matched_candidate: str | None = None
        end_reason = "stagnant_limit_reached"
        previous_visible_fingerprints: tuple[str, ...] = ()

        for iteration_index in range(1, 200):
            step_dir = output_dir / f"iteration_{iteration_index:02d}"
            step_dir.mkdir(parents=True, exist_ok=True)
            screenshot_path, hierarchy_path = self._capture_result_list_step(step_dir)

            root = self._parse_xml(hierarchy_path)
            visible_cards = self.extract_visible_candidate_cards(root)
            visible_fingerprints = tuple(card.fingerprint for card in visible_cards)
            newly_screened = 0

            for card in visible_cards:
                if card.fingerprint not in screened_fingerprints:
                    screened_fingerprints.add(card.fingerprint)
                    newly_screened += 1
                if self.is_junior_college_candidate(card.raw_text):
                    junior_college_rejected += 1
                    continue
                if self.is_exact_candidate_match(card.raw_text, seed):
                    matched_candidate = card.raw_text
                    iterations.append(
                        FilterIteration(
                            iteration=iteration_index,
                            visible_candidates=len(visible_cards),
                            newly_screened=newly_screened,
                            screened_total=len(screened_fingerprints),
                            matched_candidate=matched_candidate,
                            stagnant_swipes=stagnant_swipes,
                            screenshot_path=str(screenshot_path),
                            hierarchy_path=str(hierarchy_path),
                        )
                    )
                    end_reason = "exact_match_found"
                    return self._write_report(
                        output_dir=output_dir,
                        seed=seed,
                        screened_candidates=len(screened_fingerprints),
                        junior_college_rejected=junior_college_rejected,
                        matched=True,
                        matched_candidate=matched_candidate,
                        end_reason=end_reason,
                        iterations=iterations,
                    )

            if iteration_index > 1 and self._is_stagnant(
                previous_visible_fingerprints,
                visible_fingerprints,
                newly_screened,
            ):
                stagnant_swipes += 1
            else:
                stagnant_swipes = 0

            iterations.append(
                FilterIteration(
                    iteration=iteration_index,
                    visible_candidates=len(visible_cards),
                    newly_screened=newly_screened,
                    screened_total=len(screened_fingerprints),
                    matched_candidate=None,
                    stagnant_swipes=stagnant_swipes,
                    screenshot_path=str(screenshot_path),
                    hierarchy_path=str(hierarchy_path),
                )
            )

            if stagnant_swipes >= self.max_stagnant_swipes:
                break

            previous_visible_fingerprints = visible_fingerprints
            self.device.swipe(
                RESULT_SWIPE_START_X,
                RESULT_SWIPE_START_Y,
                RESULT_SWIPE_END_X,
                RESULT_SWIPE_END_Y,
                duration=RESULT_SWIPE_DURATION,
            )
            self._wait()

        return self._write_report(
            output_dir=output_dir,
            seed=seed,
            screened_candidates=len(screened_fingerprints),
            junior_college_rejected=junior_college_rejected,
            matched=False,
            matched_candidate=None,
            end_reason=end_reason,
            iterations=iterations,
        )

    @staticmethod
    def extract_visible_candidate_cards(root: ElementTree.Element) -> list[CandidateCard]:
        cards: list[CandidateCard] = []
        for node in root.iter("node"):
            if node.attrib.get("package", "") != "com.lietou.mishu":
                continue
            if node.attrib.get("clickable") != "true":
                continue
            bounds = node.attrib.get("bounds", "")
            if not bounds.startswith("[") or "][" not in bounds:
                continue
            left_top, right_bottom = bounds.split("][")
            x1, y1 = map(int, left_top[1:].split(","))
            x2, y2 = map(int, right_bottom[:-1].split(","))
            width = x2 - x1
            height = y2 - y1
            if y1 < 360:
                continue
            if width < 850 or height < 150:
                continue
            raw_text = " ".join(node.attrib.get("content-desc", "").split())
            if not raw_text:
                continue
            cards.append(
                CandidateCard(
                    fingerprint=ResultPageFilter.build_card_fingerprint(raw_text),
                    raw_text=raw_text,
                    bounds=bounds,
                )
            )
        return cards

    @staticmethod
    def build_card_fingerprint(raw_text: str) -> str:
        normalized = " ".join(raw_text.split())
        first_line = normalized.split(" ")[0] if normalized else ""
        head = normalized[:160]
        return f"{first_line}|{head}"

    @staticmethod
    def is_exact_candidate_match(raw_text: str, seed: SearchTaskSeed) -> bool:
        normalized = " ".join(raw_text.split())
        return seed.brand_keyword in normalized and seed.role_keyword in normalized

    @staticmethod
    def is_junior_college_candidate(raw_text: str) -> bool:
        normalized = " ".join(raw_text.split())
        return "大专" in normalized

    @staticmethod
    def _is_stagnant(
        previous_visible_fingerprints: Iterable[str],
        current_visible_fingerprints: Iterable[str],
        newly_screened: int,
    ) -> bool:
        previous_set = set(previous_visible_fingerprints)
        current_set = set(current_visible_fingerprints)
        if not previous_set and not current_set:
            return True
        if newly_screened > 0:
            return False
        overlap = previous_set & current_set
        return current_set == previous_set or len(overlap) >= max(1, len(current_set) - 1)

    @staticmethod
    def _parse_xml(path: Path) -> ElementTree.Element:
        return ElementTree.fromstring(path.read_text(encoding="utf-8"))

    def _tap_result_search_entry(self, root: ElementTree.Element) -> None:
        center = find_search_box(root)
        if center is None:
            raise ResultFilterError("Search box control not found on current page")
        self.device.tap(*center)

    def _run_exact_search_from_search_page(
        self,
        seed: SearchTaskSeed,
        hierarchy_path: Path,
    ) -> None:
        root = self._parse_xml(hierarchy_path)
        query_text = f"{seed.role_keyword} {seed.brand_keyword}"
        search_box_center = find_search_box(root)
        if search_box_center is None:
            raise ResultFilterError("Search box control not found on search page")
        self.device.tap(*search_box_center)
        self._wait(0.5)
        self.device.send_keys(query_text, clear=True)
        self._wait(0.5)

        refreshed_path = self.device.dump_hierarchy(hierarchy_path)
        refreshed_root = self._parse_xml(refreshed_path)
        submit_center = find_search_submit(refreshed_root)
        if submit_center is None:
            raise ResultFilterError("Search submit control not found on search page")
        self.device.tap(*submit_center)
        self._wait(2.0)

    def _capture_result_list_step(self, step_dir: Path) -> tuple[Path, Path]:
        for _ in range(3):
            screenshot_path = self.device.screenshot(step_dir / "screen.png")
            hierarchy_path = self.device.dump_hierarchy(step_dir / "hierarchy.xml")
            detection = self.detector.detect_from_xml(hierarchy_path)
            if detection.state == PageState.RESULT_LIST:
                return screenshot_path, hierarchy_path
            if detection.state == PageState.CANDIDATE_PROFILE:
                self.device.press_back()
                self._wait(1.0)
                continue
            raise ResultFilterError(
                f"Expected current page to stay on result list, got: {detection.state.value}"
            )
        raise ResultFilterError("Unable to recover result list during filtering")

    def _ensure_result_list_ready(self, seed: SearchTaskSeed) -> PageState:
        probe_dir = Path("work") / "result_filter_probe"
        probe_dir.mkdir(parents=True, exist_ok=True)
        for _ in range(5):
            hierarchy_path = self.device.dump_hierarchy(probe_dir / "current.xml")
            detection = self.detector.detect_from_xml(hierarchy_path)
            if detection.state in (PageState.RESULT_LIST, PageState.EMPTY_RESULT):
                return detection.state
            if detection.state == PageState.CANDIDATE_PROFILE:
                self.device.press_back()
                self._wait()
                continue
            if detection.state in (PageState.SEARCH_ENTRY, PageState.HOME_RECOMMEND):
                self._tap_result_search_entry(self._parse_xml(hierarchy_path))
                self._wait()
                continue
            if detection.state == PageState.SEARCH_CONDITION:
                self._run_exact_search_from_search_page(seed, hierarchy_path)
                continue
            raise ResultFilterError(
                "Expected to start from result list, candidate profile, or search page, "
                f"got: {detection.state.value}"
            )
        raise ResultFilterError("Unable to recover back to result list before filtering")

    def _write_report(
        self,
        output_dir: Path,
        seed: SearchTaskSeed,
        screened_candidates: int,
        junior_college_rejected: int,
        matched: bool,
        matched_candidate: str | None,
        end_reason: str,
        iterations: list[FilterIteration],
    ) -> FilterRunReport:
        report = FilterRunReport(
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            screened_candidates=screened_candidates,
            junior_college_rejected=junior_college_rejected,
            matched=matched,
            matched_candidate=matched_candidate,
            end_reason=end_reason,
            iterations=iterations,
        )
        report_path = output_dir / "report.json"
        report_path.write_text(
            json.dumps(
                {
                    "brand_keyword": report.brand_keyword,
                    "role_keyword": report.role_keyword,
                    "screened_candidates": report.screened_candidates,
                    "junior_college_rejected": report.junior_college_rejected,
                    "matched": report.matched,
                    "matched_candidate": report.matched_candidate,
                    "end_reason": report.end_reason,
                    "iterations": [asdict(item) for item in report.iterations],
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        return report

    def _wait(self, seconds: float | None = None) -> None:
        time.sleep(self.settle_seconds if seconds is None else seconds)
