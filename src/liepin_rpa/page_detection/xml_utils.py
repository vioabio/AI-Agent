from __future__ import annotations

from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree


def load_xml_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_xml_root(path: Path) -> ElementTree.Element:
    return ElementTree.fromstring(load_xml_text(path))


def load_xml_root_from_text(xml_text: str) -> ElementTree.Element:
    return ElementTree.fromstring(xml_text)


def iter_nodes(root: ElementTree.Element) -> Iterable[ElementTree.Element]:
    return root.iter("node")


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    return " ".join(value.split())


def node_attribute_matches(node: ElementTree.Element, key: str, expected: str) -> bool:
    actual = normalize_text(node.attrib.get(key))
    expected_normalized = normalize_text(expected)
    if key.endswith("_contains"):
        real_key = key.removesuffix("_contains")
        actual = normalize_text(node.attrib.get(real_key))
        return expected_normalized in actual
    return actual == expected_normalized


def any_node_matches(root: ElementTree.Element, attributes: dict[str, str]) -> bool:
    translated = {
        "resource_id": "resource-id",
        "content_desc": "content-desc",
        "content_desc_contains": "content-desc_contains",
        "text_contains": "text_contains",
        "class_name": "class",
    }
    for node in iter_nodes(root):
        if all(
            node_attribute_matches(node, translated.get(key, key), value)
            for key, value in attributes.items()
        ):
            return True
    return False
