from types import SimpleNamespace

from sqlalchemy import create_engine, text

from liepin_rpa import db_daily_report


def test_cumulative_metrics_by_job_use_latest_analysis_and_job_profile(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                CREATE TABLE candidate_resume_search_hits (
                    id INTEGER PRIMARY KEY,
                    candidate_fingerprint TEXT,
                    search_keyword TEXT,
                    hit_status TEXT
                );
                """
            )
        )
        connection.execute(
            text(
                """
                CREATE TABLE candidate_resume_source_locators (
                    id INTEGER PRIMARY KEY,
                    candidate_fingerprint TEXT,
                    job_profile_id TEXT
                );
                """
            )
        )
        connection.execute(
            text(
                """
                CREATE TABLE candidate_resume_analysis_cache (
                    id INTEGER PRIMARY KEY,
                    candidate_fingerprint TEXT,
                    analysis_version TEXT,
                    match_result TEXT,
                    analysis_reason TEXT,
                    experience TEXT,
                    evidence_summary_json TEXT
                );
                """
            )
        )
        connection.execute(
            text(
                """
                INSERT INTO candidate_resume_search_hits VALUES
                    (1, 'talent-history', '达人营销总监', 'sent'),
                    (2, 'finance-profile', '集团总账经理', 'sent'),
                    (3, 'profile-wins', '财务共享负责人', 'reviewed')
                """
            )
        )
        connection.execute(
            text(
                """
                INSERT INTO candidate_resume_source_locators VALUES
                    (1, 'finance-profile', 'finance_shared_management_v1'),
                    (2, 'profile-wins', 'daren_live_bd_v1')
                """
            )
        )
        connection.execute(
            text(
                """
                INSERT INTO candidate_resume_analysis_cache VALUES
                    (1, 'talent-history', 'legacy-v1', 'relevant', '', 'history', '[]'),
                    (2, 'finance-profile', 'finance-v2', 'relevant', '', 'finance', '[]'),
                    (3, 'profile-wins', 'finance-v2', 'needs_review', '', 'talent', '[]')
                """
            )
        )

    monkeypatch.setattr(db_daily_report, "get_engine", lambda settings: engine)
    settings = SimpleNamespace(coze_finance_analysis_version="finance-v2")

    result = db_daily_report._count_cumulative_report_metrics_by_job(settings)

    assert result == {
        "达人营销总监": {"relevant": 1, "needs_review": 1, "greeted": 1},
        "财务共享与管理经理": {"relevant": 1, "needs_review": 0, "greeted": 1},
    }
