from __future__ import annotations

from pathlib import Path

from liepin_rpa.page_detection.detector import PageDetector
from liepin_rpa.page_detection.selectors import SelectorRepository


def build_page_detector(selectors_dir: Path | None = None) -> PageDetector:
    base_dir = selectors_dir or Path("config") / "selectors"
    repository = SelectorRepository(base_dir)
    return PageDetector(repository)
