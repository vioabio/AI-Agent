from dataclasses import replace
import json

import pytest
from sqlalchemy import create_engine, func, inspect, select, update

from liepin_rpa.analysis_evidence_store import (
    analysis_evidence_refs, analysis_evidence_runs, evidence_metadata, persist_analysis_evidence,
    read_bound_evidence, result_document, resume_snapshots,
)
from liepin_rpa.resume_evidence import build_exact_references, build_snapshot, resume_text_hash, valid_reference


def _result(quote="开发并上线🙂", state="succeeded"):
    return result_document(match_result="relevant", match_score=90,
                           score_breakdown={"industry_depth": 25, "beauty_continuity": 15, "role_evidence": 50},
                           analysis_reason="合成结果", evidence_summary=[quote, "此项是摘要"], execution_status=state)


@pytest.fixture
def evidence_engine():
    engine = create_engine("sqlite://")
    evidence_metadata.create_all(engine)
    yield engine
    engine.dispose()


def _persist(conn, **overrides):
    kwargs = dict(analysis_run_id="run-1", candidate_fingerprint="candidate-1",
                  job_profile_id="job-1", analysis_version="version-1", raw_text="  项目：开发并上线🙂\r\n其他经历\n",
                  result=_result())
    kwargs.update(overrides)
    kwargs.setdefault("analyzed_text_hash", resume_text_hash(kwargs["raw_text"]))
    return persist_analysis_evidence(conn, **kwargs)


def _read(conn, **overrides):
    kwargs = dict(analysis_run_id="run-1", candidate_fingerprint="candidate-1",
                  job_profile_id="job-1", analysis_version="version-1", result=_result())
    kwargs.update(overrides)
    return read_bound_evidence(conn, **kwargs)


def test_snapshot_preserves_codepoints_whitespace_crlf_and_identity():
    original = "  简历🙂\r\n开发并上线🙂\n"
    snapshot = build_snapshot("one", original)
    assert snapshot.raw_text == original
    assert snapshot == build_snapshot("one", original)
    assert snapshot.snapshot_id != build_snapshot("two", original).snapshot_id
    assert snapshot.snapshot_id != build_snapshot("one", original.strip()).snapshot_id
    ref = build_exact_references(snapshot, "run", ["开发并上线🙂"])[0]
    assert original[ref.start:ref.end] == ref.quote
    assert valid_reference(snapshot, "run", ref, [ref.quote])
    assert not valid_reference(snapshot, "run", replace(ref, start=True), [ref.quote])
    assert not valid_reference(snapshot, "other-run", ref, [ref.quote])


def test_ambiguous_overlapping_or_paraphrased_claims_are_not_guessed():
    snapshot = build_snapshot("one", "aaaa；拥有经验；拥有经验；唯一证据")
    refs = build_exact_references(snapshot, "run", ["aa", "拥有经验", "经验丰富", "唯一证据"])
    assert len(refs) == 1
    assert refs[0].summary_index == 3


def test_storage_is_idempotent_and_old_snapshots_survive_new_analyses(evidence_engine):
    with evidence_engine.begin() as conn:
        _persist(conn)
        _persist(conn)
        old = _read(conn)
        assert old.status == "verified" and len(old.references) == 1
        _persist(conn, analysis_run_id="run-2", raw_text="新的简历：开发并上线🙂；有新项目")
        assert _read(conn).snapshot == old.snapshot
        assert _read(conn, analysis_run_id="run-2").snapshot != old.snapshot
        assert conn.scalar(select(func.count()).select_from(analysis_evidence_runs)) == 2
        assert conn.scalar(select(func.count()).select_from(resume_snapshots)) == 2


def test_analysis_runs_cannot_be_rewritten(evidence_engine):
    with evidence_engine.begin() as conn:
        _persist(conn)
        with pytest.raises(ValueError, match="overwritten"):
            _persist(conn, raw_text="另一个版本：开发并上线🙂")


@pytest.mark.parametrize("scope", [
    {"candidate_fingerprint": "other-person"}, {"job_profile_id": "other-job"},
    {"analysis_version": "other-version"}, {"result": _result("另一个摘要")},
])
def test_foreign_or_changed_analysis_cannot_disclose_snapshot(evidence_engine, scope):
    with evidence_engine.begin() as conn:
        _persist(conn)
        bundle = _read(conn, **scope)
        assert bundle.status == "invalid"
        assert bundle.snapshot is None and not bundle.references


@pytest.mark.parametrize("corruption", ["text", "quote", "range", "deleted_ref", "result"])
def test_tampered_data_never_creates_clickable_citations(evidence_engine, corruption):
    with evidence_engine.begin() as conn:
        _persist(conn)
        if corruption == "text":
            conn.execute(update(resume_snapshots).values(raw_text="替换后的正文"))
        elif corruption == "result":
            conn.execute(update(analysis_evidence_runs).values(result_json=json.dumps(_result("不同摘要"))))
        elif corruption == "deleted_ref":
            conn.execute(analysis_evidence_refs.delete())
        else:
            conn.execute(update(analysis_evidence_refs).values(**({"quote": "伪造引用"} if corruption == "quote" else {"end": 99999})))
        assert _read(conn).status == "invalid"


def test_unknown_legacy_and_failed_results_do_not_get_fabricated_proofs(evidence_engine):
    with evidence_engine.begin() as conn:
        assert _read(conn, analysis_run_id=None).status == "legacy"
        failed = _result(state="transient_error")
        _persist(conn, result=failed)
        bundle = _read(conn, result=failed)
        assert bundle.status == "verified"
        assert not bundle.references


def test_mismatched_inputs_are_rejected_and_writes_share_caller_transaction(evidence_engine):
    with pytest.raises(ValueError, match="input"):
        with evidence_engine.begin() as conn:
            _persist(conn, analyzed_text_hash="wrong")
    with pytest.raises(RuntimeError):
        with evidence_engine.begin() as conn:
            _persist(conn)
            raise RuntimeError("Simulate a later cache write failure")
    with evidence_engine.connect() as conn:
        assert conn.scalar(select(func.count()).select_from(analysis_evidence_runs)) == 0
        assert conn.scalar(select(func.count()).select_from(resume_snapshots)) == 0


def test_runtime_schema_guard_does_not_silently_create_evidence_tables(monkeypatch):
    from liepin_rpa import db
    from liepin_rpa.config import Settings

    engine = create_engine("sqlite://")
    monkeypatch.setattr(db, "get_engine", lambda _settings: engine)
    with pytest.raises(RuntimeError, match="explicit Alembic migration"):
        db.ensure_resume_experience_schema(Settings(_env_file=None))
    assert inspect(engine).get_table_names() == []
    engine.dispose()


def test_partial_migration_fails_before_legacy_schema_maintenance(monkeypatch):
    from liepin_rpa import db
    from liepin_rpa.config import Settings

    engine = create_engine("sqlite://")
    evidence_metadata.create_all(engine)
    with engine.begin() as conn:
        conn.exec_driver_sql("CREATE TABLE candidate_resume_analysis_cache (id INTEGER PRIMARY KEY)")
    before = set(inspect(engine).get_table_names())
    monkeypatch.setattr(db, "get_engine", lambda _settings: engine)
    with pytest.raises(RuntimeError, match="before any runtime schema maintenance"):
        db.ensure_resume_experience_schema(Settings(_env_file=None))
    assert set(inspect(engine).get_table_names()) == before
    engine.dispose()
