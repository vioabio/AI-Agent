from xml.etree import ElementTree

import liepin_rpa.search_execution.search_pages as search_pages_module
from liepin_rpa.search_execution.search_pages import SearchPageNavigator
from liepin_rpa.task_generation.models import SearchTaskSeed


class _FakeSearchDevice:
    def __init__(self) -> None:
        self.sent_text: str | None = None

    def tap(self, *_args: int) -> None:
        return None

    def send_keys(self, text: str, *, clear: bool = False) -> None:
        assert clear is True
        self.sent_text = text

    def dump_hierarchy_text(self) -> str:
        return "<hierarchy />"

    def device_info(self) -> dict[str, int]:
        return {"displayWidth": 1080, "displayHeight": 2388}


def test_jd_search_submits_effective_query_instead_of_pack_label(monkeypatch) -> None:
    device = _FakeSearchDevice()
    navigator = SearchPageNavigator(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
    )
    seed = SearchTaskSeed(
        brand_keyword="珀莱雅",
        role_keyword="达人营销/KOL种草",
        query_text="种草营销负责人 珀莱雅",
        strategy="jd_smart",
        search_pack_id="daren_kol",
    )
    monkeypatch.setattr(search_pages_module, "find_search_box", lambda _root: (1, 1))
    monkeypatch.setattr(search_pages_module, "find_search_submit", lambda _root: (2, 2))

    navigator.submit_exact_search(seed, ElementTree.Element("hierarchy"))

    assert device.sent_text == "种草营销负责人 珀莱雅"


def test_dismisses_non_clickable_resume_share_sheet() -> None:
    device = _FakeSearchDevice()
    navigator = SearchPageNavigator(device=device, detector=object(), settle_seconds=0)
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" text="温馨提示" />
          <node package="com.lietou.mishu" text="复制链接" />
          <node package="com.lietou.mishu" text="取消" clickable="false"
                bounds="[0,2100][1080,2200]" />
        </hierarchy>
        """
    )

    tapped: list[tuple[int, int]] = []
    device.tap = lambda x, y: tapped.append((x, y))  # type: ignore[method-assign]

    assert navigator.dismiss_interruption_if_present(root) is True
    assert tapped == [(540, 2150)]
