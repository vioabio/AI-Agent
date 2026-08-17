from __future__ import annotations

from dataclasses import dataclass
from xml.etree import ElementTree

APP_PACKAGE = "com.lietou.mishu"
RESULT_FILTER_LABELS = ("综合排序", "全国", "经验", "学历", "更多")
SEARCH_PLACEHOLDER = "搜索职位、公司、行业等"
EMPTY_RESULT_TEXTS = ("这里暂无数据", "加载失败")
PROFILE_SECTION_LABELS = ("求职意向", "简历亮点", "工作经历")


@dataclass(slots=True)
class BoundsBox:
    x1: int
    y1: int
    x2: int
    y2: int

    @property
    def width(self) -> int:
        return self.x2 - self.x1

    @property
    def height(self) -> int:
        return self.y2 - self.y1

    @property
    def center(self) -> tuple[int, int]:
        return ((self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2)


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    return " ".join(value.split())


def parse_bounds(bounds: str) -> BoundsBox | None:
    if not bounds.startswith("[") or "][" not in bounds:
        return None
    left_top, right_bottom = bounds.split("][")
    x1, y1 = map(int, left_top[1:].split(","))
    x2, y2 = map(int, right_bottom[:-1].split(","))
    return BoundsBox(x1=x1, y1=y1, x2=x2, y2=y2)


def iter_app_nodes(root: ElementTree.Element, package: str = APP_PACKAGE):
    for node in root.iter("node"):
        if node.attrib.get("package", "") == package:
            yield node


def node_label(node: ElementTree.Element) -> str:
    for key in ("content-desc", "text", "hint"):
        value = normalize_text(node.attrib.get(key))
        if value:
            return value
    return ""


def node_has_label(node: ElementTree.Element, expected: str) -> bool:
    for key in ("content-desc", "text", "hint"):
        if normalize_text(node.attrib.get(key)) == expected:
            return True
    return False


def node_contains_label(node: ElementTree.Element, expected: str) -> bool:
    for key in ("content-desc", "text", "hint"):
        if expected in normalize_text(node.attrib.get(key)):
            return True
    return False


def any_label(root: ElementTree.Element, expected: str) -> bool:
    return any(node_has_label(node, expected) for node in iter_app_nodes(root))


def any_label_contains(root: ElementTree.Element, expected: str) -> bool:
    return any(node_contains_label(node, expected) for node in iter_app_nodes(root))


def _is_top_clickable_search_box(node: ElementTree.Element) -> bool:
    if node.attrib.get("clickable") != "true":
        return False
    bounds = parse_bounds(node.attrib.get("bounds", ""))
    if bounds is None:
        return False
    if bounds.y2 > 260:
        return False
    if bounds.width < 350 or bounds.height < 50 or bounds.height > 150:
        return False
    if node.attrib.get("class", "") == "android.widget.EditText":
        return True
    return (
        node_contains_label(node, "搜索职位")
        or node_contains_label(node, "搜索")
        or bounds.x1 >= 400
        or (bounds.x1 == 0 and bounds.width >= 900)
    )


def find_search_box(root: ElementTree.Element) -> tuple[int, int] | None:
    candidates: list[tuple[int, BoundsBox]] = []
    for node in iter_app_nodes(root):
        if not _is_top_clickable_search_box(node):
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        score = bounds.width
        if node.attrib.get("class", "") == "android.widget.EditText":
            score += 500
        if node_contains_label(node, SEARCH_PLACEHOLDER):
            score += 300
        candidates.append((score, bounds))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1].center


def find_search_submit(root: ElementTree.Element) -> tuple[int, int] | None:
    candidates: list[tuple[int, BoundsBox]] = []
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        if bounds.y2 > 240 or bounds.x1 < 850:
            continue
        if bounds.width > 180 or bounds.height > 100:
            continue
        score = bounds.x1
        if node_has_label(node, "搜索"):
            score += 500
        candidates.append((score, bounds))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1].center


def find_back_button(root: ElementTree.Element) -> tuple[int, int] | None:
    candidates: list[BoundsBox] = []
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        if bounds.x1 > 80 or bounds.y2 > 260:
            continue
        if bounds.width > 140 or bounds.height > 160:
            continue
        candidates.append(bounds)
    if not candidates:
        return None
    candidates.sort(key=lambda item: (item.y1, item.x1))
    return candidates[0].center


def find_clickable_labeled_center(
    root: ElementTree.Element,
    expected_labels: tuple[str, ...],
) -> tuple[int, int] | None:
    candidates: list[tuple[int, BoundsBox]] = []
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        label = node_label(node)
        if label in expected_labels:
            score = 1000 + bounds.y1
            candidates.append((score, bounds))
            continue
        for child in list(node):
            if node_label(child) in expected_labels:
                score = 1000 + bounds.y1
                candidates.append((score, bounds))
                break
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1].center


def find_profile_communication_action(
    root: ElementTree.Element,
) -> tuple[str, tuple[int, int]] | None:
    candidates: list[tuple[int, str, BoundsBox]] = []
    expected_labels = ("继续沟通", "立即沟通")
    for node in iter_app_nodes(root):
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        label = node_label(node)
        if label in expected_labels:
            score = 1000 + bounds.y1 if node.attrib.get("clickable") == "true" else bounds.y1
            if bounds.y1 >= 1600:
                score += 700
            candidates.append((score, label, bounds))
            continue
        if node.attrib.get("clickable") != "true":
            continue
        for descendant in node.iter("node"):
            if descendant is node:
                continue
            descendant_label = node_label(descendant)
            if descendant_label in expected_labels:
                candidates.append((1000 + bounds.y1, descendant_label, bounds))
                break
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    _, label, bounds = candidates[0]
    return label, bounds.center


def find_profile_communication_button(root: ElementTree.Element) -> tuple[int, int] | None:
    action = find_profile_communication_action(root)
    if action is None:
        return None
    return action[1]


def find_chat_input_center(root: ElementTree.Element) -> tuple[int, int] | None:
    candidates: list[tuple[int, BoundsBox]] = []
    for node in iter_app_nodes(root):
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        if bounds.y1 < 1800:
            continue
        label = node_label(node)
        class_name = node.attrib.get("class", "")
        score = 0
        if class_name == "android.widget.EditText":
            score += 1000
        if "请输入" in label or "发消息" in label or "消息" in label:
            score += 500
        if bounds.width >= 500 and bounds.height >= 80:
            score += 100
        if score <= 0:
            continue
        candidates.append((score, bounds))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1].center


def find_chat_send_button(root: ElementTree.Element) -> tuple[int, int] | None:
    labeled = find_clickable_labeled_center(root, ("发送",))
    if labeled is not None:
        return labeled

    candidates: list[tuple[int, BoundsBox]] = []
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        if bounds.y1 < 1800 or bounds.x1 < 820:
            continue
        if bounds.width > 220 or bounds.height > 180:
            continue
        score = bounds.x1 + bounds.y1
        candidates.append((score, bounds))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1].center


def has_result_filter_bar(root: ElementTree.Element) -> bool:
    return all(any_label(root, label) for label in RESULT_FILTER_LABELS)


def find_candidate_card_centers(root: ElementTree.Element) -> list[tuple[int, int]]:
    centers: list[tuple[int, int]] = []
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        if bounds.y1 < 360:
            continue
        if bounds.width < 850 or bounds.height < 150:
            continue
        centers.append(bounds.center)
    return centers


def has_empty_result_banner(root: ElementTree.Element) -> bool:
    return any(any_label_contains(root, text) for text in EMPTY_RESULT_TEXTS)


def has_home_recommend_nav(root: ElementTree.Element) -> bool:
    for node in iter_app_nodes(root):
        if not node_has_label(node, "推荐"):
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is not None and bounds.y1 >= 2100:
            return True
    return False


def _wide_bottom_action_count(root: ElementTree.Element) -> int:
    count = 0
    for node in iter_app_nodes(root):
        if node.attrib.get("clickable") != "true":
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        if bounds.y1 < 2150:
            continue
        if bounds.width < 380 or bounds.height < 120:
            continue
        count += 1
    return count


def is_home_recommend(root: ElementTree.Element) -> bool:
    return find_search_box(root) is not None and has_home_recommend_nav(root)


def is_search_condition(root: ElementTree.Element) -> bool:
    if has_result_filter_bar(root):
        return False
    if find_search_box(root) is None or find_search_submit(root) is None:
        return False
    return any_label(root, "按职位搜索") or any_label(root, "历史记录")


def is_search_entry(root: ElementTree.Element) -> bool:
    if has_result_filter_bar(root):
        return False
    if is_home_recommend(root) or is_search_condition(root):
        return False
    return find_search_box(root) is not None


def is_empty_result(root: ElementTree.Element) -> bool:
    return has_result_filter_bar(root) and not find_candidate_card_centers(root) and has_empty_result_banner(root)


def is_result_list(root: ElementTree.Element) -> bool:
    return has_result_filter_bar(root) and bool(find_candidate_card_centers(root))


def is_candidate_profile(root: ElementTree.Element) -> bool:
    has_actions = any_label(root, "获取电话") and any_label(root, "立即沟通")
    has_profile_sections = any(any_label(root, label) for label in PROFILE_SECTION_LABELS)
    if has_actions and has_profile_sections:
        return True
    return (
        not has_result_filter_bar(root)
        and find_back_button(root) is not None
        and _wide_bottom_action_count(root) >= 2
        and any_label_contains(root, "工作")
    )
