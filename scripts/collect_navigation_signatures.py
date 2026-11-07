from __future__ import annotations

import json
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable
from xml.etree import ElementTree

from liepin_rpa.config import get_settings
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.logging_utils import configure_logging
from liepin_rpa.page_detection.runtime import build_page_detector


@dataclass(slots=True)
class StepCapture:
    step_name: str
    state: str
    matched_signature: str | None
    package: str | None
    activity: str | None
    screenshot_path: str
    hierarchy_path: str
    signatures: list[str]


def _parse_xml(path: Path) -> ElementTree.Element:
    return ElementTree.fromstring(path.read_text(encoding="utf-8"))


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
        content_desc = " ".join(node.attrib.get("content-desc", "").split())
        text = " ".join(node.attrib.get("text", "").split())
        hint = " ".join(node.attrib.get("hint", "").split())
        for candidate in (content_desc, text, hint):
            if candidate and candidate not in values:
                values.append(candidate)
            if len(values) >= limit:
                return values
    return values


def _find_bounds(
    root: ElementTree.Element,
    predicate: Callable[[ElementTree.Element], bool],
) -> tuple[int, int]:
    for node in root.iter("node"):
        if predicate(node):
            bounds = node.attrib.get("bounds", "")
            if bounds.startswith("[") and "][" in bounds:
                left_top, right_bottom = bounds.split("][")
                x1, y1 = left_top[1:].split(",")
                x2, y2 = right_bottom[:-1].split(",")
                return ((int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2)
    raise RuntimeError("Target bounds not found in current hierarchy")


def _tap_via_adb(adb_runner: AdbRunner, x: int, y: int) -> None:
    adb_runner.shell("input", "tap", str(x), str(y))


def _tap_match(
    adb_runner: AdbRunner,
    root: ElementTree.Element,
    predicate: Callable[[ElementTree.Element], bool],
) -> tuple[int, int]:
    x, y = _find_bounds(root, predicate)
    _tap_via_adb(adb_runner, x, y)
    return x, y


def _wait_for_settle(seconds: float = 1.8) -> None:
    time.sleep(seconds)


def _node_with_bounds_exists(root: ElementTree.Element, bounds: str) -> bool:
    for node in root.iter("node"):
        if node.attrib.get("bounds", "") == bounds:
            return True
    return False


def _is_search_entry_like(root: ElementTree.Element) -> bool:
    required_bounds = (
        "[180,150][348,210]",
        "[441,126][852,234]",
        "[900,150][1032,210]",
        "[48,522][606,618]",
    )
    return all(_node_with_bounds_exists(root, bounds) for bounds in required_bounds)


def _is_result_list_like(root: ElementTree.Element) -> bool:
    return any(
        _node_with_bounds_exists(root, bounds)
        for bounds in (
            "[48,918][1032,1140]",
            "[48,1140][1032,1362]",
            "[48,1362][1032,1517]",
        )
    )


def _tap_history_keyword(adb_runner: AdbRunner, root: ElementTree.Element) -> tuple[int, int]:
    for bounds in ("[48,522][606,618]", "[0,246][1080,378]"):
        try:
            return _tap_match(
                adb_runner,
                root,
                lambda node, target=bounds: node.attrib.get("bounds", "") == target,
            )
        except RuntimeError:
            continue
    raise RuntimeError("No reusable history/suggestion keyword entry found on search page")


def _tap_top_search_bar(adb_runner: AdbRunner, root: ElementTree.Element) -> tuple[int, int]:
    return _tap_match(
        adb_runner,
        root,
        lambda node: node.attrib.get("bounds", "") == "[441,150][912,210]",
    )


def _tap_first_candidate_card(adb_runner: AdbRunner, root: ElementTree.Element) -> tuple[int, int]:
    candidate_nodes: list[tuple[int, int, int, int]] = []
    for node in root.iter("node"):
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
        if y1 < 420:
            continue
        if width < 850 or height < 150:
            continue
        candidate_nodes.append((x1, y1, x2, y2))
    if not candidate_nodes:
        raise RuntimeError("No candidate card-like node found on result list page")
    candidate_nodes.sort(key=lambda item: (item[1], item[0]))
    x1, y1, x2, y2 = candidate_nodes[0]
    x = (x1 + x2) // 2
    y = (y1 + y2) // 2
    _tap_via_adb(adb_runner, x, y)
    return x, y


def _capture_step(
    device: DeviceClient,
    detector,
    output_dir: Path,
    step_name: str,
) -> StepCapture:
    step_dir = output_dir / step_name
    step_dir.mkdir(parents=True, exist_ok=True)
    screenshot_path = device.screenshot(step_dir / "screen.png")
    hierarchy_path = device.dump_hierarchy(step_dir / "hierarchy.xml")
    current_app = device.current_app()
    detection = detector.detect_from_xml(hierarchy_path)
    signatures = _extract_signatures(_parse_xml(hierarchy_path))
    metadata = StepCapture(
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
        json.dumps(asdict(metadata), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return metadata


def _write_summary(output_dir: Path, captures: list[StepCapture]) -> Path:
    summary_path = output_dir / "report.md"
    lines = [
        "# Navigation Signature Collection",
        "",
        "## Database Connection",
        "",
        "- host: `rm-bp135o9085r8yeu7tlo.mysql.rds.aliyuncs.com`",
        "- port: `3306`",
        "- database: `phone-rpa`",
        "- charset: `utf8mb4`",
        "- auth: use env vars `MYSQL_USER` and `MYSQL_PASSWORD`; do not hardcode credentials in runtime code",
        "",
        "## Captured Steps",
        "",
    ]
    for item in captures:
        visible_signatures = ", ".join(f"`{text}`" for text in item.signatures[:6]) or "`<none>`"
        lines.extend(
            [
                f"### {item.step_name}",
                f"- state: `{item.state}`",
                f"- matched signature: `{item.matched_signature}`",
                f"- activity: `{item.activity}`",
                f"- screenshot: `{item.screenshot_path}`",
                f"- hierarchy: `{item.hierarchy_path}`",
                f"- visible signatures: {visible_signatures}",
                "",
            ]
        )
    summary_path.write_text("\n".join(lines), encoding="utf-8")
    return summary_path


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)
    adb_runner = AdbRunner(settings.adb_executable)
    adb_runner.clear_uiautomator_sessions()
    client = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)
    client.connect()
    detector = build_page_detector()

    output_dir = Path("outputs") / "navigation_signatures"
    output_dir.mkdir(parents=True, exist_ok=True)

    captures: list[StepCapture] = []

    current_capture = _capture_step(client, detector, output_dir, "01_current_page")
    captures.append(current_capture)
    current_root = _parse_xml(Path(current_capture.hierarchy_path))

    if _is_search_entry_like(current_root) or current_capture.state == "search_condition":
        search_root = current_root
    elif _is_result_list_like(current_root) or current_capture.state == "result_list":
        _tap_top_search_bar(adb_runner, current_root)
        _wait_for_settle()
        search_entry_capture = _capture_step(client, detector, output_dir, "02_search_entry")
        captures.append(search_entry_capture)
        search_root = _parse_xml(Path(search_entry_capture.hierarchy_path))
    else:
        raise RuntimeError(
            f"Unsupported starting page for signature collection: {current_capture.state}"
        )

    _tap_history_keyword(adb_runner, search_root)
    _wait_for_settle(2.2)
    result_capture = _capture_step(client, detector, output_dir, "03_result_list")
    captures.append(result_capture)
    result_root = _parse_xml(Path(result_capture.hierarchy_path))

    _tap_first_candidate_card(adb_runner, result_root)
    _wait_for_settle(2.2)
    profile_capture = _capture_step(client, detector, output_dir, "04_candidate_profile")
    captures.append(profile_capture)

    client.press_back()
    _wait_for_settle()
    back_to_result_capture = _capture_step(client, detector, output_dir, "05_back_to_result_list")
    captures.append(back_to_result_capture)

    report_path = output_dir / "report.json"
    report_path.write_text(
        json.dumps([asdict(item) for item in captures], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    summary_path = _write_summary(output_dir, captures)
    print(
        json.dumps(
            {
                "report_path": str(report_path),
                "summary_path": str(summary_path),
                "steps": [asdict(item) for item in captures],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
