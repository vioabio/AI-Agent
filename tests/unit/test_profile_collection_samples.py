from pathlib import Path
from xml.etree import ElementTree

from liepin_rpa.profile_collection.collector import (
    build_swipe_fingerprint,
    extract_candidate_interaction_note,
    extract_visible_profile_text_nodes,
    find_expand_all_controls,
)

FIXTURES_DIR = Path(__file__).parents[1] / "fixtures"


def test_profile_helpers_parse_saved_candidate_profile() -> None:
    root = ElementTree.fromstring(
        (FIXTURES_DIR / "candidate_profile.xml").read_text(
            encoding="utf-8"
        )
    )

    expand_controls = find_expand_all_controls(root)
    visible_text_nodes = extract_visible_profile_text_nodes(root)

    assert len(expand_controls) >= 1
    assert expand_controls[0].label.startswith("展开全部")
    assert len(visible_text_nodes) >= 4
    assert visible_text_nodes[0].label == "陈**"
    assert len(build_swipe_fingerprint([node.label for node in visible_text_nodes])) >= 4


def test_interaction_banner_is_saved_as_note_but_removed_from_resume_header() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" text="你们之前HR找过我" bounds="[20,100][400,150]" />
          <node package="com.lietou.mishu" text="2" bounds="[410,100][440,150]" />
          <node package="com.lietou.mishu" text="毛先生" bounds="[20,210][200,260]" />
          <node package="com.lietou.mishu" text="某公司 · 红人负责人" bounds="[20,270][500,320]" />
        </hierarchy>
        """
    )

    labels = [node.label for node in extract_visible_profile_text_nodes(root)]

    assert extract_candidate_interaction_note(root) == "你们之前HR找过我"
    assert labels == ["毛先生", "某公司 · 红人负责人"]
