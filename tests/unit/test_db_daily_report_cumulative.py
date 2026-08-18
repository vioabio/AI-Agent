from types import SimpleNamespace

from sqlalchemy import create_engine, insert, select

from liepin_rpa import db_daily_report
from liepin_rpa.config import Settings
from liepin_rpa.db import (
    metadata,
    resume_analysis_cache,
    resume_experience_records,
    resume_search_hits,
    resume_source_locators,
)


def test_database_reanalysis_routes_job_specific_primary_and_backup_workflows() -> None:
    settings = Settings(
        _env_file=None,
        COZE_JD_WORKFLOW_ID="daren-primary",
        COZE_JD_BACKUP_WORKFLOW_ID="daren-backup",
        COZE_FINANCE_WORKFLOW_ID="finance-primary",
        COZE_FINANCE_BACKUP_WORKFLOW_ID="finance-backup",
    )

    daren = db_daily_report._analysis_settings_for_search_keyword(
        settings,
        "creator marketing director",
        job_profile_id="daren_live_bd_v1",
    )
    finance = db_daily_report._analysis_settings_for_search_keyword(
        settings,
        "group accounting manager",
        job_profile_id="finance_shared_management_v1",
    )

    assert daren.coze_workflow_id == "daren-primary"
    assert daren.coze_backup_workflow_id == "daren-backup"
    assert finance.coze_workflow_id == "finance-primary"
    assert finance.coze_backup_workflow_id == "finance-backup"


def test_cumulative_metrics_and_review_list_use_actionable_population(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    candidates = [
        "talent-history",
        "finance-profile",
        "profile-wins",
        "coze-system-failure",
        "low-score-review",
        "low-score-relevant",
        "suppressed-relevant",
        "suppressed-review",
    ]
    with engine.begin() as connection:
        connection.execute(
            insert(resume_search_hits),
            [
                {
                    "id": index,
                    "candidate_fingerprint": fingerprint,
                    "candidate_name": fingerprint,
                    "search_keyword": keyword,
                    "hit_status": hit_status,
                }
                for index, (fingerprint, keyword, hit_status) in enumerate(
                    [
                        ("talent-history", "达人营销总监", "sent"),
                        ("finance-profile", "集团总账经理", "sent"),
                        ("profile-wins", "财务共享负责人", "reviewed"),
                        ("coze-system-failure", "达人营销总监", "reviewed"),
                        ("low-score-review", "达人营销总监", "reviewed"),
                        ("low-score-relevant", "达人营销总监", "reviewed"),
                        ("suppressed-relevant", "达人营销总监", "reviewed"),
                        ("suppressed-review", "财务共享负责人", "reviewed"),
                    ],
                    start=1,
                )
            ],
        )
        connection.execute(
            insert(resume_source_locators),
            [
                {
                    "id": index,
                    "candidate_fingerprint": fingerprint,
                    "candidate_name": fingerprint,
                    "job_profile_id": profile_id,
                    "search_keyword": "测试关键词",
                }
                for index, (fingerprint, profile_id) in enumerate(
                    [
                        ("finance-profile", "finance_shared_management_v1"),
                        ("profile-wins", "daren_live_bd_v1"),
                        ("low-score-review", "daren_live_bd_v1"),
                        ("low-score-relevant", "daren_live_bd_v1"),
                        ("suppressed-relevant", "daren_live_bd_v1"),
                        ("suppressed-review", "finance_shared_management_v1"),
                    ],
                    start=1,
                )
            ],
        )
        analysis_rows = [
            ("talent-history", "legacy-v1", "relevant", 95, "", "history", "[]"),
            ("finance-profile", "finance-v2", "relevant", 96, "", "finance", "[]"),
            ("profile-wins", "finance-v2", "needs_review", 92, "", "talent", "[]"),
            (
                "coze-system-failure",
                "finance-v2",
                "needs_review",
                99,
                "需要人工复核：Coze API 临时异常，code=4000",
                "完整的候选人简历正文",
                '["已有完整简历证据"]',
            ),
            ("low-score-review", "finance-v2", "needs_review", 89, "", "talent", "[]"),
            ("low-score-relevant", "legacy-v1", "relevant", 70, "", "talent", "[]"),
            ("suppressed-relevant", "legacy-v1", "relevant", 95, "", "talent", "[]"),
            ("suppressed-review", "finance-v2", "needs_review", 95, "", "finance", "[]"),
        ]
        connection.execute(
            insert(resume_analysis_cache),
            [
                {
                    "id": index,
                    "candidate_fingerprint": fingerprint,
                    "experience_hash": f"hash-{index}",
                    "analysis_version": version,
                    "analysis_status": (
                        "匹配" if result == "relevant" else "待复核"
                    ),
                    "match_result": result,
                    "match_score": score,
                    "analysis_reason": reason,
                    "experience": experience,
                    "evidence_summary_json": evidence,
                }
                for index, (
                    fingerprint,
                    version,
                    result,
                    score,
                    reason,
                    experience,
                    evidence,
                ) in enumerate(analysis_rows, start=1)
            ],
        )
        connection.execute(
            insert(resume_experience_records),
            [
                {
                    "id": index,
                    "candidate_fingerprint": fingerprint,
                    "candidate_name": fingerprint,
                    "experience": f"候选人信息：{fingerprint}",
                    "manual_suppress_report": int(fingerprint.startswith("suppressed-")),
                }
                for index, fingerprint in enumerate(candidates, start=1)
            ],
        )

    monkeypatch.setattr(db_daily_report, "get_engine", lambda settings: engine)
    settings = SimpleNamespace(coze_finance_analysis_version="finance-v2")

    by_job = db_daily_report._count_cumulative_report_metrics_by_job(settings)
    cumulative = db_daily_report._count_cumulative_report_metrics(settings)
    review_groups, _ = db_daily_report._load_cumulative_review_candidates_by_job(settings)

    assert by_job == {
        "达人营销总监": {"relevant": 2, "needs_review": 1, "greeted": 1},
        "财务共享与管理经理": {"relevant": 1, "needs_review": 0, "greeted": 1},
    }
    assert cumulative == {
        "relevant": 3,
        "needs_review": 1,
        "not_relevant": 1,
        "greeted": 2,
    }
    assert [item.candidate_fingerprint for item in review_groups["达人营销总监"]] == [
        "profile-wins"
    ]
    assert review_groups["财务共享与管理经理"] == []

    # Manual disposition removes candidates from current/cumulative action lists,
    # but the historical AI rows remain available for audit.
    with engine.begin() as connection:
        retained = connection.execute(
            select(resume_analysis_cache.c.candidate_fingerprint).where(
                resume_analysis_cache.c.candidate_fingerprint.in_(
                    ["suppressed-relevant", "suppressed-review"]
                )
            )
        ).all()
    assert {row[0] for row in retained} == {"suppressed-relevant", "suppressed-review"}
