from __future__ import annotations

from datetime import datetime
from types import SimpleNamespace

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


def test_window_hit_map_keeps_communication_status_inside_batch() -> None:
    batch_records = [
        {
            "candidate_fingerprint": "candidate-a",
            "hit_status": "continue_without_db_record",
            "created_at": datetime(2026, 8, 19, 8, 36, 9),
        }
    ]

    window_hits = db_daily_report._build_window_hit_map(batch_records)

    assert window_hits["candidate-a"]["hit_status"] == "continue_without_db_record"


def test_window_hit_map_counts_new_message_status_only_in_its_own_batch() -> None:
    previous_batch = db_daily_report._build_window_hit_map(
        [
            {
                "candidate_fingerprint": "candidate-a",
                "hit_status": "continue_without_db_record",
                "created_at": datetime(2026, 8, 19, 8, 36, 9),
            }
        ]
    )
    next_batch = db_daily_report._build_window_hit_map(
        [
            {
                "candidate_fingerprint": "candidate-a",
                "hit_status": "message_sent",
                "created_at": datetime(2026, 8, 20, 8, 24, 52),
            }
        ]
    )

    assert previous_batch["candidate-a"]["hit_status"] == "continue_without_db_record"
    assert next_batch["candidate-a"]["hit_status"] == "message_sent"


def test_report_history_watermark_uses_same_actionable_population(monkeypatch) -> None:
    candidates = [
        ReportCandidate("relevant-low", "低分匹配", match_result="relevant", match_score=40),
        ReportCandidate("review-90", "90分待复核", match_result="needs_review", match_score=90),
        ReportCandidate("review-89", "89分待复核", match_result="needs_review", match_score=89),
        ReportCandidate("not-relevant", "不匹配", match_result="not_relevant", match_score=99),
    ]
    monkeypatch.setattr(db_daily_report, "build_report_candidates", lambda _outcomes: candidates)

    assert db_daily_report._select_actionable_candidate_fingerprints([]) == {
        "relevant-low",
        "review-90",
    }


def test_recent_analysis_skip_hit_is_excluded_from_batch_report(monkeypatch) -> None:
    monkeypatch.setattr(db_daily_report, "_load_latest_analysis_map", lambda _settings: {})
    monkeypatch.setattr(
        db_daily_report,
        "_build_candidate_outcome",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("recent-analysis skip must not rebuild a report outcome")
        ),
    )

    assert db_daily_report._build_candidate_outcomes(
        object(),  # type: ignore[arg-type]
        latest_hits={"candidate-a": {"hit_status": "reviewed"}},
        contacted_records=[
            {
                "candidate_fingerprint": "candidate-a",
                "hit_status": "skipped_recent_analysis",
            }
        ],
        use_cache=True,
    ) == []


def test_candidate_analyzed_before_batch_is_excluded_even_without_new_skip_hit(
    monkeypatch,
) -> None:
    monkeypatch.setattr(db_daily_report, "_load_latest_analysis_map", lambda _settings: {})
    monkeypatch.setattr(
        db_daily_report,
        "_load_prior_analysis_identity_scope",
        lambda *_args, **_kwargs: ({"candidate-a"}, []),
    )
    monkeypatch.setattr(
        db_daily_report,
        "_build_candidate_outcome",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("prior-batch analysis must not enter the report")
        ),
    )

    assert db_daily_report._build_candidate_outcomes(
        object(),  # type: ignore[arg-type]
        latest_hits={"candidate-a": {"hit_status": "reviewed"}},
        contacted_records=[
            {
                "candidate_fingerprint": "candidate-a",
                "hit_status": "reviewed",
            }
        ],
        use_cache=True,
        search_batch_id="batch-new",
        batch_started_at=datetime(2026, 8, 20, 8, 0, 0),
    ) == []


def test_current_batch_send_is_reported_even_if_candidate_was_analyzed_before_batch(
    monkeypatch,
) -> None:
    monkeypatch.setattr(db_daily_report, "_load_latest_analysis_map", lambda _settings: {})
    monkeypatch.setattr(
        db_daily_report,
        "_load_prior_analysis_identity_scope",
        lambda *_args, **_kwargs: ({"candidate-a"}, []),
    )
    monkeypatch.setattr(
        db_daily_report,
        "_build_candidate_outcome",
        lambda *_args, **kwargs: SimpleNamespace(
            candidate_fingerprint=kwargs["candidate_fingerprint"],
            hit_status="message_sent",
        ),
    )
    monkeypatch.setattr(db_daily_report, "_is_coze_api_failure_outcome", lambda _outcome: False)

    outcomes = db_daily_report._build_candidate_outcomes(
        object(),  # type: ignore[arg-type]
        latest_hits={"candidate-a": {"hit_status": "message_sent"}},
        contacted_records=[
            {
                "candidate_fingerprint": "candidate-a",
                "hit_status": "message_sent",
            }
        ],
        use_cache=True,
        search_batch_id="batch-new",
        batch_started_at=datetime(2026, 8, 20, 8, 0, 0),
    )

    assert len(outcomes) == 1
    assert outcomes[0].hit_status == "message_sent"


def test_missing_hit_fact_never_defaults_to_sent() -> None:
    outcome = db_daily_report._build_candidate_outcome(
        object(),  # type: ignore[arg-type]
        candidate_fingerprint="candidate-without-hit",
        record={
            "candidate_name": "候选人",
            "experience": "工作内容：负责项目交付。",
            "analysis_status": "未分析",
        },
        latest_hit={},
        use_cache=True,
    )

    assert outcome is not None
    assert outcome.hit_status == "reviewed"
    assert outcome.message_send_success is False
