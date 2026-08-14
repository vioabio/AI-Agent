from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path


REPORT_CANDIDATE_HISTORY_FILENAME = "daily_report_candidate_history.json"


class ReportCandidateHistoryStore:
    def __init__(self, path: Path) -> None:
        self.path = path

    def load_fingerprints(self) -> set[str]:
        payload = self._load()
        fingerprints: set[str] = set()
        for item in payload.get("deliveries", []):
            if not isinstance(item, dict):
                continue
            fingerprints.update(
                str(value)
                for value in item.get("candidate_fingerprints", [])
                if str(value).strip()
            )
        return fingerprints

    def record_delivery(
        self,
        *,
        report_key: str,
        candidate_fingerprints: set[str],
        window_start_at: datetime | None,
        window_end_at: datetime | None,
        source: str = "formal_feishu",
    ) -> None:
        if not candidate_fingerprints:
            return
        payload = self._load()
        deliveries = payload.setdefault("deliveries", [])
        if not isinstance(deliveries, list):
            raise ValueError("Daily report candidate history is invalid.")
        existing = next(
            (
                item
                for item in deliveries
                if isinstance(item, dict) and item.get("report_key") == report_key
            ),
            None,
        )
        item = {
            "report_key": report_key,
            "source": source,
            "window_start_at": _format_datetime(window_start_at),
            "window_end_at": _format_datetime(window_end_at),
            "candidate_fingerprints": sorted(candidate_fingerprints),
            "recorded_at": datetime.now().isoformat(timespec="seconds"),
        }
        if existing is None:
            deliveries.append(item)
        else:
            existing.update(item)
        payload["deliveries"] = deliveries[-200:]
        self._save(payload)

    def _load(self) -> dict[str, object]:
        if not self.path.exists():
            return {"schema_version": 1, "deliveries": []}
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("Daily report candidate history must be a JSON object.")
        payload.setdefault("schema_version", 1)
        payload.setdefault("deliveries", [])
        return payload

    def _save(self, payload: dict[str, object]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.path)


def _format_datetime(value: datetime | None) -> str | None:
    return value.isoformat(timespec="seconds") if value is not None else None
