from __future__ import annotations

import json
import time
from dataclasses import asdict
from pathlib import Path
from xml.etree import ElementTree

from liepin_rpa.device.client import DeviceClient
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector
from liepin_rpa.search_execution.models import SearchExecutionReport, SearchStepCapture
from liepin_rpa.task_generation.models import SearchTaskSeed
from liepin_rpa.ui_controls import (
    find_candidate_card_centers,
    find_clickable_labeled_center,
    find_search_box,
    find_search_submit,
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


class SearchExecutionError(RuntimeError):
    """Raised when a readonly search run cannot continue safely."""


class ReadonlySearchExecutor:
    def __init__(
        self,
        device: DeviceClient,
        detector=None,
        settle_seconds: float = 1.8,
    ) -> None:
        self.device = device
        self.detector = detector or build_page_detector()
        self.settle_seconds = settle_seconds

    def run(
        self,
        seed: SearchTaskSeed,
        query_text: str,
        output_dir: Path,
    ) -> SearchExecutionReport:
        output_dir.mkdir(parents=True, exist_ok=True)
        steps: list[SearchStepCapture] = []

        current_capture = self._capture_step(output_dir, "01_current_page")
        current_capture = self._recover_interrupted_page(
            output_dir,
            capture=current_capture,
            expected_states=(
                PageState.RESULT_LIST.value,
                PageState.EMPTY_RESULT.value,
                PageState.SEARCH_ENTRY.value,
                PageState.HOME_RECOMMEND.value,
                PageState.SEARCH_CONDITION.value,
            ),
            step_name="01_current_page_recovered",
        )
        current_capture = self._recover_supported_start_page(
            output_dir,
            capture=current_capture,
            step_name="01_current_page_from_profile",
        )
        steps.append(current_capture)
        current_root = self._parse_xml(Path(current_capture.hierarchy_path))

        if current_capture.state in (
            PageState.RESULT_LIST.value,
            PageState.EMPTY_RESULT.value,
            PageState.SEARCH_ENTRY.value,
            PageState.HOME_RECOMMEND.value,
        ):
            self._tap_search_box(current_root)
            self._wait(1.2)
            search_capture = self._capture_step(output_dir, "02_search_entry")
            search_capture = self._recover_interrupted_page(
                output_dir,
                capture=search_capture,
                expected_states=(
                    PageState.SEARCH_ENTRY.value,
                    PageState.SEARCH_CONDITION.value,
                ),
                step_name="02_search_entry_recovered",
            )
            steps.append(search_capture)
        elif current_capture.state == PageState.SEARCH_CONDITION.value:
            search_capture = current_capture
        else:
            raise SearchExecutionError(
                f"Unsupported starting page for readonly search: {current_capture.state}"
            )

        search_root = self._parse_xml(Path(search_capture.hierarchy_path))
        self._tap_search_box(search_root)
        self._wait(0.6)
        self.device.send_keys(query_text, clear=True)
        self._wait(0.6)

        refreshed_search_capture = self._capture_step(output_dir, "03_search_ready")
        steps.append(refreshed_search_capture)
        refreshed_search_root = self._parse_xml(Path(refreshed_search_capture.hierarchy_path))
        self._tap_search_submit(refreshed_search_root)
        self._wait(2.2)

        result_capture = self._capture_step(output_dir, "04_result_list")
        steps.append(result_capture)
        if result_capture.state == PageState.EMPTY_RESULT.value:
            return self._write_report(
                output_dir=output_dir,
                seed=seed,
                query_text=query_text,
                steps=steps,
            )
        if result_capture.state != PageState.RESULT_LIST.value:
            raise SearchExecutionError(
                f"Expected result list after exact search, got: {result_capture.state}"
            )

        result_root = self._parse_xml(Path(result_capture.hierarchy_path))
        self._tap_first_candidate_card(result_root)
        self._wait(2.2)

        profile_capture = self._capture_step(output_dir, "05_candidate_profile")
        steps.append(profile_capture)
        if profile_capture.state != PageState.CANDIDATE_PROFILE.value:
            raise SearchExecutionError(
                f"Expected candidate profile after tapping result card, got: {profile_capture.state}"
            )

        self.device.press_back()
        self._wait(1.4)
        back_capture = self._capture_step(output_dir, "06_back_to_result_list")
        steps.append(back_capture)
        if back_capture.state not in (PageState.RESULT_LIST.value, PageState.EMPTY_RESULT.value):
            raise SearchExecutionError(
                f"Expected to return to result list after profile back, got: {back_capture.state}"
            )

        return self._write_report(
            output_dir=output_dir,
            seed=seed,
            query_text=query_text,
            steps=steps,
        )

    def _write_report(
        self,
        output_dir: Path,
        seed: SearchTaskSeed,
        query_text: str,
        steps: list[SearchStepCapture],
    ) -> SearchExecutionReport:
        report_path = output_dir / "report.json"
        report = SearchExecutionReport(
            query_text=query_text,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            report_path=str(report_path),
            steps=steps,
        )
        report_path.write_text(
            json.dumps(
                {
                    "query_text": report.query_text,
                    "brand_keyword": report.brand_keyword,
                    "role_keyword": report.role_keyword,
                    "steps": [asdict(item) for item in report.steps],
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        return report

    def _capture_step(self, output_dir: Path, step_name: str) -> SearchStepCapture:
        step_dir = output_dir / step_name
        step_dir.mkdir(parents=True, exist_ok=True)
        screenshot_path = self.device.screenshot(step_dir / "screen.png")
        hierarchy_path = self.device.dump_hierarchy(step_dir / "hierarchy.xml")
        current_app = self.device.current_app()
        detection = self.detector.detect_from_xml(hierarchy_path)
        signatures = self._extract_signatures(self._parse_xml(hierarchy_path))
        capture = SearchStepCapture(
            step_name=step_name,
            state=detection.state.value,
            matched_signature=detection.matched_signature,
            package=str(current_app.get("package")),
            activity=str(current_app.get("activity")),
            screenshot_path=str(screenshot_path),
            hierarchy_path=str(hierarchy_path),
            signatures=signatures,
        )
        (step_dir / "metadata.json").write_text(
            json.dumps(asdict(capture), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return capture

    @staticmethod
    def _parse_xml(path: Path) -> ElementTree.Element:
        return ElementTree.fromstring(path.read_text(encoding="utf-8"))

    @staticmethod
    def _extract_signatures(root: ElementTree.Element, limit: int = 8) -> list[str]:
        values: list[str] = []
        for node in root.iter("node"):
            package = node.attrib.get("package", "")
            if package.startswith("com.android.systemui"):
                continue
            if package.startswith("com.baidu.input"):
                continue
            if package == "gesture":
                continue
            for key in ("content-desc", "text", "hint"):
                candidate = " ".join(node.attrib.get(key, "").split())
                if candidate and candidate not in values:
                    values.append(candidate)
                if len(values) >= limit:
                    return values
        return values

    def _tap_search_box(self, root: ElementTree.Element) -> None:
        center = find_search_box(root)
        if center is None:
            raise SearchExecutionError("Search box control not found in current hierarchy")
        self.device.tap(*center)

    def _tap_search_submit(self, root: ElementTree.Element) -> None:
        center = find_search_submit(root)
        if center is None:
            raise SearchExecutionError("Search submit control not found in current hierarchy")
        self.device.tap(*center)

    def _tap_first_candidate_card(self, root: ElementTree.Element) -> None:
        card_centers = find_candidate_card_centers(root)
        if not card_centers:
            raise SearchExecutionError("No candidate card-like node found on result list page")
        card_centers.sort(key=lambda item: (item[1], item[0]))
        self.device.tap(*card_centers[0])

    def _recover_interrupted_page(
        self,
        output_dir: Path,
        *,
        capture: SearchStepCapture,
        expected_states: tuple[str, ...],
        step_name: str,
    ) -> SearchStepCapture:
        if capture.state in expected_states:
            return capture
        root = self._parse_xml(Path(capture.hierarchy_path))
        dismiss_center = find_clickable_labeled_center(root, SEARCH_INTERRUPTION_DISMISS_LABELS)
        if dismiss_center is None:
            return capture
        self.device.tap(*dismiss_center)
        self._wait(1.0)
        recovered_capture = self._capture_step(output_dir, step_name)
        if recovered_capture.state not in expected_states:
            raise SearchExecutionError(
                "Dismissed an interruption prompt but did not recover the expected page. "
                f"Current state: {recovered_capture.state}"
            )
        return recovered_capture

    def _recover_supported_start_page(
        self,
        output_dir: Path,
        *,
        capture: SearchStepCapture,
        step_name: str,
    ) -> SearchStepCapture:
        if capture.state != PageState.CANDIDATE_PROFILE.value:
            return capture
        self.device.press_back()
        self._wait(1.2)
        recovered_capture = self._capture_step(output_dir, step_name)
        recovered_capture = self._recover_interrupted_page(
            output_dir,
            capture=recovered_capture,
            expected_states=(
                PageState.RESULT_LIST.value,
                PageState.EMPTY_RESULT.value,
                PageState.SEARCH_ENTRY.value,
                PageState.HOME_RECOMMEND.value,
                PageState.SEARCH_CONDITION.value,
            ),
            step_name=f"{step_name}_recovered",
        )
        return recovered_capture

    def _wait(self, seconds: float | None = None) -> None:
        time.sleep(self.settle_seconds if seconds is None else seconds)
