"""Replay explicitly fictional dual-platform observations into a fresh local source ledger."""
from collections import Counter
from dataclasses import asdict
import json
from pathlib import Path
from uuid import uuid4

from sqlalchemy import create_engine, event, func, select

from liepin_rpa.sourcing.contracts import parse_observation
from liepin_rpa.sourcing.schema import (
    sourcing_metadata, sourcing_runs, sourcing_platform_runs, sourcing_observations, candidate_source_identities,
)
from liepin_rpa.sourcing.store import PlatformScope, SourceStore

ROOT = Path(__file__).resolve().parents[1]


def run_fixture():
    # Never accept a live DB URL or consult Settings/.env in this fixture entrypoint.
    directory = ROOT / "artifacts/recruiting/fixture-ledger" / uuid4().hex
    directory.mkdir(parents=True)
    engine = create_engine(f"sqlite+pysqlite:///{(directory / 'sources.sqlite3').as_posix()}")
    @event.listens_for(engine, "connect")
    def enable_foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")
    try:
        sourcing_metadata.create_all(engine)
        store = SourceStore(engine, mode="fixture")
        run_id = "fictional-dual-platform-v1"
        batch_id = store.create_run(run_id=run_id, job_profile_id="ai_development_engineer_v1",
            scopes=[PlatformScope("liepin", "primary", "fictional-liepin-job"),
                    PlatformScope("boss", "primary", "fictional-boss-job")],
            max_requests=12, max_tokens=50000, max_cost_micros=10000)
        base = dict(schema_version="sourcing-observation-v1", mode="fixture", run_id=run_id,
            event_id="fictional-person-1", platform="liepin", account_alias="primary",
            job_profile_id="ai_development_engineer_v1", platform_candidate_id="fictional-platform-id-1",
            candidate_name="纯虚构同名候选人", captured_at="2026-11-07T00:00:00Z",
            cli_version="fixture-only-not-cli-output", adapter_version="fixture-source-v1",
            content_kind="api_text", completeness="full", resume_text="【纯虚构样本】开发并上线检索问答服务。")
        variants = [{}, {"platform": "boss"},
            {"event_id": "fictional-person-2", "platform_candidate_id": "fictional-platform-id-2"},
            {"platform": "boss", "event_id": "fictional-summary", "platform_candidate_id": "fictional-summary-id",
             "content_kind": "list_summary", "completeness": "partial", "resume_text": "【纯虚构列表摘要】AI 开发"},
            {"event_id": "fictional-no-id", "platform_candidate_id": None}, {}]
        receipts = [asdict(store.append(parse_observation(json.dumps({**base, **variant}, ensure_ascii=False))))
                    for variant in variants]
        with engine.connect() as connection:
            counts = {table.name: connection.scalar(select(func.count()).select_from(table))
                      for table in (sourcing_runs, sourcing_platform_runs, candidate_source_identities, sourcing_observations)}
        report = dict(mode="fixture", synthetic_data=True, schema_revision="20261107_09",
            schema_initialization="fresh_source_metadata_not_alembic", run_id=run_id,
            parent_analysis_batch_id=batch_id, queue_batch_created=False,
            counts=counts, receipt_states=dict(Counter(r["state"] for r in receipts)),
            stored_readiness=dict(Counter(r["analysis_readiness"] for r in receipts if r["state"] == "stored")),
            browser_commands=0, platform_requests=0, model_requests=0, hr_rows_written=0,
            next_step="Adapters and queue/HR import remain to be implemented")
        assert counts == {"sourcing_runs": 1, "sourcing_platform_runs": 2,
                          "candidate_source_identities": 5, "sourcing_observations": 5}
        assert report["receipt_states"] == {"stored": 5, "replayed": 1}
        (directory / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        return report, directory / "report.json"
    finally:
        engine.dispose()


def main():
    report, path = run_fixture()
    print(json.dumps({**report, "report_path": str(path)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
