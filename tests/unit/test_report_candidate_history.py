from __future__ import annotations

from datetime import datetime

from liepin_rpa import db_daily_report
from liepin_rpa.feishu_report import ReportCandidate
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


def test_report_history_watermark_uses_same_actionable_population(monkeypatch) -> None:
    candidates = [
        ReportCandidate("relevant-low", "低分匹配", match_result="relevant", match_score=40),
        ReportCandidate("review-80", "80分待复核", match_result="needs_review", match_score=80),
        ReportCandidate("review-79", "79分待复核", match_result="needs_review", match_score=79),
        ReportCandidate("not-relevant", "不匹配", match_result="not_relevant", match_score=99),
    ]
    monkeypatch.setattr(db_daily_report, "build_report_candidates", lambda _outcomes: candidates)

    assert db_daily_report._select_actionable_candidate_fingerprints([]) == {
        "review-80",
    }
