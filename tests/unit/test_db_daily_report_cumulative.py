from types import SimpleNamespace

from sqlalchemy import create_engine, insert, select, update

from liepin_rpa import db_daily_report
from liepin_rpa.config import Settings
from liepin_rpa.db import (
    metadata,
    resume_analysis_cache,
    resume_experience_records,
    resume_search_hits,
    resume_source_locators,
)


def test_database_report_resolves_job_versions_without_provider_side_effects() -> None:
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

    assert daren.coze_analysis_version == settings.coze_jd_analysis_version
    assert finance.coze_analysis_version == settings.coze_finance_analysis_version


def test_database_reanalysis_uses_active_openai_compatible_versions() -> None:
    settings = Settings(
        _env_file=None,
        RESUME_ANALYSIS_PROVIDER="openai_compatible",
        LLM_JD_ANALYSIS_VERSION="daren-llm-current",
        LLM_FINANCE_ANALYSIS_VERSION="finance-llm-current",
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

    assert daren.coze_analysis_version == "daren-llm-current"
    assert finance.coze_analysis_version == "finance-llm-current"


def test_latest_analysis_map_does_not_revive_stale_coze_score(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    with engine.begin() as connection:
        connection.execute(
            insert(resume_analysis_cache),
            [
                {
                    "id": 1,
                    "candidate_fingerprint": "finance-candidate",
                    "experience_hash": "old-hash",
                    "analysis_version": "finance-coze-old",
                    "analysis_status": "待复核",
                    "match_result": "needs_review",
                    "match_score": 100,
                },
                {
                    "id": 2,
                    "candidate_fingerprint": "finance-candidate",
                    "experience_hash": "current-hash",
                    "analysis_version": "finance-llm-current",
                    "analysis_status": "匹配",
                    "match_result": "relevant",
                    "match_score": 89,
                },
            ],
        )

    monkeypatch.setattr(db_daily_report, "get_engine", lambda settings: engine)
    settings = Settings(
        _env_file=None,
        RESUME_ANALYSIS_PROVIDER="openai_compatible",
        COZE_FINANCE_ANALYSIS_VERSION="finance-coze-old",
        LLM_FINANCE_ANALYSIS_VERSION="finance-llm-current",
    )

    selected = db_daily_report._load_latest_analysis_map(settings)["finance-candidate"]

    assert selected["analysis_version"] == "finance-llm-current"
    assert selected["match_score"] == 89


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
                    "experience_hash": f"hash-{index}",
                    "job_profile_id": profile_id,
                    "search_keyword": "测试关键词",
                }
                for index, (fingerprint, profile_id) in enumerate(
                    [
                        ("talent-history", "daren_live_bd_v1"),
                        ("finance-profile", "finance_shared_management_v1"),
                        ("profile-wins", "daren_live_bd_v1"),
                        ("coze-system-failure", "daren_live_bd_v1"),
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
            ("talent-history", "jd-v2", "relevant", 95, "", "history", "[]"),
            ("finance-profile", "finance-v2", "relevant", 96, "", "finance", "[]"),
            ("profile-wins", "jd-v2", "needs_review", 92, "", "talent", "[]"),
            (
                "coze-system-failure",
                "jd-v2",
                "needs_review",
                99,
                "需要人工复核：Coze API 临时异常，code=4000",
                "完整的候选人简历正文",
                '["已有完整简历证据"]',
            ),
            ("low-score-review", "jd-v2", "needs_review", 89, "", "talent", "[]"),
            ("low-score-relevant", "jd-v2", "relevant", 70, "", "talent", "[]"),
            ("suppressed-relevant", "jd-v2", "relevant", 95, "", "talent", "[]"),
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
                    "experience_hash": f"hash-{index}",
                    "experience": f"候选人信息：{fingerprint}",
                    "manual_suppress_report": int(fingerprint.startswith("suppressed-")),
                }
                for index, fingerprint in enumerate(candidates, start=1)
            ],
        )

    monkeypatch.setattr(db_daily_report, "get_engine", lambda settings: engine)
    settings = SimpleNamespace(
        coze_jd_analysis_version="jd-v2",
        coze_finance_analysis_version="finance-v2",
    )

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


def test_report_candidate_membership_uses_batch_id_not_timestamp(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    same_time = db_daily_report.datetime(2026, 8, 18, 9, 0)
    with engine.begin() as connection:
        for identifier, fingerprint, batch_id in (
            (101, "batch-a-candidate", "batch-a"),
            (102, "batch-b-candidate", "batch-b"),
        ):
            experience_hash = f"batch-hash-{identifier}"
            connection.execute(
                insert(resume_experience_records).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=fingerprint,
                    experience_hash=experience_hash,
                    experience=f"候选人信息：{fingerprint}",
                )
            )
            connection.execute(
                insert(resume_search_hits).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=fingerprint,
                    search_keyword="同一时间执行",
                    search_batch_id=batch_id,
                    hit_status="hit",
                    created_at=same_time,
                )
            )
            connection.execute(
                insert(resume_source_locators).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=fingerprint,
                    experience_hash=experience_hash,
                    job_profile_id="daren_live_bd_v1",
                    search_keyword="同一时间执行",
                    search_batch_id=batch_id,
                    created_at=same_time,
                )
            )
            connection.execute(
                insert(resume_analysis_cache).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    experience_hash=experience_hash,
                    analysis_version="jd-v2",
                    analysis_status="待复核",
                    match_result="needs_review",
                    match_score=95,
                    created_at=same_time,
                )
            )

    monkeypatch.setattr(db_daily_report, "get_engine", lambda _settings: engine)
    settings = SimpleNamespace(
        coze_jd_analysis_version="jd-v2",
        coze_finance_analysis_version="finance-v2",
    )
    records = db_daily_report._load_report_candidate_records(
        settings,  # type: ignore[arg-type]
        limit=None,
        window_start_at=db_daily_report.datetime(2026, 8, 18, 0, 0),
        window_end_at=db_daily_report.datetime(2026, 8, 19, 0, 0),
        search_batch_id="batch-a",
    )

    assert [record["candidate_fingerprint"] for record in records] == [
        "batch-a-candidate"
    ]


def test_report_excludes_recent_analysis_skip_hits_at_query_boundary(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    analyzed_at = db_daily_report.datetime(2026, 8, 18, 9, 0)
    with engine.begin() as connection:
        for identifier, fingerprint, hit_status in (
            (201, "new-candidate", "message_sent"),
            (202, "recent-candidate", "skipped_recent_analysis"),
        ):
            experience_hash = f"report-hash-{identifier}"
            connection.execute(
                insert(resume_experience_records).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=fingerprint,
                    experience_hash=experience_hash,
                    experience=f"候选人信息：{fingerprint}",
                )
            )
            connection.execute(
                insert(resume_search_hits).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=fingerprint,
                    search_keyword="FDE实施交付",
                    search_batch_id="batch-fde",
                    hit_status=hit_status,
                    created_at=analyzed_at,
                )
            )
            connection.execute(
                insert(resume_source_locators).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    candidate_name=fingerprint,
                    experience_hash=experience_hash,
                    job_profile_id="fde_ai_deployment_engineer_v1",
                    search_keyword="FDE实施交付",
                    search_batch_id="batch-fde",
                    created_at=analyzed_at,
                )
            )
            connection.execute(
                insert(resume_analysis_cache).values(
                    id=identifier,
                    candidate_fingerprint=fingerprint,
                    experience_hash=experience_hash,
                    analysis_version="fde-v2",
                    analysis_status="匹配",
                    match_result="relevant",
                    match_score=90,
                    created_at=analyzed_at,
                )
            )

    monkeypatch.setattr(db_daily_report, "get_engine", lambda _settings: engine)
    settings = SimpleNamespace(
        coze_jd_analysis_version="jd-v2",
        coze_finance_analysis_version="finance-v2",
        llm_fde_analysis_version="fde-v2",
    )
    records = db_daily_report._load_report_candidate_records(
        settings,  # type: ignore[arg-type]
        limit=None,
        window_start_at=None,
        window_end_at=None,
        search_batch_id="batch-fde",
    )

    assert [record["candidate_fingerprint"] for record in records] == [
        "new-candidate"
    ]


def test_report_keeps_current_send_when_later_duplicate_is_analysis_skipped(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    analyzed_at = db_daily_report.datetime(2026, 8, 18, 9, 0)
    with engine.begin() as connection:
        connection.execute(
            insert(resume_experience_records).values(
                id=301,
                candidate_fingerprint="sent-then-skipped",
                candidate_name="重复候选人",
                experience_hash="sent-then-skipped-hash",
                experience="候选人信息：已发送",
            )
        )
        connection.execute(
            insert(resume_search_hits),
            [
                {
                    "id": 301,
                    "candidate_fingerprint": "sent-then-skipped",
                    "candidate_name": "重复候选人",
                    "search_keyword": "FDE实施交付",
                    "search_batch_id": "batch-fde",
                    "hit_status": "message_sent",
                    "created_at": analyzed_at,
                },
                {
                    "id": 302,
                    "candidate_fingerprint": "sent-then-skipped",
                    "candidate_name": "重复候选人",
                    "search_keyword": "AI应用部署",
                    "search_batch_id": "batch-fde",
                    "hit_status": "skipped_recent_analysis",
                    "created_at": analyzed_at,
                },
            ],
        )
        connection.execute(
            insert(resume_source_locators).values(
                id=301,
                candidate_fingerprint="sent-then-skipped",
                candidate_name="重复候选人",
                experience_hash="sent-then-skipped-hash",
                job_profile_id="fde_ai_deployment_engineer_v1",
                search_keyword="FDE实施交付",
                search_batch_id="batch-fde",
                created_at=analyzed_at,
            )
        )
        connection.execute(
            insert(resume_analysis_cache).values(
                id=301,
                candidate_fingerprint="sent-then-skipped",
                experience_hash="sent-then-skipped-hash",
                analysis_version="fde-v2",
                analysis_status="匹配",
                match_result="relevant",
                match_score=90,
                created_at=analyzed_at,
            )
        )

    monkeypatch.setattr(db_daily_report, "get_engine", lambda _settings: engine)
    settings = SimpleNamespace(
        coze_jd_analysis_version="jd-v2",
        coze_finance_analysis_version="finance-v2",
        llm_fde_analysis_version="fde-v2",
    )
    records = db_daily_report._load_report_candidate_records(
        settings,  # type: ignore[arg-type]
        limit=None,
        window_start_at=None,
        window_end_at=None,
        search_batch_id="batch-fde",
    )

    assert len(records) == 1
    assert records[0]["hit_status"] == "message_sent"


def test_report_selects_current_resume_and_policy_per_job(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    analyzed_at = db_daily_report.datetime.now()
    with engine.begin() as connection:
        connection.execute(
            insert(resume_experience_records).values(
                id=1,
                candidate_fingerprint="cross-job",
                candidate_name="脱敏候选人",
                experience_hash="resume-v2",
                experience="候选人信息：脱敏测试正文",
            )
        )
        connection.execute(
            insert(resume_search_hits).values(
                id=1,
                candidate_fingerprint="cross-job",
                candidate_name="脱敏候选人",
                search_keyword="测试任务",
                search_batch_id="batch-current",
                hit_status="hit",
                created_at=analyzed_at,
            )
        )
        connection.execute(
            insert(resume_source_locators),
            [
                {
                    "id": identifier,
                    "candidate_fingerprint": "cross-job",
                    "experience_hash": "resume-v2",
                    "job_profile_id": job_profile_id,
                    "search_keyword": "测试任务",
                    "search_batch_id": "batch-current",
                    "created_at": analyzed_at,
                }
                for identifier, job_profile_id in enumerate(
                    ("daren_live_bd_v1", "finance_shared_management_v1"), start=1
                )
            ],
        )
        connection.execute(
            insert(resume_analysis_cache),
            [
                {
                    "id": 1,
                    "candidate_fingerprint": "cross-job",
                    "experience_hash": "resume-v1",
                    "analysis_version": "jd-v2",
                    "analysis_status": "匹配",
                    "match_result": "relevant",
                    "match_score": 99,
                    "experience": "旧简历正文",
                    "created_at": analyzed_at,
                },
                {
                    "id": 2,
                    "candidate_fingerprint": "cross-job",
                    "experience_hash": "resume-v2",
                    "analysis_version": "jd-v2",
                    "analysis_status": "匹配",
                    "match_result": "relevant",
                    "match_score": 81,
                    "experience": "当前简历正文",
                    "created_at": analyzed_at,
                },
                {
                    "id": 3,
                    "candidate_fingerprint": "cross-job",
                    "experience_hash": "resume-v2",
                    "analysis_version": "finance-v2",
                    "analysis_status": "匹配",
                    "match_result": "relevant",
                    "match_score": 94,
                    "experience": "当前简历正文",
                    "created_at": analyzed_at,
                },
            ],
        )

    monkeypatch.setattr(db_daily_report, "get_engine", lambda _settings: engine)
    settings = SimpleNamespace(
        coze_jd_analysis_version="jd-v2",
        coze_finance_analysis_version="finance-v2",
    )
    records = db_daily_report._load_report_candidate_records(
        settings,  # type: ignore[arg-type]
        limit=None,
        window_start_at=None,
        window_end_at=None,
        search_batch_id="batch-current",
    )
    assert {(record["job_profile_id"], record["match_score"]) for record in records} == {
        ("daren_live_bd_v1", 81),
        ("finance_shared_management_v1", 94),
    }

    with engine.begin() as connection:
        connection.execute(
            update(resume_experience_records)
            .where(resume_experience_records.c.candidate_fingerprint == "cross-job")
            .values(experience_hash="resume-v3", experience="候选人信息：新版本正文")
        )
    assert (
        db_daily_report._load_report_candidate_records(
            settings,  # type: ignore[arg-type]
            limit=None,
            window_start_at=None,
            window_end_at=None,
            search_batch_id="batch-current",
        )
        == []
    )
