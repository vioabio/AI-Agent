from pathlib import Path
from xml.etree import ElementTree

from liepin_rpa.profile_collection.collector import (
    build_swipe_fingerprint,
    extract_visible_profile_text_nodes,
    find_expand_all_controls,
)


def test_profile_helpers_parse_saved_candidate_profile() -> None:
    root = ElementTree.fromstring(
        Path("outputs/navigation_signatures/04_candidate_profile/hierarchy.xml").read_text(
            encoding="utf-8"
        )
    )

    expand_controls = find_expand_all_controls(root)
    visible_text_nodes = extract_visible_profile_text_nodes(root)

    assert len(expand_controls) >= 1
    assert expand_controls[0].label.startswith("展开全部")
    assert len(visible_text_nodes) >= 15
    assert visible_text_nodes[0].label == "陈**"
    assert len(build_swipe_fingerprint([node.label for node in visible_text_nodes])) >= 10
