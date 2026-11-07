from types import SimpleNamespace

from sqlalchemy import create_engine, insert

from liepin_rpa import db as db_module
from liepin_rpa.communication_status_policy import (
    normalize_communication_status,
    prefer_communication_hit_status,
)
from liepin_rpa.db import (
    get_candidate_record_by_experience_hash,
    metadata,
    resume_analysis_cache,
    resume_experience_records,
    resume_search_hits,
    resume_source_locators,
    was_analyzed_in_prior_batch_within_days,
    was_contacted_within_days,
)


def test_real_send_statuses_exclude_immediate_job_confirmation() -> None:
    assert normalize_communication_status("sent") == "sent"
    assert normalize_communication_status("message_sent") == "sent"
    assert normalize_communication_status("immediate_job_confirmed") == "not_sent"
    assert prefer_communication_hit_status("message_sent", "reviewed") == "message_sent"


def test_global_cooldown_uses_only_real_sent_events_across_jobs(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    with engine.begin() as connection:
        connection.execute(
            insert(resume_search_hits).values(
                id=1,
                candidate_fingerprint="global-candidate",
                candidate_name="脱敏候选人",
                search_keyword="达人营销负责人",
                hit_status="immediate_job_confirmed",
            )
        )
    monkeypatch.setattr(db_module, "get_engine", lambda _settings: engine)
    settings = SimpleNamespace()
    assert not was_contacted_within_days(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="global-candidate",
        days=7,
    )

    with engine.begin() as connection:
        connection.execute(
            insert(resume_search_hits).values(
                id=2,
                candidate_fingerprint="global-candidate",
                candidate_name="脱敏候选人",
                search_keyword="财务共享与管理经理",
                hit_status="message_sent",
            )
        )

    assert was_contacted_within_days(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="global-candidate",
        days=7,
    )


def test_recent_analysis_cooldown_only_uses_valid_results_from_prior_batches(
    monkeypatch,
) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    with engine.begin() as connection:
        connection.execute(
            insert(resume_search_hits),
            [
                {
                    "id": 1,
                    "candidate_fingerprint": "prior-candidate",
                    "search_keyword": "达人营销负责人",
                    "search_batch_id": "batch-old",
                    "hit_status": "reviewed",
                },
                {
                    "id": 2,
                    "candidate_fingerprint": "same-batch-candidate",
                    "search_keyword": "达人营销负责人",
                    "search_batch_id": "batch-new",
                    "hit_status": "reviewed",
                },
                {
                    "id": 3,
                    "candidate_fingerprint": "transient-candidate",
                    "search_keyword": "达人营销负责人",
                    "search_batch_id": "batch-old",
                    "hit_status": "reviewed",
                },
            ],
        )
        connection.execute(
            insert(resume_analysis_cache),
            [
                {
                    "id": 1,
                    "candidate_fingerprint": "prior-candidate",
                    "experience_hash": "hash-prior",
                    "analysis_version": "v1",
                    "analysis_status": "匹配",
                    "match_result": "relevant",
                    "match_score": 95,
                },
                {
                    "id": 2,
                    "candidate_fingerprint": "same-batch-candidate",
                    "experience_hash": "hash-same",
                    "analysis_version": "v1",
                    "analysis_status": "匹配",
                    "match_result": "relevant",
                    "match_score": 95,
                },
                {
                    "id": 3,
                    "candidate_fingerprint": "transient-candidate",
                    "experience_hash": "hash-transient",
                    "analysis_version": "v1",
                    "analysis_status": "待复核",
                    "match_result": "needs_review",
                    "match_score": None,
                    "analysis_reason": "Coze API 临时异常",
                },
            ],
        )
    monkeypatch.setattr(db_module, "get_engine", lambda _settings: engine)
    settings = SimpleNamespace()

    assert was_analyzed_in_prior_batch_within_days(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="prior-candidate",
        days=7,
        current_search_batch_id="batch-new",
    )
    assert not was_analyzed_in_prior_batch_within_days(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="same-batch-candidate",
        days=7,
        current_search_batch_id="batch-new",
    )
    assert not was_analyzed_in_prior_batch_within_days(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="transient-candidate",
        days=7,
        current_search_batch_id="batch-new",
    )


def test_recent_analysis_cooldown_resolves_historical_fingerprint_by_resume_hash(
    monkeypatch,
) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    with engine.begin() as connection:
        connection.execute(
            insert(resume_experience_records),
            [
                {
                    "id": 1,
                    "candidate_fingerprint": "masked-name-fingerprint",
                    "candidate_name": "李**",
                    "experience_hash": "newer-resume-hash",
                    "experience": "newer resume snapshot",
                },
                {
                    "id": 2,
                    "candidate_fingerprint": "full-name-fingerprint",
                    "candidate_name": "李昌浩",
                    "experience_hash": "shared-historical-hash",
                    "experience": "historical resume snapshot",
                },
            ],
        )
        connection.execute(
            insert(resume_analysis_cache),
            [
                {
                    "id": 1,
                    "candidate_fingerprint": "masked-name-fingerprint",
                    "experience_hash": "shared-historical-hash",
                    "analysis_version": "v1",
                    "analysis_status": "不匹配",
                    "match_result": "not_relevant",
                    "match_score": 0,
                },
                {
                    "id": 2,
                    "candidate_fingerprint": "full-name-fingerprint",
                    "experience_hash": "shared-historical-hash",
                    "analysis_version": "v2",
                    "analysis_status": "匹配",
                    "match_result": "relevant",
                    "match_score": 89,
                },
            ],
        )
        connection.execute(
            insert(resume_search_hits),
            [
                {
                    "id": 1,
                    "candidate_fingerprint": "masked-name-fingerprint",
                    "search_keyword": "财务共享中心负责人",
                    "search_batch_id": "batch-old",
                    "hit_status": "not_relevant",
                },
                {
                    "id": 2,
                    "candidate_fingerprint": "full-name-fingerprint",
                    "search_keyword": "财务共享中心总监",
                    "search_batch_id": "batch-new",
                    "hit_status": "sent",
                },
            ],
        )
    monkeypatch.setattr(db_module, "get_engine", lambda _settings: engine)
    settings = SimpleNamespace()

    assert was_analyzed_in_prior_batch_within_days(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="full-name-fingerprint",
        days=7,
        current_search_batch_id="batch-new",
    )
    canonical = get_candidate_record_by_experience_hash(
        settings,  # type: ignore[arg-type]
        "shared-historical-hash",
    )
    assert canonical is not None
    assert canonical["candidate_fingerprint"] in {
        "masked-name-fingerprint",
        "full-name-fingerprint",
    }


def test_recent_analysis_cooldown_matches_identity_alias_across_jobs(monkeypatch) -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    metadata.create_all(engine)
    prior_experience = (
        "候选人信息：年龄：35岁；李先生；财务共享、总账核算、资金管理与团队建设。"
        * 20
    )
    current_experience = prior_experience.replace("李先生", "李**")
    with engine.begin() as connection:
        connection.execute(
            insert(resume_analysis_cache).values(
                id=11,
                candidate_fingerprint="historical-alias",
                experience_hash="historical-hash",
                experience=prior_experience,
                analysis_version="v1",
                analysis_status="匹配",
                match_result="relevant",
                match_score=88,
            )
        )
        connection.execute(
            insert(resume_source_locators).values(
                id=11,
                candidate_fingerprint="historical-alias",
                candidate_name="李先生",
                job_profile_id="finance_shared_management_v1",
                search_keyword="财务共享经理",
                candidate_card_text="李先生 35岁 本科 40-45K 集团财务经理",
                locator_quality="snapshot",
            )
        )
        connection.execute(
            insert(resume_search_hits).values(
                id=11,
                candidate_fingerprint="historical-alias",
                search_keyword="财务共享经理",
                search_batch_id="batch-old",
                hit_status="not_relevant",
            )
        )
    monkeypatch.setattr(db_module, "get_engine", lambda _settings: engine)

    assert was_analyzed_in_prior_batch_within_days(
        SimpleNamespace(),  # type: ignore[arg-type]
        candidate_fingerprint="new-daren-fingerprint",
        days=7,
        current_search_batch_id="batch-new",
        candidate_name="李**",
        candidate_age=35,
        candidate_card_text="李** 35岁 本科 40-45K 集团财务经理",
        experience=current_experience,
    )
