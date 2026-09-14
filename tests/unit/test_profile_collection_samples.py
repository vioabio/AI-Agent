from pathlib import Path
from xml.etree import ElementTree

import pytest

from liepin_rpa.profile_collection.collector import (
    CandidateProfileCollector,
    ProfileCollectionRecoverableError,
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


def test_collector_accepts_unknown_after_confirmed_profile_scroll(monkeypatch) -> None:
    profile_xml = """
        <hierarchy>
          <node package="com.lietou.mishu" text="立即沟通" bounds="[0,2200][500,2380]" />
          <node package="com.lietou.mishu" text="获取电话" bounds="[500,2200][1080,2380]" />
          <node package="com.lietou.mishu" text="工作经历" bounds="[20,300][300,360]" />
          <node package="com.lietou.mishu" text="某科技公司" bounds="[20,400][500,460]" />
        </hierarchy>
    """
    scrolled_xml = """
        <hierarchy>
          <node package="com.lietou.mishu" text="负责大模型项目交付" bounds="[20,400][800,460]" />
        </hierarchy>
    """

    class FakeDevice:
        def __init__(self) -> None:
            self.dumps = [profile_xml, scrolled_xml, scrolled_xml]
            self.index = 0

        def dump_hierarchy_text(self) -> str:
            value = self.dumps[min(self.index, len(self.dumps) - 1)]
            self.index += 1
            return value

        def current_app(self) -> dict[str, str]:
            return {"package": "com.lietou.mishu"}

        def swipe(self, *_args, **_kwargs) -> None:
            return None

    collector = CandidateProfileCollector(
        device=FakeDevice(),  # type: ignore[arg-type]
        max_stagnant_swipes=1,
        max_iterations=5,
    )
    monkeypatch.setattr(collector, "_settle_wait", lambda *_args, **_kwargs: None)

    report = collector.collect_current_profile()

    assert report.reached_bottom is True
    assert "负责大模型项目交付" in report.collected_text_lines


def test_collector_rejects_unknown_before_profile_is_confirmed() -> None:
    class FakeDevice:
        def dump_hierarchy_text(self) -> str:
            return """
                <hierarchy>
                  <node package="com.lietou.mishu" text="未知页面" bounds="[20,400][500,460]" />
                </hierarchy>
            """

        def current_app(self) -> dict[str, str]:
            return {"package": "com.lietou.mishu"}

    collector = CandidateProfileCollector(device=FakeDevice())  # type: ignore[arg-type]

    with pytest.raises(ProfileCollectionRecoverableError, match="not candidate profile"):
        collector.collect_current_profile()
