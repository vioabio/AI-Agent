from types import SimpleNamespace
from xml.etree import ElementTree

from sqlalchemy import create_engine, event
from sqlalchemy.pool import StaticPool

import liepin_rpa.db as db_module
from liepin_rpa.candidate_locator import (
    build_cross_account_lookup_strategy,
    extract_platform_locator_evidence,
    is_official_resume_share_link,
)
from liepin_rpa.db import (
    list_candidate_source_locators,
    metadata,
    record_candidate_source_locator,
    update_candidate_source_locator_identity,
    update_candidate_source_locator_share_link,
)


def test_extracts_explicit_platform_ids_and_deep_link() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu"
                candidate_id="talent_A12345"
                content-desc="liepin://candidate/detail?resumeId=res_B67890&amp;candidateId=talent_A12345" />
        </hierarchy>
        """
    )

    evidence = extract_platform_locator_evidence(
        root,
        current_app={"activity": ".candidate.ResumeDetailActivity"},
    )

    assert evidence.platform_candidate_id == "talent_A12345"
    assert evidence.platform_resume_id == "res_B67890"
    assert evidence.detail_deep_link is not None
    assert evidence.detail_activity == ".candidate.ResumeDetailActivity"
    assert evidence.locator_quality == "platform_id"
    assert len(evidence.detail_hierarchy_hash) == 32


def test_does_not_treat_resume_numbers_as_platform_ids() -> None:
    root = ElementTree.fromstring(
        '<hierarchy><node text="2024年GMV 30000000，候选人35岁" /></hierarchy>'
    )

    evidence = extract_platform_locator_evidence(root)

    assert evidence.platform_candidate_id is None
    assert evidence.platform_resume_id is None
    assert evidence.detail_deep_link is None
    assert evidence.locator_quality == "snapshot"


def test_lookup_strategy_falls_back_to_search_hints() -> None:
    strategy = build_cross_account_lookup_strategy(
        {
            "source_account_alias": "recruiter_a",
            "search_keyword": "财务共享经理",
            "candidate_card_text": "刘女士 35岁 财务共享中心经理",
            "experience_hash": "resume-hash",
        }
    )

    assert strategy["method"] == "search_hints_then_verify"
    assert strategy["experience_hash"] == "resume-hash"


def test_official_share_link_is_the_primary_cross_account_strategy() -> None:
    share_link = (
        "https://mr.liepin.com/resume/view-share-res?"
        "bsSource=res_forward&acKey=sample-key&sourceRole=b"
    )

    strategy = build_cross_account_lookup_strategy(
        {"detail_deep_link": share_link, "experience_hash": "resume-hash"}
    )

    assert is_official_resume_share_link(share_link)
    assert strategy["method"] == "share_link_then_verify"
    assert strategy["detail_deep_link"] == share_link


def test_source_locator_round_trip_on_sqlite(monkeypatch) -> None:
    engine = create_engine(
        "sqlite://",
        future=True,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    metadata.create_all(engine)
    # SQLAlchemy BigInteger autoincrements on MySQL; provide a deterministic id
    # in the SQLite-only round-trip test without changing the production schema.
    @event.listens_for(engine, "before_cursor_execute", retval=True)
    def add_sqlite_locator_id(_conn, _cursor, statement, parameters, _context, _executemany):
        if statement.startswith("INSERT INTO candidate_resume_source_locators ("):
            statement = statement.replace(
                "INSERT INTO candidate_resume_source_locators (",
                "INSERT INTO candidate_resume_source_locators (id, ",
                1,
            ).replace("VALUES (", "VALUES (?, ", 1)
            parameters = (1, *parameters)
        return statement, parameters
    monkeypatch.setattr(db_module, "get_engine", lambda _settings: engine)
    monkeypatch.setattr(db_module, "ensure_resume_experience_schema", lambda _settings: None)
    settings = SimpleNamespace()

    locator_id = record_candidate_source_locator(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="candidate-fp",
        experience_hash="experience-hash",
        candidate_name="刘女士",
        search_keyword="财务共享经理",
        source_account_alias="recruiter_a",
        source_device_id="device-1",
        job_profile_id="finance_shared_management_v1",
        search_pack_id="finance_shared",
        result_iteration=2,
        result_rank=1,
        candidate_card_fingerprint="card-fp",
        candidate_card_text="刘女士 35岁 财务共享中心经理",
        platform_candidate_id="talent-1234",
        platform_resume_id="resume-5678",
        detail_deep_link="liepin://candidate/detail?candidateId=talent-1234",
        detail_activity=".ResumeDetailActivity",
        detail_hierarchy_hash="hierarchy-hash",
        locator_quality="platform_id",
        detail_screenshot_path="artifacts/candidate_locators/example.png",
        platform_interaction_note="你们之前HR找过我",
    )
    rows = list_candidate_source_locators(
        settings,  # type: ignore[arg-type]
        experience_hash="experience-hash",
    )

    assert locator_id > 0
    assert len(rows) == 1
    assert rows[0]["source_account_alias"] == "recruiter_a"
    assert rows[0]["platform_resume_id"] == "resume-5678"
    assert rows[0]["result_iteration"] == 2
    assert rows[0]["platform_interaction_note"] == "你们之前HR找过我"

    share_link = (
        "https://mr.liepin.com/resume/view-share-res?"
        "bsSource=res_forward&acKey=sample-key&sourceRole=b"
    )
    update_candidate_source_locator_share_link(
        settings,  # type: ignore[arg-type]
        locator_id=locator_id,
        share_link=share_link,
    )
    updated_rows = list_candidate_source_locators(
        settings,  # type: ignore[arg-type]
        experience_hash="experience-hash",
    )
    assert updated_rows[0]["detail_deep_link"] == share_link
    assert updated_rows[0]["locator_quality"] == "share_link"

    update_candidate_source_locator_identity(
        settings,  # type: ignore[arg-type]
        locator_id=locator_id,
        candidate_fingerprint="canonical-candidate-fp",
        experience_hash="canonical-experience-hash",
        candidate_name="刘女士",
    )
    canonical_rows = list_candidate_source_locators(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="canonical-candidate-fp",
    )
    assert len(canonical_rows) == 1
    assert canonical_rows[0]["experience_hash"] == "canonical-experience-hash"
    assert canonical_rows[0]["detail_deep_link"] == share_link

    db_module.update_candidate_source_locator_favorite(
        settings,  # type: ignore[arg-type]
        locator_id=locator_id,
    )
    favorite_rows = list_candidate_source_locators(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="canonical-candidate-fp",
    )
    assert favorite_rows[0]["locator_quality"] == "favorite"
