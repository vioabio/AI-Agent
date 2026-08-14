from __future__ import annotations

from datetime import datetime

from liepin_rpa.report_candidate_history import ReportCandidateHistoryStore


def test_report_candidate_history_deduplicates_deliveries(tmp_path) -> None:
    store = ReportCandidateHistoryStore(tmp_path / "history.json")
    store.record_delivery(
        report_key="batch-one",
        candidate_fingerprints={"candidate-a", "candidate-b"},
        window_start_at=datetime(2026, 8, 12, 9, 0, 0),
        window_end_at=datetime(2026, 8, 12, 18, 0, 0),
    )
    store.record_delivery(
        report_key="batch-one",
        candidate_fingerprints={"candidate-a", "candidate-b"},
        window_start_at=datetime(2026, 8, 12, 9, 0, 0),
        window_end_at=datetime(2026, 8, 12, 18, 0, 0),
    )

    assert store.load_fingerprints() == {"candidate-a", "candidate-b"}
