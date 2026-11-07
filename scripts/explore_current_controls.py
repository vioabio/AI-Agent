from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree

from liepin_rpa.config import get_settings
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.logging_utils import configure_logging
from liepin_rpa.page_detection.runtime import build_page_detector


@dataclass(slots=True)
class NodeSnapshot:
    node_id: int
    depth: int
    path: str
    parent_id: int | None
    class_name: str
    package: str
    resource_id: str
    text: str
    content_desc: str
    hint: str
    clickable: bool
    enabled: bool
    focusable: bool
    scrollable: bool
    selected: bool
    bounds: str
    center_x: int | None
    center_y: int | None
    width: int | None
    height: int | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output-dir",
        default="outputs/control_explorer/current_page",
        help="Directory to store the screenshot, hierarchy dump, and exploration report.",
    )
    parser.add_argument(
        "--package-filter",
        default="com.lietou.mishu",
        help="Only include nodes from this package in control summaries. Use '*' for all packages.",
    )
    parser.add_argument(
        "--max-descendants-per-level",
        type=int,
        default=12,
        help="Maximum number of descendant nodes to retain per level in subtree summaries.",
    )
    return parser.parse_args()


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    return " ".join(value.split())


def parse_bounds(bounds: str) -> tuple[int, int, int, int] | None:
    if not bounds.startswith("[") or "][" not in bounds:
        return None
    left_top, right_bottom = bounds.split("][")
    x1, y1 = map(int, left_top[1:].split(","))
    x2, y2 = map(int, right_bottom[:-1].split(","))
    return x1, y1, x2, y2


def iter_tree(
    node: ElementTree.Element,
    *,
    depth: int = 0,
    path: str = "0",
    parent_id: int | None = None,
    next_id: list[int] | None = None,
) -> Iterable[tuple[int, int, str, int | None, ElementTree.Element]]:
    if next_id is None:
        next_id = [1]
    node_id = next_id[0]
    next_id[0] += 1
    yield node_id, depth, path, parent_id, node
    for child_index, child in enumerate(list(node)):
        child_path = f"{path}/{child_index}"
        yield from iter_tree(
            child,
            depth=depth + 1,
            path=child_path,
            parent_id=node_id,
            next_id=next_id,
        )


def build_snapshot(
    node_id: int,
    depth: int,
    path: str,
    parent_id: int | None,
    node: ElementTree.Element,
) -> NodeSnapshot:
    bounds = node.attrib.get("bounds", "")
    parsed_bounds = parse_bounds(bounds)
    center_x = center_y = width = height = None
    if parsed_bounds is not None:
        x1, y1, x2, y2 = parsed_bounds
        center_x = (x1 + x2) // 2
        center_y = (y1 + y2) // 2
        width = x2 - x1
        height = y2 - y1
    return NodeSnapshot(
        node_id=node_id,
        depth=depth,
        path=path,
        parent_id=parent_id,
        class_name=node.attrib.get("class", ""),
        package=node.attrib.get("package", ""),
        resource_id=node.attrib.get("resource-id", ""),
        text=normalize_text(node.attrib.get("text")),
        content_desc=normalize_text(node.attrib.get("content-desc")),
        hint=normalize_text(node.attrib.get("hint")),
        clickable=node.attrib.get("clickable") == "true",
        enabled=node.attrib.get("enabled") != "false",
        focusable=node.attrib.get("focusable") == "true",
        scrollable=node.attrib.get("scrollable") == "true",
        selected=node.attrib.get("selected") == "true",
        bounds=bounds,
        center_x=center_x,
        center_y=center_y,
        width=width,
        height=height,
    )


def summarize_node(snapshot: NodeSnapshot) -> dict[str, object]:
    return {
        "node_id": snapshot.node_id,
        "path": snapshot.path,
        "class_name": snapshot.class_name,
        "package": snapshot.package,
        "resource_id": snapshot.resource_id,
        "text": snapshot.text,
        "content_desc": snapshot.content_desc,
        "hint": snapshot.hint,
        "clickable": snapshot.clickable,
        "enabled": snapshot.enabled,
        "focusable": snapshot.focusable,
        "scrollable": snapshot.scrollable,
        "selected": snapshot.selected,
        "bounds": snapshot.bounds,
        "center": [snapshot.center_x, snapshot.center_y],
        "size": [snapshot.width, snapshot.height],
    }


def build_parent_chain(
    node_id: int,
    snapshots: dict[int, NodeSnapshot],
    *,
    generations: int = 3,
) -> list[dict[str, object]]:
    chain: list[dict[str, object]] = []
    current_id = snapshots[node_id].parent_id
    while current_id is not None and len(chain) < generations:
        chain.append(summarize_node(snapshots[current_id]))
        current_id = snapshots[current_id].parent_id
    return chain


def build_descendant_tree(
    node_id: int,
    children_by_parent: dict[int, list[int]],
    snapshots: dict[int, NodeSnapshot],
    *,
    max_depth: int = 3,
    max_descendants_per_level: int = 12,
) -> list[dict[str, object]]:
    def _walk(current_id: int, depth: int) -> list[dict[str, object]]:
        if depth > max_depth:
            return []
        child_ids = children_by_parent.get(current_id, [])[:max_descendants_per_level]
        items: list[dict[str, object]] = []
        for child_id in child_ids:
            child_summary = summarize_node(snapshots[child_id])
            child_summary["children"] = _walk(child_id, depth + 1)
            items.append(child_summary)
        return items

    return _walk(node_id, 1)


def is_interactive(snapshot: NodeSnapshot) -> bool:
    return snapshot.clickable or snapshot.focusable or snapshot.scrollable


def build_markdown_report(
    *,
    package: str,
    activity: str,
    detected_state: str,
    matched_signature: str | None,
    screenshot_path: Path,
    hierarchy_path: Path,
    package_filter: str,
    snapshots: list[NodeSnapshot],
    interactive_snapshots: list[NodeSnapshot],
) -> str:
    lines = [
        "# Current Page Control Explorer",
        "",
        f"- package: `{package}`",
        f"- activity: `{activity}`",
        f"- detected_state: `{detected_state}`",
        f"- matched_signature: `{matched_signature}`",
        f"- package_filter: `{package_filter}`",
        f"- screenshot: `{screenshot_path}`",
        f"- hierarchy_xml: `{hierarchy_path}`",
        f"- total_nodes_in_report: `{len(snapshots)}`",
        f"- interactive_nodes_in_report: `{len(interactive_snapshots)}`",
        "",
        "## Interactive Controls",
        "",
    ]
    for snapshot in interactive_snapshots[:40]:
        label = snapshot.content_desc or snapshot.text or snapshot.hint or snapshot.class_name
        lines.append(
            f"- id={snapshot.node_id} depth={snapshot.depth} center=({snapshot.center_x},{snapshot.center_y}) "
            f"size=({snapshot.width},{snapshot.height}) clickable={snapshot.clickable} "
            f"scrollable={snapshot.scrollable} label=`{label}` bounds=`{snapshot.bounds}`"
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()

    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    client = DeviceClient(adb_runner=AdbRunner(settings.adb_executable), device_id=settings.device_id)
    client.connect()

    current_app = client.current_app()
    screenshot_path = client.screenshot(output_dir / "screen.png")
    hierarchy_path = client.dump_hierarchy(output_dir / "hierarchy.xml")

    detector = build_page_detector()
    detection = detector.detect_from_xml(hierarchy_path)
    root = ElementTree.fromstring(hierarchy_path.read_text(encoding="utf-8"))

    all_snapshots_by_id: dict[int, NodeSnapshot] = {}
    children_by_parent: dict[int, list[int]] = defaultdict(list)

    for node_id, depth, path, parent_id, node in iter_tree(root):
        snapshot = build_snapshot(node_id, depth, path, parent_id, node)
        all_snapshots_by_id[node_id] = snapshot
        if parent_id is not None:
            children_by_parent[parent_id].append(node_id)

    ordered_snapshots = [
        snapshot
        for snapshot in sorted(all_snapshots_by_id.values(), key=lambda item: item.node_id)
        if args.package_filter == "*" or snapshot.package == args.package_filter
    ]
    interactive_snapshots = [item for item in ordered_snapshots if is_interactive(item)]

    full_report = {
        "current_app": {
            "package": current_app.get("package"),
            "activity": current_app.get("activity"),
        },
        "detection": {
            "state": detection.state.value,
            "matched_signature": detection.matched_signature,
        },
        "artifacts": {
            "screenshot_path": str(screenshot_path),
            "hierarchy_path": str(hierarchy_path),
        },
        "package_filter": args.package_filter,
        "total_nodes": len(ordered_snapshots),
        "interactive_nodes": len(interactive_snapshots),
        "nodes": [],
    }

    for snapshot in ordered_snapshots:
        full_report["nodes"].append(
            {
                **asdict(snapshot),
                "parent_chain": build_parent_chain(snapshot.node_id, all_snapshots_by_id, generations=3),
                "children_3_levels": build_descendant_tree(
                    snapshot.node_id,
                    children_by_parent,
                    all_snapshots_by_id,
                    max_depth=3,
                    max_descendants_per_level=args.max_descendants_per_level,
                ),
            }
        )

    json_path = output_dir / "report.json"
    json_path.write_text(json.dumps(full_report, ensure_ascii=False, indent=2), encoding="utf-8")

    interactive_json_path = output_dir / "interactive_controls.json"
    interactive_json_path.write_text(
        json.dumps(
            {
                "controls": [
                    {
                        **asdict(snapshot),
                        "parent_chain": build_parent_chain(
                            snapshot.node_id,
                            all_snapshots_by_id,
                            generations=3,
                        ),
                        "children_3_levels": build_descendant_tree(
                            snapshot.node_id,
                            children_by_parent,
                            all_snapshots_by_id,
                            max_depth=3,
                            max_descendants_per_level=args.max_descendants_per_level,
                        ),
                    }
                    for snapshot in interactive_snapshots
                ]
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    markdown_path = output_dir / "report.md"
    markdown_path.write_text(
        build_markdown_report(
            package=str(current_app.get("package")),
            activity=str(current_app.get("activity")),
            detected_state=detection.state.value,
            matched_signature=detection.matched_signature,
            screenshot_path=screenshot_path,
            hierarchy_path=hierarchy_path,
            package_filter=args.package_filter,
            snapshots=ordered_snapshots,
            interactive_snapshots=interactive_snapshots,
        ),
        encoding="utf-8",
    )

    print(
        json.dumps(
            {
                "status": "ok",
                "package": current_app.get("package"),
                "activity": current_app.get("activity"),
                "detected_state": detection.state.value,
                "matched_signature": detection.matched_signature,
                "package_filter": args.package_filter,
                "total_nodes": len(ordered_snapshots),
                "interactive_nodes": len(interactive_snapshots),
                "report_path": str(json_path),
                "interactive_controls_path": str(interactive_json_path),
                "markdown_path": str(markdown_path),
                "screenshot_path": str(screenshot_path),
                "hierarchy_path": str(hierarchy_path),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
