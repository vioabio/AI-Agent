from __future__ import annotations

from pathlib import Path

import yaml

from liepin_rpa.page_detection.models import SelectorCandidate, SignatureRule


class SelectorRepository:
    def __init__(self, base_dir: Path) -> None:
        self.base_dir = base_dir
        self._entries = self._load_entries()

    def _load_entries(self) -> dict[str, dict[str, object]]:
        entries: dict[str, dict[str, object]] = {}
        for path in sorted(self.base_dir.glob("*.yaml")):
            payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            for key, value in payload.items():
                entries[key] = value
        return entries

    def get_candidates(self, name: str) -> list[SelectorCandidate]:
        entry = self._entries[name]
        candidates = entry.get("candidates", [])
        return [SelectorCandidate(attributes=dict(candidate)) for candidate in candidates]

    def get_signature(self, name: str) -> SignatureRule:
        entry = self._entries[name]
        all_of = self._expand_signature_items(entry.get("all_of", []))
        any_of = self._expand_signature_items(entry.get("any_of", []))
        return SignatureRule(name=name, all_of=all_of, any_of=any_of)

    def _expand_signature_items(self, items: list[object]) -> list[SelectorCandidate]:
        expanded: list[SelectorCandidate] = []
        for item in items:
            if isinstance(item, dict) and "selector" in item:
                expanded.extend(self.get_candidates(str(item["selector"])))
            elif isinstance(item, dict):
                expanded.append(SelectorCandidate(attributes=dict(item)))
        return expanded
