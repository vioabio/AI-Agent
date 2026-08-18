from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest
from cozepy.exception import CozeAPIError
from sqlalchemy import create_engine, insert, select

import liepin_rpa.coze_workflow as coze_workflow
import liepin_rpa.search_execution.workflow as workflow_module
from liepin_rpa.coze_workflow import (
    CozeMatchResult,
    _build_coze_workflow_parameters,
    _validate_coze_payload,
    analyze_resume_record_with_coze,
    is_transient_coze_api_review,
    transient_coze_cache_retry_due,
)
from liepin_rpa.db import (
    _backfill_resume_experience_hashes,
    build_experience_hash,
    metadata,
    resume_analysis_cache,
    resume_experience_records,
    resume_search_hits,
)
from liepin_rpa.runtime_control import ProcessLease, ProcessLeaseError
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.models import DetectionResult
from liepin_rpa.search_execution.result_filter import FilterRunReport
from liepin_rpa.search_execution.workflow import SearchWorkflowRunner, WorkflowCheckpointStore
from liepin_rpa.task_generation.models import SearchTaskSeed


def test_experience_hash_uses_content_beyond_legacy_prefix() -> None:
    prefix = "a" * 4000
    assert build_experience_hash(prefix + "first") != build_experience_hash(prefix + "second")


def test_checkpoint_only_completes_successful_keyword(tmp_path: Path) -> None:
    store = WorkflowCheckpointStore(tmp_path / "checkpoint.json")
    seed = SearchTaskSeed(brand_keyword="brand-a", role_keyword="role-a")
    catalog_key = "catalog-v1"

    store.mark_failed(
        seed=seed,
        index=1,
        total_keywords=1,
        error_message="temporary page error",
        catalog_key=catalog_key,
    )
    assert not store.load_completed_keys(catalog_key=catalog_key)

    store.mark_completed(
        seed=seed,
        index=1,
        total_keywords=1,
        end_reason="result_list_exhausted",
        catalog_key=catalog_key,
    )
    assert len(store.load_completed_keys(catalog_key=catalog_key)) == 1
    assert not store.load_completed_keys(catalog_key="catalog-v2")


def test_jd_workflow_continues_after_collecting_more_than_fifty_profiles(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seeds = [
        SearchTaskSeed(brand_keyword=f"brand-{index}", role_keyword="role")
        for index in range(1, 12)
    ]

    class FakeResultFilter:
        selection_mode = "jd_smart"

        def __init__(self) -> None:
            self.queries: list[str] = []

        def search_and_filter_seed(self, *, seed: SearchTaskSeed, output_dir=None) -> FilterRunReport:
            self.queries.append(seed.effective_query)
            return FilterRunReport(
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
                screened_candidates=5,
                junior_college_rejected=0,
                matched=True,
                matched_candidate="candidate",
                matched_candidates=["candidate"],
                profiles_collected=5,
                end_reason="profile_limit_reached",
                iterations=[],
                candidate_outcomes=[],
            )

    result_filter = FakeResultFilter()
    runner = SearchWorkflowRunner(result_filter)  # type: ignore[arg-type]
    monkeypatch.setattr(
        workflow_module,
        "get_settings",
        lambda: SimpleNamespace(workflow_max_consecutive_keyword_errors=3),
    )
    monkeypatch.setattr(runner, "_throttle_before_keyword", lambda **_: None)
    monkeypatch.setattr(runner, "_heartbeat_if_needed", lambda **_: None)
    monkeypatch.setattr(runner, "_ensure_target_app_foreground", lambda **_: None)

    report = runner.run(seeds)

    assert result_filter.queries == [seed.effective_query for seed in seeds]
    assert len(report.keyword_runs) == 11
    assert report.profiles_collected == 55


def test_process_lease_prevents_overlapping_runs(tmp_path: Path) -> None:
    lease_path = tmp_path / "search.lock"
    lease = ProcessLease.acquire(lease_path, stale_after_seconds=60)
    with pytest.raises(ProcessLeaseError):
        ProcessLease.acquire(lease_path, stale_after_seconds=60)
    lease.release()
    assert not lease_path.exists()


def test_coze_score_payload_requires_consistent_dimensions() -> None:
    payload = {
        "beauty_relevant": True,
        "match_result": "relevant",
        "analysis_reason": "Evidence is sufficient.",
        "evidence_summary": ["Target brand employment evidence."],
        "match_score": 80,
        "score_breakdown": {
            "beauty_continuity": 20,
            "industry_depth": 30,
            "role_evidence": 30,
        },
    }
    assert _validate_coze_payload(payload) == []
    payload["match_score"] = 79
    assert _validate_coze_payload(payload)


@pytest.mark.parametrize(
    ("beauty_relevant", "match_result"),
    [
        (True, "needs_review"),
        (True, "not_relevant"),
        (False, "relevant"),
    ],
)
def test_coze_payload_rejects_conflicting_match_fields(
    beauty_relevant: bool,
    match_result: str,
) -> None:
    payload = {
        "beauty_relevant": beauty_relevant,
        "match_result": match_result,
        "analysis_reason": "Conflicting model output.",
        "evidence_summary": ["Evidence is present."],
        "match_score": 80,
        "score_breakdown": {
            "beauty_continuity": 20,
            "industry_depth": 30,
            "role_evidence": 30,
        },
    }

    assert any(
        "beauty_relevant must be true exactly" in error
        for error in _validate_coze_payload(payload)
    )


def test_coze_workflow_parameters_match_published_object_schema() -> None:
    parameters = _build_coze_workflow_parameters(
        {
            "id": 0,
            "candidate_name": "Alice",
            "source_platform": "liepin",
            "search_keyword": "brand, role",
            "experience": "resume text",
            "analysis_status": "pending",
        }
    )

    resume_record = parameters["resume_record"]
    assert isinstance(resume_record, dict)
    assert resume_record == {
        "id": 0,
        "candidate_name": "Alice",
        "source_platform": "liepin",
        "search_keyword": "brand, role",
        "experience": "resume text",
        "analysis_status": "pending",
    }


def test_coze_workflow_parameters_convert_numeric_string_id() -> None:
    parameters = _build_coze_workflow_parameters(
        {
            "id": "123",
            "candidate_name": "Alice",
            "source_platform": "liepin",
            "search_keyword": "brand, role",
            "experience": "resume text",
            "analysis_status": "pending",
        }
    )

    assert parameters["resume_record"]["id"] == 123  # type: ignore[index]


def test_coze_workflow_parameters_cap_long_experience() -> None:
    parameters = _build_coze_workflow_parameters(
        {
            "candidate_name": "Alice",
            "source_platform": "liepin",
            "search_keyword": "brand, role",
            "experience": ("resume \n\t" + "x" * 5000),
            "analysis_status": "pending",
        }
    )

    experience = parameters["resume_record"]["experience"]  # type: ignore[index]
    assert isinstance(experience, str)
    assert len(experience) == 4000
    assert "\n" not in experience
    assert "\t" not in experience


def test_coze_invalid_model_input_becomes_needs_review(monkeypatch: pytest.MonkeyPatch) -> None:
    class FakeRuns:
        def __init__(self) -> None:
            self.calls = 0

        def create(self, **_: object) -> object:
            self.calls += 1
            raise CozeAPIError(code=4000, msg="The input parameters provided to the model are invalid.")

    fake_runs = FakeRuns()

    class FakeCoze:
        def __init__(self, **_: object) -> None:
            self.workflows = SimpleNamespace(runs=fake_runs)

    monkeypatch.setattr(coze_workflow, "Coze", FakeCoze)
    monkeypatch.setattr(coze_workflow.time, "sleep", lambda _: None)
    settings = SimpleNamespace(
        coze_api_token="token",
        coze_workflow_id="workflow-id",
        coze_api_base_url="https://api.coze.cn",
    )

    result = analyze_resume_record_with_coze(
        settings,  # type: ignore[arg-type]
        resume_record={
            "candidate_name": "Alice",
            "source_platform": "liepin",
            "search_keyword": "brand, role",
            "experience": "resume text",
            "analysis_status": "pending",
        },
    )

    assert result.matched is False
    assert result.match_result == "needs_review"
    assert "code=4000" in (result.analysis_reason or "")
    assert fake_runs.calls == coze_workflow.COZE_MAX_ATTEMPTS


def test_coze_primary_api_failure_falls_back_to_backup_workflow(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, object]] = []

    class FakeRuns:
        def create(self, **kwargs: object) -> object:
            calls.append(kwargs)
            if kwargs["workflow_id"] == "primary-workflow":
                raise CozeAPIError(code=4000, msg="temporary primary failure")
            return SimpleNamespace(
                data=(
                    '{"beauty_relevant":true,"match_result":"relevant",'
                    '"match_score":90,"score_breakdown":{"industry_depth":30,'
                    '"beauty_continuity":30,"role_evidence":30},'
                    '"analysis_reason":"strong evidence",'
                    '"evidence_summary":["target-role evidence"]}'
                )
            )

    class FakeCoze:
        def __init__(self, **_: object) -> None:
            self.workflows = SimpleNamespace(runs=FakeRuns())

    monkeypatch.setattr(coze_workflow, "Coze", FakeCoze)
    monkeypatch.setattr(coze_workflow.time, "sleep", lambda _: None)
    monkeypatch.setattr(coze_workflow.random, "uniform", lambda *_: 0.0)
    settings = SimpleNamespace(
        coze_api_token="token",
        coze_workflow_id="primary-workflow",
        coze_backup_workflow_id="backup-workflow",
        coze_api_base_url="https://api.coze.cn",
    )

    result = analyze_resume_record_with_coze(
        settings,  # type: ignore[arg-type]
        resume_record={"candidate_name": "Alice", "experience": "resume text"},
    )

    assert result.match_result == "relevant"
    assert result.matched is True
    assert [call["workflow_id"] for call in calls] == [
        "primary-workflow",
        "primary-workflow",
        "primary-workflow",
        "primary-workflow",
        "backup-workflow",
    ]
    assert all(call["parameters"] == calls[0]["parameters"] for call in calls)


def test_coze_primary_invalid_payload_falls_back_to_backup_workflow(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []

    class FakeRuns:
        def create(self, **kwargs: object) -> object:
            workflow_id = str(kwargs["workflow_id"])
            calls.append(workflow_id)
            if workflow_id == "primary-workflow":
                return SimpleNamespace(data="")
            return SimpleNamespace(
                data=(
                    '{"beauty_relevant":false,"match_result":"not_relevant",'
                    '"match_score":40,"score_breakdown":{"industry_depth":10,'
                    '"beauty_continuity":10,"role_evidence":20},'
                    '"analysis_reason":"insufficient evidence",'
                    '"evidence_summary":["no target-role evidence"]}'
                )
            )

    class FakeCoze:
        def __init__(self, **_: object) -> None:
            self.workflows = SimpleNamespace(runs=FakeRuns())

    monkeypatch.setattr(coze_workflow, "Coze", FakeCoze)
    monkeypatch.setattr(coze_workflow.time, "sleep", lambda _: None)
    settings = SimpleNamespace(
        coze_api_token="token",
        coze_workflow_id="primary-workflow",
        coze_backup_workflow_id="backup-workflow",
        coze_api_base_url="https://api.coze.cn",
    )

    result = analyze_resume_record_with_coze(
        settings,  # type: ignore[arg-type]
        resume_record={"candidate_name": "Alice", "experience": "resume text"},
    )

    assert result.match_result == "not_relevant"
    assert calls == [
        "primary-workflow",
        "primary-workflow",
        "primary-workflow",
        "primary-workflow",
        "backup-workflow",
    ]


def test_coze_valid_needs_review_does_not_use_backup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, object]] = []

    class FakeRuns:
        def create(self, **kwargs: object) -> object:
            calls.append(kwargs)
            return SimpleNamespace(
                data=(
                    '{"beauty_relevant":false,"match_result":"needs_review",'
                    '"match_score":90,"score_breakdown":{"industry_depth":30,'
                    '"beauty_continuity":30,"role_evidence":30},'
                    '"analysis_reason":"manual evidence check required",'
                    '"evidence_summary":["ambiguous evidence"]}'
                )
            )

    class FakeCoze:
        def __init__(self, **_: object) -> None:
            self.workflows = SimpleNamespace(runs=FakeRuns())

    monkeypatch.setattr(coze_workflow, "Coze", FakeCoze)
    settings = SimpleNamespace(
        coze_api_token="token",
        coze_workflow_id="primary-workflow",
        coze_backup_workflow_id="backup-workflow",
        coze_api_base_url="https://api.coze.cn",
    )

    result = analyze_resume_record_with_coze(
        settings,  # type: ignore[arg-type]
        resume_record={"candidate_name": "Alice", "experience": "resume text"},
    )

    assert result.match_result == "needs_review"
    assert result.matched is False
    assert [call["workflow_id"] for call in calls] == ["primary-workflow"]


def test_coze_all_configured_workflows_fail_closed_to_transient_review(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, object]] = []

    class FakeRuns:
        def create(self, **kwargs: object) -> object:
            calls.append(kwargs)
            raise CozeAPIError(code=4000, msg="temporary workflow failure")

    class FakeCoze:
        def __init__(self, **_: object) -> None:
            self.workflows = SimpleNamespace(runs=FakeRuns())

    monkeypatch.setattr(coze_workflow, "Coze", FakeCoze)
    monkeypatch.setattr(coze_workflow.time, "sleep", lambda _: None)
    monkeypatch.setattr(coze_workflow.random, "uniform", lambda *_: 0.0)
    settings = SimpleNamespace(
        coze_api_token="token",
        coze_workflow_id="primary-workflow",
        coze_backup_workflow_id="backup-workflow",
        coze_api_base_url="https://api.coze.cn",
    )

    result = analyze_resume_record_with_coze(
        settings,  # type: ignore[arg-type]
        resume_record={"candidate_name": "Alice", "experience": "resume text"},
    )

    assert result.match_result == "needs_review"
    assert result.matched is False
    assert is_transient_coze_api_review(result)
    assert [call["workflow_id"] for call in calls] == [
        "primary-workflow",
        "primary-workflow",
        "primary-workflow",
        "primary-workflow",
        "backup-workflow",
        "backup-workflow",
        "backup-workflow",
        "backup-workflow",
    ]


def test_transient_coze_cache_retries_only_after_ttl() -> None:
    result = CozeMatchResult(
        matched=False,
        match_result="needs_review",
        match_score=None,
        score_breakdown={},
        analysis_reason="需要人工复核：Coze API 临时异常，系统未进行自动匹配判断。",
        evidence_summary=[],
        raw_data="",
        parsed_payload={},
    )
    now = datetime(2026, 8, 14, 12, 0, 0)

    assert not transient_coze_cache_retry_due(
        result,
        {"updated_at": now - timedelta(minutes=9, seconds=59)},
        now=now,
    )
    assert transient_coze_cache_retry_due(
        result,
        {"updated_at": now - timedelta(minutes=10)},
        now=now,
    )
    assert transient_coze_cache_retry_due(result, {}, now=now)


def test_coze_retries_identical_parameters_after_transient_4000(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, object]] = []

    class FakeRuns:
        def create(self, **kwargs: object) -> object:
            calls.append(kwargs)
            if len(calls) == 1:
                raise CozeAPIError(code=4000, msg="temporary invalid model parameters")
            return SimpleNamespace(
                data=(
                    '{"beauty_relevant":false,"match_result":"not_relevant",'
                    '"match_score":40,"score_breakdown":{"industry_depth":10,'
                    '"beauty_continuity":5,"role_evidence":25},'
                    '"analysis_reason":"evidence is insufficient",'
                    '"evidence_summary":["no target-role evidence"]}'
                )
            )

    class FakeCoze:
        def __init__(self, **_: object) -> None:
            self.workflows = SimpleNamespace(runs=FakeRuns())

    monkeypatch.setattr(coze_workflow, "Coze", FakeCoze)
    monkeypatch.setattr(coze_workflow.time, "sleep", lambda _: None)
    monkeypatch.setattr(coze_workflow.random, "uniform", lambda *_: 0.0)
    settings = SimpleNamespace(
        coze_api_token="token",
        coze_workflow_id="workflow-id",
        coze_api_base_url="https://api.coze.cn",
    )

    result = analyze_resume_record_with_coze(
        settings,  # type: ignore[arg-type]
        resume_record={"candidate_name": "Alice", "experience": "resume text"},
    )

    assert result.match_result == "not_relevant"
    assert len(calls) == 2
    assert calls[0]["parameters"] == calls[1]["parameters"]


def test_conflicting_coze_match_fields_fail_closed_to_needs_review(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = 0

    class FakeRuns:
        def create(self, **_: object) -> object:
            nonlocal calls
            calls += 1
            return SimpleNamespace(
                data=(
                    '{"beauty_relevant":true,"match_result":"not_relevant",'
                    '"match_score":80,"score_breakdown":{"industry_depth":30,'
                    '"beauty_continuity":20,"role_evidence":30},'
                    '"analysis_reason":"conflicting output",'
                    '"evidence_summary":["evidence"]}'
                )
            )

    class FakeCoze:
        def __init__(self, **_: object) -> None:
            self.workflows = SimpleNamespace(runs=FakeRuns())

    monkeypatch.setattr(coze_workflow, "Coze", FakeCoze)
    monkeypatch.setattr(coze_workflow.time, "sleep", lambda _: None)
    settings = SimpleNamespace(
        coze_api_token="token",
        coze_workflow_id="workflow-id",
        coze_api_base_url="https://api.coze.cn",
    )

    result = analyze_resume_record_with_coze(
        settings,  # type: ignore[arg-type]
        resume_record={"candidate_name": "Alice", "experience": "resume text"},
    )

    assert calls == coze_workflow.COZE_MAX_ATTEMPTS
    assert result.matched is False
    assert result.match_result == "needs_review"
    assert result.match_score is None


def test_resume_hash_backfill_merges_duplicate_legacy_rows() -> None:
    engine = create_engine("sqlite:///:memory:", future=True)
    metadata.create_all(engine)
    duplicate_experience = "工作内容：同一份历史简历"
    with engine.begin() as connection:
        connection.execute(
            insert(resume_experience_records),
            [
                {
                    "id": 1,
                    "candidate_fingerprint": "candidate-old",
                    "candidate_name": "唐**",
                    "source_platform": "liepin",
                    "search_keyword": "卡姿兰，达播负责人",
                    "experience": duplicate_experience,
                    "analysis_status": "匹配",
                    "experience_hash": None,
                    "experience_hash_version": 1,
                },
                {
                    "id": 2,
                    "candidate_fingerprint": "candidate-new",
                    "candidate_name": "唐先生",
                    "source_platform": "liepin",
                    "search_keyword": "完美日记，达播负责人",
                    "experience": duplicate_experience,
                    "analysis_status": "匹配",
                    "experience_hash": None,
                    "experience_hash_version": 1,
                },
            ],
        )
        connection.execute(
            insert(resume_search_hits),
            {
                "id": 1,
                "candidate_fingerprint": "candidate-old",
                "candidate_name": "唐**",
                "search_keyword": "卡姿兰，达播负责人",
                "hit_status": "sent",
            },
        )
        connection.execute(
            insert(resume_analysis_cache),
            {
                "id": 1,
                "candidate_fingerprint": "candidate-old",
                "experience_hash": build_experience_hash(duplicate_experience),
                "analysis_version": "legacy",
                "analysis_status": "匹配",
                "match_result": "relevant",
            },
        )

    updated_count, merged_count = _backfill_resume_experience_hashes(engine)

    with engine.begin() as connection:
        records = connection.execute(select(resume_experience_records)).mappings().all()
        hits = connection.execute(select(resume_search_hits)).mappings().all()
        cache = connection.execute(select(resume_analysis_cache)).mappings().all()

    assert updated_count == 1
    assert merged_count == 1
    assert len(records) == 1
    assert records[0]["experience_hash"] == build_experience_hash(duplicate_experience)
    assert hits[0]["candidate_fingerprint"] == records[0]["candidate_fingerprint"]
    assert cache[0]["candidate_fingerprint"] == records[0]["candidate_fingerprint"]


def test_keyword_retry_recovery_restarts_when_foreground_app_drifted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seed = SearchTaskSeed(brand_keyword="华熙生物", role_keyword="达播负责人")
    device = _FakeRecoveryDevice(current_package="com.other.app")
    ensure_calls: list[bool] = []

    class FakeDetector:
        def detect_from_xml_text(self, _: str) -> DetectionResult:
            return DetectionResult(PageState.SEARCH_ENTRY, None)

    def fake_ensure_liepin_started(
        device_arg: _FakeRecoveryDevice,
        package: str,
        *_: object,
        force_restart: bool = False,
        **__: object,
    ) -> None:
        ensure_calls.append(force_restart)
        device_arg.current_package = package

    monkeypatch.setattr(workflow_module, "build_page_detector", lambda: FakeDetector())
    monkeypatch.setattr(workflow_module, "ensure_liepin_started", fake_ensure_liepin_started)
    monkeypatch.setattr(
        workflow_module,
        "get_settings",
        lambda: SimpleNamespace(liepin_package="com.lietou.mishu", liepin_main_activity=".HomeActivity"),
    )
    monkeypatch.setattr(workflow_module.time, "sleep", lambda _: None)

    runner = SearchWorkflowRunner(_FakeRecoveryResultFilter(device))  # type: ignore[arg-type]

    assert runner._recover_to_search_surface_for_retry(seed=seed) is True
    assert ensure_calls[0] is True
    assert device.current_package == "com.lietou.mishu"


def test_keyword_retry_recovery_restarts_after_risk_control(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seed = SearchTaskSeed(brand_keyword="贝泰妮", role_keyword="直播电商负责人")
    device = _FakeRecoveryDevice(current_package="com.lietou.mishu")
    ensure_calls: list[bool] = []
    page_states = [PageState.RISK_CONTROL, PageState.SEARCH_CONDITION]

    class FakeDetector:
        def detect_from_xml_text(self, _: str) -> DetectionResult:
            return DetectionResult(page_states.pop(0), None)

    def fake_ensure_liepin_started(
        device_arg: _FakeRecoveryDevice,
        package: str,
        *_: object,
        force_restart: bool = False,
        **__: object,
    ) -> None:
        ensure_calls.append(force_restart)
        device_arg.current_package = package

    monkeypatch.setattr(workflow_module, "build_page_detector", lambda: FakeDetector())
    monkeypatch.setattr(workflow_module, "ensure_liepin_started", fake_ensure_liepin_started)
    monkeypatch.setattr(
        workflow_module,
        "get_settings",
        lambda: SimpleNamespace(liepin_package="com.lietou.mishu", liepin_main_activity=".HomeActivity"),
    )
    monkeypatch.setattr(workflow_module.time, "sleep", lambda _: None)

    runner = SearchWorkflowRunner(_FakeRecoveryResultFilter(device))  # type: ignore[arg-type]

    assert runner._recover_to_search_surface_for_retry(seed=seed) is True
    assert ensure_calls == [True, True]


class _FakeRecoveryDevice:
    def __init__(self, *, current_package: str) -> None:
        self.current_package = current_package

    def current_app(self) -> dict[str, str]:
        return {"package": self.current_package}

    def dump_hierarchy_text(self) -> str:
        return "<hierarchy />"

    def press_back(self) -> None:
        pass


class _FakeRecoveryNavigator:
    @staticmethod
    def dismiss_interruption_if_present(_: object) -> bool:
        return False

    @staticmethod
    def _reset_to_search_surface() -> None:
        return None


class _FakeRecoveryResultFilter:
    def __init__(self, device: _FakeRecoveryDevice) -> None:
        self.device = device
        self.navigator = _FakeRecoveryNavigator()
