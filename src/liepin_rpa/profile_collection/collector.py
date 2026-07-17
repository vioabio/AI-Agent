from __future__ import annotations

import random
import time
from collections import OrderedDict
from dataclasses import asdict, dataclass
from pathlib import Path
from xml.etree import ElementTree

from liepin_rpa.device.adb import AdbCommandError
from liepin_rpa.device.client import DeviceClient, DeviceConnectionError
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector
from liepin_rpa.profile_collection.resume_schema import build_candidate_resume_schema
from liepin_rpa.ui_controls import iter_app_nodes, node_label, parse_bounds

from .models import ProfileCollectionIteration, ProfileCollectionReport

LOGGER = get_logger(__name__)
PROFILE_HEADER_SECTION = "_profile_header"
PROFILE_SWIPE_X = 540
PROFILE_SWIPE_START_Y = 1650
PROFILE_SWIPE_END_Y = 620
EXPAND_LABEL_PREFIX = "展开全部"
ACTION_LABELS = {
    "获取电话",
    "立即沟通",
    "继续沟通",
    "向TA索要",
    "更多",
    "询问TA",
    "个人信息",
    "技能标签",
}
SECTION_HEADERS = {
    "求职意向",
    "简历亮点",
    "工作经历",
    "教育经历",
    "项目经历",
    "资格证书",
    "语言能力",
    "培训经历",
    "自我介绍",
    "自我评价",
    "个人优势",
    "附件简历",
}


@dataclass(slots=True)
class ProfileControl:
    label: str
    bounds: str
    tap_x: int
    tap_y: int
    top: int
    left: int


@dataclass(slots=True)
class VisibleTextNode:
    label: str
    bounds: str
    top: int
    left: int


class ProfileCollectionRecoverableError(RuntimeError):
    """Raised when profile collection should skip only the current candidate."""


class CandidateProfileCollector:
    def __init__(
        self,
        device: DeviceClient,
        *,
        settle_seconds: float = 3.0,
        swipe_duration_seconds: float = 0.22,
        max_stagnant_swipes: int = 3,
        max_iterations: int = 80,
    ) -> None:
        self.device = device
        self.detector = build_page_detector()
        self.settle_seconds = settle_seconds
        self.swipe_duration_seconds = swipe_duration_seconds
        self.max_stagnant_swipes = max_stagnant_swipes
        self.max_iterations = max_iterations

    def collect_current_profile(self, output_dir: Path | None = None) -> ProfileCollectionReport:
        seen_texts: set[str] = set()
        collected_text_lines: list[str] = []
        sections: OrderedDict[str, list[str]] = OrderedDict()
        iterations: list[ProfileCollectionIteration] = []
        clicked_expand_controls: set[str] = set()

        total_swipes = 0
        total_expand_clicks = 0
        stagnant_swipe_count = 0
        last_swipe_fingerprint: tuple[str, ...] | None = None
        reached_bottom = False

        for iteration_index in range(1, self.max_iterations + 1):
            hierarchy_text = self.device.dump_hierarchy_text()
            detection = self.detector.detect_from_xml_text(hierarchy_text)
            if detection.state != PageState.CANDIDATE_PROFILE:
                raise RuntimeError(
                    "Current page is not candidate profile. "
                    f"Detected {detection.state.value} ({detection.matched_signature})."
                )

            root = ElementTree.fromstring(hierarchy_text)
            visible_nodes = extract_visible_profile_text_nodes(root)
            visible_text_lines = [node.label for node in visible_nodes]
            new_text_count = merge_unique_lines(
                visible_text_lines,
                seen=seen_texts,
                target=collected_text_lines,
            )
            merge_section_lines(visible_nodes, sections)

            expand_controls = [
                control
                for control in find_expand_all_controls(root)
                if control_signature(control) not in clicked_expand_controls
            ]

            clicked_expand_labels: list[str] = []
            clicked_expand_bounds: list[str] = []
            swipe_performed = False

            if expand_controls:
                for control in expand_controls:
                    clicked_expand_labels.append(control.label)
                    clicked_expand_bounds.append(control.bounds)
                    clicked_expand_controls.add(control_signature(control))
                    total_expand_clicks += 1
                    self.device.tap(control.tap_x, control.tap_y)
                    self._settle_wait()
                stagnant_swipe_count = 0
                last_swipe_fingerprint = None
            else:
                fingerprint = build_swipe_fingerprint(visible_text_lines)
                if fingerprint == last_swipe_fingerprint:
                    stagnant_swipe_count += 1
                else:
                    stagnant_swipe_count = 0
                if stagnant_swipe_count >= self.max_stagnant_swipes:
                    reached_bottom = True
                    iterations.append(
                        ProfileCollectionIteration(
                            iteration=iteration_index,
                            detection_state=detection.state.value,
                            matched_signature=detection.matched_signature,
                            clicked_expand_labels=clicked_expand_labels,
                            clicked_expand_bounds=clicked_expand_bounds,
                            swipe_performed=False,
                            visible_text_count=len(visible_text_lines),
                            new_text_count=new_text_count,
                            stagnant_swipe_count=stagnant_swipe_count,
                            visible_text_lines=visible_text_lines,
                        )
                    )
                    break

                self._swipe_profile_page()
                swipe_performed = True
                total_swipes += 1
                last_swipe_fingerprint = fingerprint
                self._settle_wait()

            iterations.append(
                ProfileCollectionIteration(
                    iteration=iteration_index,
                    detection_state=detection.state.value,
                    matched_signature=detection.matched_signature,
                    clicked_expand_labels=clicked_expand_labels,
                    clicked_expand_bounds=clicked_expand_bounds,
                    swipe_performed=swipe_performed,
                    visible_text_count=len(visible_text_lines),
                    new_text_count=new_text_count,
                    stagnant_swipe_count=stagnant_swipe_count,
                    visible_text_lines=visible_text_lines,
                )
            )
        else:
            raise RuntimeError(
                f"Profile collection exceeded max iterations ({self.max_iterations})."
            )

        debug_report_payload = {
            "total_iterations": len(iterations),
            "total_swipes": total_swipes,
            "total_expand_clicks": total_expand_clicks,
            "stagnant_swipe_limit": self.max_stagnant_swipes,
            "reached_bottom": reached_bottom,
            "collected_text_lines": collected_text_lines,
            "sections": dict(sections),
            "iterations": [asdict(item) for item in iterations],
        }
        structured_resume = build_candidate_resume_schema(
            debug_payload=debug_report_payload,
            output_dir=output_dir or Path("."),
        )

        return ProfileCollectionReport(
            total_iterations=len(iterations),
            total_swipes=total_swipes,
            total_expand_clicks=total_expand_clicks,
            stagnant_swipe_limit=self.max_stagnant_swipes,
            reached_bottom=reached_bottom,
            collected_text_lines=collected_text_lines,
            sections=dict(sections),
            iterations=iterations,
            structured_resume=structured_resume,
        )

    def _swipe_profile_page(self) -> None:
        # Keep profile swipes in the content area to avoid bottom action buttons.
        try:
            self.device.swipe(
                PROFILE_SWIPE_X,
                PROFILE_SWIPE_START_Y,
                PROFILE_SWIPE_X,
                PROFILE_SWIPE_END_Y,
                duration=self.swipe_duration_seconds,
            )
            return
        except (AdbCommandError, DeviceConnectionError) as exc:
            LOGGER.warning(
                "Profile swipe failed, attempting device recovery before skipping candidate",
                extra={
                    "event": "candidate_profile_swipe_retry",
                    "context": {"error": str(exc)},
                },
            )

        try:
            self.device.wake_up()
            self.device.ensure_unlocked()
            self.device.ensure_healthy(max_age_seconds=0.0)
            self._settle_wait(3.0)
            self.device.swipe(
                PROFILE_SWIPE_X,
                PROFILE_SWIPE_START_Y,
                PROFILE_SWIPE_X,
                PROFILE_SWIPE_END_Y,
                duration=self.swipe_duration_seconds,
            )
        except (AdbCommandError, DeviceConnectionError) as exc:
            raise ProfileCollectionRecoverableError(
                f"Profile swipe failed after recovery attempt: {exc}"
            ) from exc

    def _settle_wait(self, minimum_seconds: float | None = None) -> None:
        base_seconds = random.uniform(self.settle_seconds, 5.0)
        target_seconds = base_seconds if minimum_seconds is None else max(base_seconds, minimum_seconds)
        time.sleep(target_seconds)


def find_expand_all_controls(root: ElementTree.Element) -> list[ProfileControl]:
    controls: list[ProfileControl] = []
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        label = node_label(node)
        if not label.startswith(EXPAND_LABEL_PREFIX):
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        controls.append(
            ProfileControl(
                label=label,
                bounds=node.attrib.get("bounds", ""),
                tap_x=expand_control_tap_x(bounds.x1, bounds.width),
                tap_y=bounds.center[1],
                top=bounds.y1,
                left=bounds.x1,
            )
        )
    controls.sort(key=lambda item: (item.top, item.left))
    return controls


def extract_visible_profile_text_nodes(root: ElementTree.Element) -> list[VisibleTextNode]:
    nodes: list[VisibleTextNode] = []
    for node in iter_app_nodes(root):
        label = node_label(node)
        if not should_collect_profile_label(label):
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        if looks_like_recommended_candidate_card(node, label, bounds.width, bounds.height):
            continue
        nodes.append(
            VisibleTextNode(
                label=label,
                bounds=node.attrib.get("bounds", ""),
                top=bounds.y1,
                left=bounds.x1,
            )
        )
    nodes.sort(key=lambda item: (item.top, item.left, len(item.label)))
    return nodes


def should_collect_profile_label(label: str) -> bool:
    if not label:
        return False
    if label.startswith(EXPAND_LABEL_PREFIX):
        return False
    if label in ACTION_LABELS:
        return False
    if label == "·":
        return False
    return True


def looks_like_recommended_candidate_card(
    node: ElementTree.Element,
    label: str,
    width: int,
    height: int,
) -> bool:
    if node.attrib.get("clickable") != "true":
        return False
    if width < 850 or height < 180:
        return False
    if "岁" not in label or "年" not in label:
        return False
    return any(token in label for token in ("K", "薪", "活跃", "本科", "硕士", "MBA"))


def merge_unique_lines(lines: list[str], *, seen: set[str], target: list[str]) -> int:
    added = 0
    for line in lines:
        if line in seen:
            continue
        seen.add(line)
        target.append(line)
        added += 1
    return added


def expand_control_tap_x(left: int, width: int) -> int:
    if width >= 600:
        return left + min(290, max(150, width // 3))
    return left + max(48, width // 3)


def merge_section_lines(
    visible_nodes: list[VisibleTextNode],
    sections: OrderedDict[str, list[str]],
) -> None:
    current_section = PROFILE_HEADER_SECTION
    sections.setdefault(current_section, [])

    for node in visible_nodes:
        label = node.label
        if label in SECTION_HEADERS:
            current_section = label
            sections.setdefault(current_section, [])
            continue

        bucket = sections.setdefault(current_section, [])
        if label not in bucket:
            bucket.append(label)


def build_swipe_fingerprint(visible_text_lines: list[str]) -> tuple[str, ...]:
    if len(visible_text_lines) <= 12:
        return tuple(visible_text_lines)
    return tuple(visible_text_lines[-12:])


def control_signature(control: ProfileControl) -> str:
    return f"{control.label}|{control.bounds}"
