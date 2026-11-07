from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json

import pytest
from pydantic import ValidationError
from sqlalchemy import create_engine, event as sql_event, func, select

from liepin_rpa.sourcing.contracts import SourceObservation, parse_observation
from liepin_rpa.sourcing.schema import sourcing_metadata, sourcing_runs, sourcing_observations, candidate_source_identities
from liepin_rpa.sourcing.store import SourceStore, SourceConflict, PlatformScope


def observation(**updates):
    return SourceObservation(**dict(dict(mode="fixture", event_id="event-1", run_id="run-1", platform="liepin",
        account_alias="primary", job_profile_id="ai_development_engineer_v1", platform_candidate_id="source-1",
        platform_resume_id="resume-1", requested_ref="preview-1", candidate_name="虚构同名候选人",
        captured_at=datetime(2026, 11, 7, tzinfo=timezone.utc), cli_version="fixture-v1", adapter_version="fixture-v1",
        content_kind="api_text", completeness="full", resume_text="【纯虚构】独立开发并上线检索问答服务。"), **updates))


@pytest.fixture
def store(tmp_path):
    engine = create_engine(f"sqlite+pysqlite:///{tmp_path / 'sources.sqlite'}")
    @sql_event.listens_for(engine, "connect")
    def foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")
    sourcing_metadata.create_all(engine)
    value = SourceStore(engine)
    value.create_run(run_id="run-1", job_profile_id="ai_development_engineer_v1",
        scopes=[PlatformScope("liepin", "primary"), PlatformScope("boss", "primary")],
        max_requests=12, max_tokens=50000, max_cost_micros=10000)
    yield value
    engine.dispose()


def count(store, table):
    with store.engine.connect() as connection:
        return connection.scalar(select(func.count()).select_from(table))


def test_exact_replay_and_conflicting_replay_preserve_first_observation(store):
    first = store.append(observation())
    assert first.state == "stored" and first.analysis_readiness == "text_ready"
    assert store.append(parse_observation(observation().model_dump_json())).state == "replayed"
    with pytest.raises(SourceConflict, match="event_payload_conflict"):
        store.append(observation(resume_text="【纯虚构】篡改原文"))
    assert count(store, sourcing_observations) == count(store, candidate_source_identities) == 1
    with store.engine.connect() as connection:
        stored = json.loads(connection.scalar(select(sourcing_observations.c.payload_json)))
    assert stored["requested_ref"] == "preview-1" and stored["platform_resume_id"] == "resume-1"
    assert stored["resume_text"] == observation().resume_text


def test_same_name_and_text_do_not_merge_platforms_or_source_ids(store):
    observations = [observation(), observation(platform="boss"), observation(event_id="event-2", platform_candidate_id="source-2")]
    receipts = [store.append(item) for item in observations]
    assert len({receipt.candidate_fingerprint for receipt in receipts}) == 3
    assert count(store, candidate_source_identities) == count(store, sourcing_observations) == 3


def test_content_version_changes_preserve_identity_and_history(store):
    first = store.append(observation())
    second = store.append(observation(event_id="event-2", platform_resume_id="resume-2", resume_text="【纯虚构】新的履历版本"))
    assert first.candidate_fingerprint == second.candidate_fingerprint
    assert count(store, candidate_source_identities) == 1 and count(store, sourcing_observations) == 2


def test_no_stable_id_is_observation_scoped_and_not_ready_for_analysis(store):
    missing = dict(platform_candidate_id=None, platform_resume_id=None)
    first = store.append(observation(**missing))
    second = store.append(observation(event_id="event-2", **missing))
    assert first.analysis_readiness == "identity_unverified"
    assert first.candidate_fingerprint != second.candidate_fingerprint


def test_parent_mapping_and_budget_are_shared_immutable_and_order_independent(store):
    params = dict(run_id="run-1", job_profile_id="ai_development_engineer_v1",
        scopes=[PlatformScope("boss", "primary"), PlatformScope("liepin", "primary")],
        max_requests=12, max_tokens=50000, max_cost_micros=10000)
    assert store.create_run(**params) == store.create_run(**params)
    with pytest.raises(SourceConflict):
        store.create_run(**{**params, "max_requests": 24})
    assert count(store, sourcing_runs) == 1
    with store.engine.connect() as connection:
        assert connection.scalar(select(sourcing_runs.c.max_requests)) == 12


@pytest.mark.parametrize("update", [dict(mode="live"), dict(account_alias="other"), dict(run_id="missing"),
                                   dict(job_profile_id="other-job")])
def test_unconfigured_or_wrongly_attributed_event_cannot_write(store, update):
    with pytest.raises(ValueError):
        store.append(observation(**update))
    assert count(store, sourcing_observations) == count(store, candidate_source_identities) == 0


@pytest.mark.parametrize("update", [dict(completeness="full", content_kind="list_summary"),
    dict(content_kind="image_only", completeness="unknown"), dict(resume_text=" "),
    dict(captured_at=datetime(2026, 11, 7)), dict(platform_candidate_id=" "), dict(cookie="not-accepted")])
def test_contract_rejects_invented_fulltext_ambiguous_time_and_credentials(update):
    with pytest.raises(ValidationError):
        observation(**update)


def test_summary_is_stored_without_promoting_it_to_full_resume(store):
    receipt = store.append(observation(content_kind="list_summary", completeness="partial"))
    assert receipt.analysis_readiness == "insufficient_content"


def test_identity_namespaces_are_separate_by_account_and_fixture_status():
    base = observation()
    assert len({base.identity_key, observation(account_alias="second").identity_key, observation(mode="live").identity_key}) == 3


def test_concurrent_replays_have_one_durable_event(store):
    with ThreadPoolExecutor(max_workers=4) as workers:
        receipts = list(workers.map(lambda _: store.append(observation()), range(8)))
    assert sum(r.state == "stored" for r in receipts) == 1
    assert count(store, sourcing_observations) == count(store, candidate_source_identities) == 1


def test_constructed_objects_cannot_bypass_storage_validation(store):
    forged = observation().model_copy(update={"content_kind": "list_summary"})
    with pytest.raises(ValidationError):
        store.append(forged)
    with pytest.raises(ValueError, match="bounded input"):
        parse_observation(b" " * 1_000_001)


def test_source_import_leaves_existing_human_review_and_global_text_hash_untouched(store):
    from sqlalchemy import insert
    from liepin_rpa.db import metadata, resume_experience_records
    metadata.create_all(store.engine)
    with store.engine.begin() as connection:
        connection.execute(insert(resume_experience_records).values(id=1, candidate_fingerprint="legacy-person",
            candidate_name="旧虚构候选人", experience_hash="legacy-hash", experience=observation().resume_text,
            manual_review_status="reviewed", manual_review_feedback="保留人工意见", manual_review_revision=7,
            manual_suppress_communication=1, manual_suppress_report=1))
        before = dict(connection.execute(select(resume_experience_records)).mappings().one())
    store.append(observation())
    store.append(observation(platform="boss"))
    with store.engine.connect() as connection:
        assert dict(connection.execute(select(resume_experience_records)).mappings().one()) == before


def test_new_run_keeps_identity_but_appends_another_observation(store):
    first = store.append(observation())
    store.create_run(run_id="run-2", job_profile_id="ai_development_engineer_v1",
        scopes=[PlatformScope("liepin", "primary")], max_requests=2, max_tokens=4000, max_cost_micros=0)
    second = store.append(observation(run_id="run-2"))
    assert first.candidate_fingerprint == second.candidate_fingerprint
    assert first.event_key != second.event_key
    assert count(store, candidate_source_identities) == 1 and count(store, sourcing_observations) == 2
