from __future__ import annotations

from collections.abc import Callable
from typing import Literal
from xml.etree import ElementTree

from liepin_rpa.device.client import DeviceClient
from liepin_rpa.ui_controls import BoundsBox, iter_app_nodes, node_label, parse_bounds

JobRoleGroup = Literal["daren_live", "brand_market", "finance_shared"]

DAREN_LIVE_SEARCH_KEYWORDS = frozenset(
    (
        "达人营销总监",
        "媒介总监",
        "直播电商负责人",
        "达人运营总监",
        "达人运营负责人",
        "KOL商务BD",
        "内容营销总监",
        "新媒体营销总监",
        "电商总经理",
        "整合营销总监",
        "直播负责人",
        "抖音负责人",
        "直播推广负责人",
        "BD负责人",
        "BD总监",
    )
)

BRAND_MARKET_SEARCH_KEYWORDS = frozenset(
    (
        "\u54c1\u724c\u4e3b\u7406\u4eba",
        "\u54c1\u724c\u521b\u59cb\u4eba",
        "\u7f8e\u5986\u64cd\u76d8\u624b",
        "\u4e8b\u4e1a\u90e8\u603b\u7ecf\u7406",
        "\u4e1a\u52a1\u8d1f\u8d23\u4eba",
        "\u9879\u76ee\u603b\u7ecf\u7406",
        "\u8fbe\u4eba\u8425\u9500\u603b\u76d1",
        "\u8fbe\u64ad\u8d1f\u8d23\u4eba",
        "\u8fbe\u64ad\u603b\u76d1",
        "\u76f4\u64ad\u4e1a\u52a1\u603b\u76d1",
        "\u76f4\u64ad\u7535\u5546\u8d1f\u8d23\u4eba",
        "\u76f4\u64ad\u8fd0\u8425\u603b\u76d1",
        "\u8fbe\u4eba\u5546\u52a1\u603b\u76d1",
        "\u5a92\u4ecb\u5546\u52a1\u603b\u76d1",
        "\u5168\u57df\u8425\u9500\u603b\u76d1",
        "\u5168\u57df\u589e\u957f\u8d1f\u8d23\u4eba",
        "CMO\uff08\u9996\u5e2d\u8425\u9500\u5b98\uff09",
        "\u8425\u9500\u603b\u76d1",
        "\u7535\u5546\u603b\u76d1",
        "\u7535\u5546\u8d1f\u8d23\u4eba",
        "\u7535\u5546\u603b\u7ecf\u7406",
        "\u8fd0\u8425\u603b\u76d1",
        "\u65b0\u5a92\u4f53\u8fd0\u8425\u603b\u76d1",
    )
)

DAREN_LIVE_OPTION_MARKERS = (
    "\u8fbe\u4eba\u8425\u9500\u603b\u76d1",
    "\u8fbe\u64ad\u603b\u76d1",
    "\u8fbe\u64ad\u8d1f\u8d23\u4eba",
)
BRAND_MARKET_OPTION_MARKERS = (
    "\u7f8e\u5986\u4e3b\u7406\u4eba",
    "\u5e02\u573a\u8fd0\u8425\u603b\u76d1",
)
FINANCE_SHARED_OPTION_MARKERS = (
    "财务共享与管理经理",
    "财务共享及管理经理",
    "财务共享经理",
    "财务共享中心经理",
)
DISABLED_JOB_OPTION_MARKERS = ("\u5b89\u548c\u989c",)


def choose_job_before_chat_if_present(
    device: DeviceClient,
    *,
    role_keyword: str,
    wait: Callable[[float], None],
) -> bool:
    root = ElementTree.fromstring(device.dump_hierarchy_text())
    if not is_job_selection_page(root):
        return False

    role_group = classify_role_group(role_keyword)
    option_center = find_role_option_center(
        root,
        role_group,
        allow_position_fallback=role_group != "finance_shared",
    )
    if option_center is None:
        return False

    device.tap(*option_center)
    wait(1.5)

    refreshed_root = ElementTree.fromstring(device.dump_hierarchy_text())
    confirm_center = find_confirm_center(refreshed_root)
    if confirm_center is None:
        return False

    device.tap(*confirm_center)
    wait(2.5)
    return True


def classify_role_group(role_keyword: str) -> JobRoleGroup:
    normalized = _normalize_role_keyword(role_keyword)
    if any(marker in normalized for marker in ("财务共享", "集团财务", "财务管理经理")):
        return "finance_shared"
    if normalized in DAREN_LIVE_SEARCH_KEYWORDS:
        return "daren_live"
    if normalized in BRAND_MARKET_SEARCH_KEYWORDS:
        return "brand_market"
    if any(marker in normalized for marker in ("\u8fbe\u4eba", "\u8fbe\u64ad", "\u76f4\u64ad")):
        return "daren_live"
    return "brand_market"


def is_job_selection_page(root: ElementTree.Element) -> bool:
    labels = [node_label(node) for node in iter_app_nodes(root)]
    if any("\u9009\u62e9\u5728\u62db\u804c\u4f4d" in label for label in labels):
        return True
    return find_confirm_center(root) is not None and len(_visible_job_option_boxes(root)) >= 2


def find_role_option_center(
    root: ElementTree.Element,
    role_group: JobRoleGroup,
    *,
    allow_position_fallback: bool = False,
) -> tuple[int, int] | None:
    markers = {
        "daren_live": DAREN_LIVE_OPTION_MARKERS,
        "brand_market": BRAND_MARKET_OPTION_MARKERS,
        "finance_shared": FINANCE_SHARED_OPTION_MARKERS,
    }[role_group]
    labeled = _find_option_by_markers(root, markers)
    if labeled is not None:
        return labeled.center

    if not allow_position_fallback:
        return None

    option_boxes = _visible_job_option_boxes(root)
    if not option_boxes:
        return None
    if role_group == "daren_live":
        return option_boxes[-1].center
    if len(option_boxes) >= 5:
        return option_boxes[4].center
    return option_boxes[0].center


def find_confirm_center(root: ElementTree.Element) -> tuple[int, int] | None:
    candidates: list[BoundsBox] = []
    for node in iter_app_nodes(root):
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        label = node_label(node)
        if "\u786e\u5b9a" in label:
            candidates.append(bounds)
            continue
        if node.attrib.get("clickable") == "true" and bounds.y1 >= 2150 and bounds.width >= 850:
            candidates.append(bounds)
    if not candidates:
        return None
    candidates.sort(key=lambda item: (item.width * item.height, item.y1), reverse=True)
    return candidates[0].center


def _find_option_by_markers(
    root: ElementTree.Element,
    markers: tuple[str, ...],
) -> BoundsBox | None:
    candidates: list[tuple[int, BoundsBox]] = []
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None or not _looks_like_job_option(bounds):
            continue
        label = _job_option_label(node)
        if not label:
            continue
        if _is_disabled_job_option(label):
            continue
        score = sum(1 for marker in markers if marker in label)
        if score <= 0:
            continue
        candidates.append((score, bounds))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (item[0], -item[1].y1), reverse=True)
    return candidates[0][1]


def _visible_job_option_boxes(root: ElementTree.Element) -> list[BoundsBox]:
    boxes: list[BoundsBox] = []
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None or not _looks_like_job_option(bounds):
            continue
        if _is_disabled_job_option(_job_option_label(node)):
            continue
        boxes.append(bounds)
    boxes.sort(key=lambda item: item.y1)
    return boxes


def _looks_like_job_option(bounds: BoundsBox) -> bool:
    if bounds.y1 < 300 or bounds.y2 > 2150:
        return False
    if bounds.width < 900:
        return False
    return 180 <= bounds.height <= 320


def _job_option_label(node: ElementTree.Element) -> str:
    labels = [node_label(item) for item in node.iter("node")]
    return " ".join(label for label in labels if label)


def _is_disabled_job_option(label: str) -> bool:
    return any(marker in label for marker in DISABLED_JOB_OPTION_MARKERS)


def _normalize_role_keyword(role_keyword: str) -> str:
    return " ".join((role_keyword or "").replace("（", "(").replace("）", ")").split()).replace(
        "(", "（"
    ).replace(")", "）")
