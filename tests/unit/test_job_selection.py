from xml.etree import ElementTree

from pathlib import Path

from liepin_rpa.search_execution.job_selection import classify_role_group, find_role_option_center
from liepin_rpa.task_generation.config_loader import load_role_keywords


def test_all_configured_roles_use_daren_live_job() -> None:
    roles = load_role_keywords(Path("config/roles.yaml"))

    assert roles
    assert all(classify_role_group(role.raw_title) == "daren_live" for role in roles)


def test_finance_shared_role_uses_finance_job_group() -> None:
    assert classify_role_group("财务共享与管理经理") == "finance_shared"


def test_fde_role_uses_dedicated_job_group() -> None:
    assert classify_role_group("AI前沿部署工程师（FDE）") == "fde"


def test_ai_development_role_uses_dedicated_job_group() -> None:
    assert classify_role_group("AI开发工程师") == "ai_development"
    assert classify_role_group("Python AI开发工程师") == "ai_development"


def test_finance_job_matches_live_job_title_without_position_fallback() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" text="选择开聊职位" bounds="[48,246][444,339]" />
          <node package="com.lietou.mishu" clickable="true" bounds="[0,363][1080,614]"
                text="财务共享及管理经理&#10;上海-黄浦区&#10;统招本科&#10;8年以上&#10;20-30k" />
          <node package="com.lietou.mishu" clickable="true" bounds="[0,614][1080,864]"
                text="副总经理/咨询总监&#10;上海-黄浦区&#10;统招本科&#10;5年以上&#10;30-60k" />
          <node package="com.lietou.mishu" text="确定" bounds="[36,2208][1044,2352]" />
        </hierarchy>
        """
    )

    assert find_role_option_center(
        root,
        "finance_shared",
        allow_position_fallback=False,
    ) == (540, 488)


def test_finance_job_does_not_fallback_to_unrelated_position() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" clickable="true" bounds="[0,363][1080,614]"
                text="副总经理/咨询总监" />
          <node package="com.lietou.mishu" clickable="true" bounds="[0,614][1080,864]"
                text="总裁助理" />
        </hierarchy>
        """
    )

    assert (
        find_role_option_center(
            root,
            "finance_shared",
            allow_position_fallback=False,
        )
        is None
    )


def test_fde_job_requires_an_explicit_matching_position() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" clickable="true" bounds="[0,363][1080,614]"
                text="AI前沿部署工程师&#10;上海-黄浦区&#10;本科&#10;2年以上" />
          <node package="com.lietou.mishu" clickable="true" bounds="[0,614][1080,864]"
                text="市场运营总监" />
        </hierarchy>
        """
    )

    assert find_role_option_center(root, "fde", allow_position_fallback=False) == (540, 488)


def test_fde_job_does_not_fallback_to_unrelated_position() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" clickable="true" bounds="[0,363][1080,614]"
                text="市场运营总监" />
          <node package="com.lietou.mishu" clickable="true" bounds="[0,614][1080,864]"
                text="财务共享及管理经理" />
        </hierarchy>
        """
    )

    assert find_role_option_center(root, "fde", allow_position_fallback=False) is None


def test_ai_development_job_matches_exact_live_job_without_position_fallback() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" clickable="true" bounds="[20,360][1060,616]"
                text="AI前沿部署工程师（FDE）" />
          <node package="com.lietou.mishu" clickable="true" bounds="[20,650][1060,906]"
                text="AI开发工程师" />
          <node package="com.lietou.mishu" clickable="true" bounds="[20,940][1060,1196]"
                text="财务共享与管理经理" />
        </hierarchy>
        """
    )

    assert find_role_option_center(
        root,
        "ai_development",
        allow_position_fallback=False,
    ) == (540, 778)


def test_ai_development_job_does_not_fallback_to_fde_or_other_position() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" clickable="true" bounds="[20,360][1060,616]"
                text="AI前沿部署工程师（FDE）" />
          <node package="com.lietou.mishu" clickable="true" bounds="[20,650][1060,906]"
                text="市场运营总监" />
        </hierarchy>
        """
    )

    assert (
        find_role_option_center(
            root,
            "ai_development",
            allow_position_fallback=False,
        )
        is None
    )


def test_position_fallback_skips_anheyan_job() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" clickable="true" bounds="[40,400][1040,620]">
            <node package="com.lietou.mishu" text="安和颜负责人" bounds="[80,440][500,520]" />
          </node>
          <node package="com.lietou.mishu" clickable="true" bounds="[40,680][1040,900]">
            <node package="com.lietou.mishu" text="其他在招职位" bounds="[80,720][500,800]" />
          </node>
        </hierarchy>
        """
    )

    assert find_role_option_center(
        root,
        "brand_market",
        allow_position_fallback=True,
    ) == (540, 790)


def test_position_fallback_stops_when_only_anheyan_job_is_available() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" clickable="true" bounds="[40,400][1040,620]">
            <node package="com.lietou.mishu" text="安和颜负责人" bounds="[80,440][500,520]" />
          </node>
        </hierarchy>
        """
    )

    assert (
        find_role_option_center(
            root,
            "brand_market",
            allow_position_fallback=True,
        )
        is None
    )
