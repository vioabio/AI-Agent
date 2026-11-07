from xml.etree import ElementTree

from liepin_rpa.ui_controls import find_profile_communication_action


def test_profile_communication_action_detects_bottom_text_node() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" text="获取电话" bounds="[80,2050][400,2200]" />
          <node package="com.lietou.mishu" text="继续沟通" bounds="[520,2050][960,2200]" />
        </hierarchy>
        """
    )

    assert find_profile_communication_action(root) == ("继续沟通", (740, 2125))


def test_profile_communication_action_detects_deep_clickable_descendant() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" clickable="true" bounds="[420,2040][1010,2210]">
            <node package="com.lietou.mishu" bounds="[500,2070][960,2170]">
              <node package="com.lietou.mishu" text="立即沟通" bounds="[610,2090][850,2150]" />
            </node>
          </node>
        </hierarchy>
        """
    )

    assert find_profile_communication_action(root) == ("立即沟通", (715, 2125))
