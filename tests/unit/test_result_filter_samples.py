from pathlib import Path
from xml.etree import ElementTree

from liepin_rpa.search_execution.result_filter import ResultPageFilter
from liepin_rpa.task_generation.models import SearchTaskSeed


def test_extract_visible_candidate_cards_from_saved_result_list() -> None:
    root = ElementTree.fromstring(
        Path("outputs/navigation_signatures/03_result_list/hierarchy.xml").read_text(
            encoding="utf-8"
        )
    )

    cards = ResultPageFilter.extract_visible_candidate_cards(root)
    seed = SearchTaskSeed(brand_keyword="珀莱雅", role_keyword="达播总监")

    assert len(cards) >= 3
    assert any(seed.brand_keyword in card.raw_text for card in cards)
    assert any("总监" in card.raw_text for card in cards)
    assert len({card.fingerprint for card in cards}) == len(cards)
