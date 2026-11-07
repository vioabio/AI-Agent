from types import SimpleNamespace

from sqlalchemy import create_engine, insert
from sqlalchemy.pool import StaticPool

import liepin_rpa.db as db_module
from liepin_rpa.db import (
    get_candidate_record_by_fingerprint,
    metadata,
    resume_experience_records,
    update_candidate_manual_review,
)


def test_manual_review_is_stored_without_overwriting_ai_status(monkeypatch) -> None:
    engine = create_engine(
        "sqlite://",
        future=True,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    metadata.create_all(engine)
    with engine.begin() as connection:
        connection.execute(
            insert(resume_experience_records).values(
                id=1,
                candidate_fingerprint="candidate-fp",
                candidate_name="毛先生",
                analysis_status="匹配",
                match_score=90,
            )
        )
    monkeypatch.setattr(db_module, "get_engine", lambda _settings: engine)
    monkeypatch.setattr(db_module, "ensure_resume_experience_schema", lambda _settings: None)
    settings = SimpleNamespace()

    updated = update_candidate_manual_review(
        settings,  # type: ignore[arg-type]
        candidate_fingerprint="candidate-fp",
        review_status="interview_rejected",
        feedback="已经面试，淘汰。",
    )
    record = get_candidate_record_by_fingerprint(
        settings,  # type: ignore[arg-type]
        "candidate-fp",
    )

    assert updated == 1
    assert record is not None
    assert record["analysis_status"] == "匹配"
    assert record["match_score"] == 90
    assert record["manual_review_status"] == "interview_rejected"
    assert record["manual_review_feedback"] == "已经面试，淘汰。"
    assert record["manual_suppress_communication"] == 1
    assert record["manual_suppress_report"] == 1
