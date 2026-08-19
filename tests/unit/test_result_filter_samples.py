from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from xml.etree import ElementTree

import liepin_rpa.search_execution.result_filter as result_filter_module
from liepin_rpa.config import Settings
from liepin_rpa.coze_workflow import CozeMatchResult
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.search_execution.result_filter import (
    CandidateCard,
    MessageSendResult,
    ResultPageFilter,
)
from liepin_rpa.share_quota import DailyShareQuotaStateStore
from liepin_rpa.task_generation.models import SearchTaskSeed

FIXTURES_DIR = Path(__file__).parents[1] / "fixtures"


def test_extract_visible_candidate_cards_from_saved_result_list() -> None:
    root = ElementTree.fromstring((FIXTURES_DIR / "result_list.xml").read_text(encoding="utf-8"))

    cards = ResultPageFilter.extract_visible_candidate_cards(root)
    seed = SearchTaskSeed(brand_keyword="珀莱雅", role_keyword="达播总监")

    assert len(cards) >= 3
    assert any(seed.brand_keyword in card.raw_text for card in cards)
    assert any("总监" in card.raw_text for card in cards)
    assert len({card.fingerprint for card in cards}) == len(cards)


def test_ai_profile_selection_does_not_require_exact_brand_or_role(monkeypatch) -> None:
    result_filter = ResultPageFilter(
        device=object(),  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        max_profiles_per_keyword=1,
        ai_first_selection=True,
    )
    seed = SearchTaskSeed(brand_keyword="海蓝之谜", role_keyword="达人营销总监")
    card = CandidateCard(
        fingerprint="candidate-1",
        raw_text="张女士 36岁 本科 内容电商负责人 某消费品公司",
        bounds="[0,0][100,100]",
    )

    monkeypatch.setattr(
        result_filter.navigator,
        "ensure_result_context",
        lambda _seed: PageState.RESULT_LIST,
    )
    monkeypatch.setattr(
        result_filter,
        "_capture_result_list_root",
        lambda _seed: ElementTree.Element("hierarchy"),
    )
    monkeypatch.setattr(
        result_filter,
        "extract_visible_candidate_cards",
        lambda _root: [card],
    )
    monkeypatch.setattr(
        result_filter,
        "_collect_profile_for_candidate",
        lambda **_kwargs: object(),
    )

    report = result_filter.run_current_result_page(seed)

    assert report.profiles_collected == 1
    assert report.end_reason == "profile_limit_reached"
    assert report.matched_candidates == [card.raw_text]


def test_legacy_selection_requires_exact_brand_and_role_by_default() -> None:
    seed = SearchTaskSeed(brand_keyword="海蓝之谜", role_keyword="达人营销总监")

    assert ResultPageFilter.is_exact_candidate_match(
        "海蓝之谜 达人营销总监 36岁 本科",
        seed,
    )
    assert not ResultPageFilter.is_exact_candidate_match(
        "某消费品公司 内容电商负责人 36岁 本科",
        seed,
    )


def test_both_jd_smart_job_profiles_require_explicit_shanghai_intent() -> None:
    repository_root = Path(__file__).parents[2]
    profile_paths = [
        repository_root / "config" / "job_profiles" / "daren_live_bd.yaml",
        repository_root / "config" / "job_profiles" / "finance_shared_management.yaml",
    ]

    for profile_path in profile_paths:
        result_filter = ResultPageFilter(
            device=object(),  # type: ignore[arg-type]
            detector=object(),
            settle_seconds=0,
            selection_mode="jd_smart",
            job_profile_path=profile_path,
        )

        assert result_filter._required_job_intent_city() == "上海"


def test_both_jd_smart_job_profiles_route_primary_and_backup_coze_workflows() -> None:
    repository_root = Path(__file__).parents[2]
    settings = Settings(
        _env_file=None,
        COZE_JD_WORKFLOW_ID="daren-primary",
        COZE_JD_BACKUP_WORKFLOW_ID="daren-backup",
        COZE_FINANCE_WORKFLOW_ID="finance-primary",
        COZE_FINANCE_BACKUP_WORKFLOW_ID="finance-backup",
    )
    cases = [
        ("daren_live_bd.yaml", "daren-primary", "daren-backup"),
        ("finance_shared_management.yaml", "finance-primary", "finance-backup"),
    ]

    for profile_name, expected_primary, expected_backup in cases:
        result_filter = ResultPageFilter(
            device=object(),  # type: ignore[arg-type]
            detector=object(),
            settle_seconds=0,
            selection_mode="jd_smart",
            job_profile_path=repository_root / "config" / "job_profiles" / profile_name,
        )

        analysis_settings = result_filter._analysis_settings(settings)

        assert analysis_settings.coze_workflow_id == expected_primary
        assert analysis_settings.coze_backup_workflow_id == expected_backup


def test_daren_card_screening_requires_direction_and_quality_support() -> None:
    repository_root = Path(__file__).parents[2]
    result_filter = ResultPageFilter(
        device=object(),  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        selection_mode="jd_smart",
        job_profile_path=repository_root / "config" / "job_profiles" / "daren_live_bd.yaml",
    )
    seed = SearchTaskSeed(
        brand_keyword="珀莱雅",
        role_keyword="达人营销/KOL种草",
        strategy="jd_smart",
        search_pack_id="daren_kol",
    )

    no_direction = result_filter.score_jd_candidate_card("珀莱雅 美妆 市场经理", seed)
    no_support = result_filter.score_jd_candidate_card("达人营销 KOL", seed)
    qualified = result_filter.score_jd_candidate_card("美妆 达人营销负责人 KOL", seed)

    assert not no_direction.selected
    assert "missing_required_daren_or_live_direction" in no_direction.reasons
    assert not no_support.selected
    assert "missing_required_leadership_or_target_industry" in no_support.reasons
    assert qualified.selected


def test_finance_card_screening_requires_a_management_combination() -> None:
    repository_root = Path(__file__).parents[2]
    result_filter = ResultPageFilter(
        device=object(),  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        selection_mode="jd_smart",
        job_profile_path=(
            repository_root / "config" / "job_profiles" / "finance_shared_management.yaml"
        ),
    )
    seed = SearchTaskSeed(
        brand_keyword="",
        role_keyword="财务共享与管理经理",
        query_text="财务共享中心负责人",
        strategy="jd_smart",
        search_pack_id="finance_shared",
    )

    shared_manager = result_filter.score_jd_candidate_card(
        "刘女士 34岁 本科 财务共享中心经理 共享运营",
        seed,
    )
    group_accounting_manager = result_filter.score_jd_candidate_card(
        "张先生 35岁 本科 集团核算经理 多主体 合并报表",
        seed,
    )
    transformation_manager = result_filter.score_jd_candidate_card(
        "李女士 33岁 本科 财务变革经理 业财一体化 ERP",
        seed,
    )
    broad_finance_manager = result_filter.score_jd_candidate_card(
        "王女士 34岁 本科 高级财务经理 预算 财务BP",
        seed,
    )
    systems_only = result_filter.score_jd_candidate_card(
        "赵先生 32岁 本科 ERP财务系统实施顾问",
        seed,
    )

    assert shared_manager.selected
    assert group_accounting_manager.selected
    assert transformation_manager.selected
    assert not broad_finance_manager.selected
    assert not systems_only.selected
    assert "missing_required_finance_combination" in systems_only.reasons


def test_finance_profile_age_limit_is_loaded_from_job_profile() -> None:
    repository_root = Path(__file__).parents[2]
    result_filter = ResultPageFilter(
        device=object(),  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        selection_mode="jd_smart",
        job_profile_path=(
            repository_root / "config" / "job_profiles" / "finance_shared_management.yaml"
        ),
    )

    assert result_filter._required_candidate_maximum_age() == 35


def test_jd_smart_selection_prioritizes_highest_scoring_visible_card(monkeypatch) -> None:
    repository_root = Path(__file__).parents[2]
    result_filter = ResultPageFilter(
        device=object(),  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        max_profiles_per_keyword=1,
        selection_mode="jd_smart",
        job_profile_path=repository_root / "config" / "job_profiles" / "daren_live_bd.yaml",
    )
    seed = SearchTaskSeed(
        brand_keyword="珀莱雅",
        role_keyword="达人营销/KOL种草",
        strategy="jd_smart",
        search_pack_id="daren_kol",
    )
    lower = CandidateCard("lower", "达人营销 负责人", "[0,0][100,100]")
    higher = CandidateCard(
        "higher",
        "珀莱雅 美妆 达人直播 KOL 负责人 抖音",
        "[0,100][100,200]",
    )
    collected: list[str] = []

    monkeypatch.setattr(
        result_filter.navigator,
        "ensure_result_context",
        lambda _seed: PageState.RESULT_LIST,
    )
    monkeypatch.setattr(
        result_filter,
        "_capture_result_list_root",
        lambda _seed: ElementTree.Element("hierarchy"),
    )
    monkeypatch.setattr(
        result_filter,
        "extract_visible_candidate_cards",
        lambda _root: [lower, higher],
    )
    monkeypatch.setattr(
        result_filter,
        "_collect_profile_for_candidate",
        lambda **kwargs: collected.append(kwargs["card"].fingerprint) or object(),
    )

    report = result_filter.run_current_result_page(seed)

    assert collected == ["higher"]
    assert report.matched_candidates == [higher.raw_text]


def test_non_shanghai_intent_stops_before_coze_and_communication(monkeypatch) -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.back_calls = 0

        def tap(self, *_args) -> None:
            return None

        def dump_hierarchy_text(self) -> str:
            return "<hierarchy/>"

        def current_app(self) -> dict[str, str]:
            return {}

        def press_back(self) -> None:
            self.back_calls += 1

    device = FakeDevice()
    repository_root = Path(__file__).parents[2]
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        write_to_db=False,
        selection_mode="jd_smart",
        job_profile_path=(
            repository_root / "config" / "job_profiles" / "finance_shared_management.yaml"
        ),
    )
    report = SimpleNamespace(
        sections={
            "求职意向": ["财务共享经理", "杭州"],
            "工作经历": ["某集团", "财务共享经理", "2020.01-至今"],
        },
        structured_resume={
            "basic_info": {"name": "张女士", "age": 34},
            "work_experiences": [],
            "education_experiences": [],
        },
    )

    monkeypatch.setattr(result_filter, "_wait", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(result_filter, "_is_intention_communication_page", lambda _root: False)
    monkeypatch.setattr(
        result_filter_module,
        "find_profile_communication_action",
        lambda _root: ("立即沟通", (1, 1)),
    )
    monkeypatch.setattr(
        result_filter_module,
        "CandidateProfileCollector",
        lambda device: SimpleNamespace(collect_current_profile=lambda: report),
    )

    def fail_if_coze_called(*_args, **_kwargs):
        raise AssertionError("Coze must not run for a non-Shanghai job intent")

    monkeypatch.setattr(
        result_filter_module, "analyze_resume_record_with_coze", fail_if_coze_called
    )

    outcome = result_filter._collect_profile_for_candidate(
        seed=SearchTaskSeed(
            brand_keyword="财务共享",
            role_keyword="财务共享与管理经理",
            query_text="财务共享经理",
        ),
        card=CandidateCard(
            fingerprint="card-1",
            raw_text="张女士 34岁 本科 财务共享经理 上海",
            bounds="[0,0][100,100]",
        ),
    )

    assert outcome.hit_status == "job_intent_city_rejected"
    assert outcome.analysis_status == "求职意向城市不符"
    assert outcome.message_send_success is False
    assert device.back_calls == 1


def test_finance_detail_age_over_limit_stops_before_coze_and_communication(monkeypatch) -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.back_calls = 0

        def tap(self, *_args) -> None:
            return None

        def dump_hierarchy_text(self) -> str:
            return "<hierarchy/>"

        def current_app(self) -> dict[str, str]:
            return {}

        def press_back(self) -> None:
            self.back_calls += 1

    device = FakeDevice()
    repository_root = Path(__file__).parents[2]
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        write_to_db=False,
        selection_mode="jd_smart",
        job_profile_path=(
            repository_root / "config" / "job_profiles" / "finance_shared_management.yaml"
        ),
    )
    report = SimpleNamespace(
        sections={
            "求职意向": ["财务共享中心经理", "上海"],
            "工作经历": ["某集团", "财务共享中心经理", "2018.01-至今"],
        },
        structured_resume={
            "basic_info": {"name": "王女士", "age": 36},
            "work_experiences": [],
            "education_experiences": [],
        },
    )

    monkeypatch.setattr(result_filter, "_wait", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(result_filter, "_is_intention_communication_page", lambda _root: False)
    monkeypatch.setattr(
        result_filter_module,
        "find_profile_communication_action",
        lambda _root: ("立即沟通", (1, 1)),
    )
    monkeypatch.setattr(
        result_filter_module,
        "CandidateProfileCollector",
        lambda device: SimpleNamespace(collect_current_profile=lambda: report),
    )

    def fail_if_coze_called(*_args, **_kwargs):
        raise AssertionError("Coze must not run for a finance candidate over age 35")

    monkeypatch.setattr(
        result_filter_module, "analyze_resume_record_with_coze", fail_if_coze_called
    )

    outcome = result_filter._collect_profile_for_candidate(
        seed=SearchTaskSeed(
            brand_keyword="",
            role_keyword="财务共享与管理经理",
            query_text="财务共享中心经理",
            strategy="jd_smart",
            search_pack_id="finance_shared",
        ),
        card=CandidateCard(
            fingerprint="card-age-36",
            raw_text="王女士 本科 财务共享中心经理",
            bounds="[0,0][100,100]",
        ),
    )

    assert outcome.hit_status == "candidate_age_rejected"
    assert outcome.match_result == "not_relevant"
    assert outcome.candidate_age == 36
    assert device.back_calls == 1


def test_share_sheet_action_prefers_bottom_copy_link() -> None:
    root = ElementTree.fromstring(
        """
        <hierarchy>
          <node package="com.lietou.mishu" text="更多" clickable="true"
                bounds="[870,1136][996,1190]" />
          <node package="com.lietou.mishu" text="更多" clickable="true"
                bounds="[835,1506][1032,1713]" />
          <node package="com.lietou.mishu" text="复制链接" clickable="true"
                bounds="[835,1892][1032,2117]" />
        </hierarchy>
        """
    )

    assert ResultPageFilter._find_bottom_sheet_action(root, "更多") == (933, 1609)
    assert ResultPageFilter._find_bottom_sheet_action(root, "复制链接") == (933, 2004)


def test_share_sheet_is_dismissed_via_bottom_cancel_action(monkeypatch) -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.tap_calls: list[tuple[int, int]] = []
            self.back_calls = 0

        def dump_hierarchy_text(self) -> str:
            return """
                <hierarchy>
                  <node package="com.lietou.mishu" content-desc="取消" clickable="true"
                        bounds="[0,2214][1080,2376]" />
                </hierarchy>
            """

        def tap(self, x: int, y: int) -> None:
            self.tap_calls.append((x, y))

        def press_back(self) -> None:
            self.back_calls += 1

    device = FakeDevice()
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
    )
    monkeypatch.setattr(result_filter, "_wait", lambda *_args, **_kwargs: None)

    result_filter._dismiss_share_sheet(1080, 2388)

    assert device.tap_calls == [(540, 2295)]
    assert device.back_calls == 0


def test_share_sheet_already_closed_never_presses_back(monkeypatch) -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.tap_calls: list[tuple[int, int]] = []
            self.back_calls = 0

        def dump_hierarchy_text(self) -> str:
            return """
                <hierarchy>
                  <node package="com.lietou.mishu" text="工作经历" clickable="false"
                        bounds="[40,400][1040,520]" />
                  <node package="com.lietou.mishu" text="立即沟通" clickable="true"
                        bounds="[550,2214][1050,2376]" />
                </hierarchy>
            """

        def tap(self, x: int, y: int) -> None:
            self.tap_calls.append((x, y))

        def press_back(self) -> None:
            self.back_calls += 1

    device = FakeDevice()
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
    )
    monkeypatch.setattr(result_filter, "_wait", lambda *_args, **_kwargs: None)

    result_filter._dismiss_share_sheet(1080, 2388)

    assert device.tap_calls == []
    assert device.back_calls == 0


def test_candidate_locator_screenshot_uses_windows_safe_filename(monkeypatch, tmp_path) -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.paths: list[Path] = []

        def screenshot(self, path: Path) -> None:
            self.paths.append(path)

    device = FakeDevice()
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
    )
    monkeypatch.setattr(
        result_filter_module,
        "get_settings",
        lambda: SimpleNamespace(artifacts_dir=tmp_path),
    )

    screenshot_path = result_filter._capture_candidate_locator_screenshot(
        seed=SearchTaskSeed(
            brand_keyword="雅诗兰黛",
            role_keyword="达人营销负责人",
            search_pack_id="daren_kol",
        ),
        candidate_name="孟**",
        candidate_fingerprint="孟**|21f404cb3a9806676437fa774abd8a3d",
    )

    assert screenshot_path is not None
    assert device.paths
    assert "|" not in device.paths[0].name


def test_message_screenshots_for_same_name_use_distinct_fingerprint_paths(
    monkeypatch, tmp_path
) -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.paths: list[Path] = []

        def screenshot(self, path: Path) -> None:
            self.paths.append(path)

    device = FakeDevice()
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
    )
    monkeypatch.setattr(
        result_filter_module,
        "get_settings",
        lambda: SimpleNamespace(artifacts_dir=tmp_path),
    )

    first = result_filter._capture_message_result_screenshot(
        candidate_name="张女士",
        candidate_fingerprint="candidate-a",
        status="success",
    )
    second = result_filter._capture_message_result_screenshot(
        candidate_name="张女士",
        candidate_fingerprint="candidate-b",
        status="success",
    )

    assert first is not None
    assert second is not None
    assert first != second
    assert device.paths[0].name != device.paths[1].name
    assert all(path.name.startswith("candidate_") for path in device.paths)


def test_detail_entry_records_locator_without_consuming_share_quota(monkeypatch) -> None:
    result_filter = ResultPageFilter(
        device=object(),  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        write_to_db=True,
        search_batch_id="batch-20260818",
    )
    settings = SimpleNamespace(liepin_account_alias="account-a", device_id="device-a")
    calls: dict[str, object] = {}
    monkeypatch.setattr(result_filter_module, "get_settings", lambda: settings)
    monkeypatch.setattr(
        result_filter, "_capture_candidate_locator_screenshot", lambda **_kwargs: "detail.png"
    )
    monkeypatch.setattr(
        result_filter,
        "_copy_candidate_share_link",
        lambda: calls.update(unexpected_share=True),
    )
    monkeypatch.setattr(
        result_filter_module,
        "record_candidate_source_locator",
        lambda *_args, **kwargs: calls.update(record=kwargs) or 17,
    )
    locator = SimpleNamespace(
        platform_candidate_id=None,
        platform_resume_id=None,
        detail_deep_link=None,
        detail_activity="CandidateActivity",
        detail_hierarchy_hash="hierarchy-hash",
        locator_quality="snapshot",
    )

    locator_id = result_filter._record_entered_candidate_detail(
        seed=SearchTaskSeed(brand_keyword="资生堂", role_keyword="达人营销负责人"),
        card=CandidateCard(
            fingerprint="card-fingerprint",
            raw_text="张女士 资生堂 达人营销负责人",
            bounds="[0,0][100,100]",
        ),
        platform_locator=locator,
        platform_interaction_note="你们之前HR找过我",
    )

    assert locator_id == 17
    assert calls["record"]["candidate_fingerprint"] == "card-fingerprint"  # type: ignore[index]
    assert calls["record"]["platform_interaction_note"] == "你们之前HR找过我"  # type: ignore[index]
    assert calls["record"]["search_batch_id"] == "batch-20260818"  # type: ignore[index]
    assert "unexpected_share" not in calls


def test_high_value_analysis_policy() -> None:
    def result(match_result: str, score: int) -> CozeMatchResult:
        return CozeMatchResult(
            matched=match_result == "relevant",
            match_result=match_result,
            match_score=score,
            score_breakdown={},
            analysis_reason=None,
            evidence_summary=[],
            raw_data="",
            parsed_payload={},
        )

    assert ResultPageFilter._is_high_value_analysis(result("relevant", 80)) is True
    assert ResultPageFilter._is_high_value_analysis(result("needs_review", 90)) is True
    assert ResultPageFilter._is_high_value_analysis(result("needs_review", 89)) is False
    assert ResultPageFilter._is_high_value_analysis(result("not_relevant", 92)) is False


def test_manual_review_handler_skips_ai_and_communication(monkeypatch) -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.back_calls = 0

        def press_back(self) -> None:
            self.back_calls += 1

    device = FakeDevice()
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        write_to_db=True,
    )
    calls: dict[str, object] = {}
    monkeypatch.setattr(result_filter_module, "get_settings", lambda: SimpleNamespace())
    monkeypatch.setattr(result_filter, "_wait", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        result_filter_module,
        "record_candidate_search_hit",
        lambda *_args, **kwargs: calls.update(hit=kwargs) or 1,
    )

    outcome = result_filter._handle_manual_review_suppressed_candidate(
        seed=SearchTaskSeed(
            brand_keyword="",
            role_keyword="达人营销负责人",
            query_text="达人营销负责人",
        ),
        candidate_name="毛先生",
        candidate_fingerprint="candidate-fp",
        communication_label="继续沟通",
        candidate_age=35,
        manual_review_status="interview_rejected",
        manual_review_feedback="已经面试，淘汰。",
    )

    assert outcome.hit_status == "manual_review_suppressed:interview_rejected"
    assert outcome.message_send_success is False
    assert outcome.message_send_reason == "manual_review_suppressed"
    assert outcome.match_result is None
    assert calls["hit"]["hit_status"] == "manual_review_suppressed:interview_rejected"  # type: ignore[index]
    assert device.back_calls == 1


def test_quota_exhausted_high_value_candidate_is_favorited(monkeypatch, tmp_path) -> None:
    result_filter = ResultPageFilter(
        device=object(),  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        write_to_db=True,
    )
    settings = SimpleNamespace(artifacts_dir=tmp_path)
    quota_store = DailyShareQuotaStateStore(tmp_path / "share_quota_state.json")
    quota_store.mark_exhausted(reason="test")
    calls: dict[str, object] = {}
    monkeypatch.setattr(result_filter, "_share_quota_store", lambda: quota_store)
    monkeypatch.setattr(result_filter, "_find_existing_official_share_link", lambda **_kwargs: None)
    monkeypatch.setattr(
        result_filter, "_copy_candidate_share_link", lambda: calls.update(unexpected_share=True)
    )
    monkeypatch.setattr(result_filter, "_favorite_current_candidate", lambda: True)
    monkeypatch.setattr(
        result_filter_module,
        "update_candidate_source_locator_favorite",
        lambda *_args, **kwargs: calls.update(favorite=kwargs),
    )
    analysis_result = CozeMatchResult(
        matched=False,
        match_result="needs_review",
        match_score=90,
        score_breakdown={},
        analysis_reason=None,
        evidence_summary=[],
        raw_data="",
        parsed_payload={},
    )

    result_filter._preserve_high_value_candidate(
        settings=settings,
        locator_record_id=17,
        candidate_name="张女士",
        candidate_fingerprint="candidate-fp",
        experience_hash="experience-hash",
        analysis_result=analysis_result,
    )

    assert "unexpected_share" not in calls
    assert calls["favorite"] == {"locator_id": 17}


def test_existing_share_link_is_reused_without_ui_action(monkeypatch, tmp_path) -> None:
    result_filter = ResultPageFilter(
        device=object(),  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        write_to_db=True,
    )
    settings = SimpleNamespace(artifacts_dir=tmp_path)
    calls: dict[str, object] = {}
    share_link = "https://mr.liepin.com/resume/view-share-res?acKey=existing"
    monkeypatch.setattr(
        result_filter,
        "_find_existing_official_share_link",
        lambda **_kwargs: share_link,
    )
    monkeypatch.setattr(
        result_filter,
        "_copy_candidate_share_link",
        lambda: calls.update(unexpected_share=True),
    )
    monkeypatch.setattr(
        result_filter,
        "_favorite_current_candidate",
        lambda: calls.update(unexpected_favorite=True),
    )
    monkeypatch.setattr(
        result_filter_module,
        "update_candidate_source_locator_share_link",
        lambda *_args, **kwargs: calls.update(reused=kwargs),
    )
    analysis_result = CozeMatchResult(
        matched=True,
        match_result="relevant",
        match_score=86,
        score_breakdown={},
        analysis_reason=None,
        evidence_summary=[],
        raw_data="",
        parsed_payload={},
    )

    result_filter._preserve_high_value_candidate(
        settings=settings,
        locator_record_id=21,
        candidate_name="李女士",
        candidate_fingerprint="candidate-fp",
        experience_hash="experience-hash",
        analysis_result=analysis_result,
    )

    assert calls == {"reused": {"locator_id": 21, "share_link": share_link}}


def test_share_quota_message_detection() -> None:
    root = ElementTree.fromstring(
        '<hierarchy><node text="今日分享次数已达上限，明日再试" /></hierarchy>'
    )

    assert ResultPageFilter._share_quota_is_exhausted_on_page(root) is True


def test_already_favorited_candidate_is_not_toggled_off(monkeypatch) -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.tap_calls: list[tuple[int, int]] = []

        def device_info(self) -> dict[str, int]:
            return {"displayWidth": 1080, "displayHeight": 2388}

        def dump_hierarchy_text(self) -> str:
            return '<hierarchy><node package="com.lietou.mishu" /></hierarchy>'

        def tap(self, x: int, y: int) -> None:
            self.tap_calls.append((x, y))

    device = FakeDevice()
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
    )
    monkeypatch.setattr(result_filter, "_favorite_icon_is_filled", lambda **_kwargs: True)

    assert result_filter._favorite_current_candidate() is True
    assert device.tap_calls == []


def test_cached_contradictory_boolean_never_overrides_match_result() -> None:
    cached = ResultPageFilter._coze_result_from_cache_record(
        {
            "analysis_status": "匹配",
            "beauty_relevant": True,
            "match_result": "needs_review",
            "match_score": 88,
        }
    )

    assert cached is not None
    assert cached.match_result == "needs_review"
    assert cached.matched is False


def test_hit_status_uses_match_result_as_canonical_decision() -> None:
    no_message = MessageSendResult(
        success=False,
        attempts=0,
        reason="not_attempted",
        screenshot_path=None,
    )

    assert (
        ResultPageFilter._derive_hit_status(
            analysis_status="不匹配",
            match_result="needs_review",
            message_send_result=no_message,
            continue_without_db_record=False,
        )
        == "reviewed"
    )
    assert (
        ResultPageFilter._derive_hit_status(
            analysis_status="匹配",
            match_result="not_relevant",
            message_send_result=no_message,
            continue_without_db_record=False,
        )
        == "not_relevant"
    )


def _run_needs_review_candidate(
    monkeypatch,
    *,
    write_to_db: bool,
    cache_record: dict[str, object] | None,
) -> tuple[object, dict[str, object]]:
    class FakeDevice:
        def __init__(self) -> None:
            self.back_calls = 0

        def tap(self, *_args) -> None:
            return None

        def dump_hierarchy_text(self) -> str:
            return "<hierarchy/>"

        def current_app(self) -> dict[str, str]:
            return {}

        def press_back(self) -> None:
            self.back_calls += 1

    device = FakeDevice()
    result_filter = ResultPageFilter(
        device=device,  # type: ignore[arg-type]
        detector=object(),
        settle_seconds=0,
        write_to_db=write_to_db,
        send_messages=True,
    )
    report = SimpleNamespace(
        sections={"求职意向": ["上海"], "工作经历": ["达人营销负责人"]},
        structured_resume={
            "basic_info": {"name": "张女士", "age": 34},
            "work_experiences": [],
            "education_experiences": [],
        },
    )
    needs_review_result = CozeMatchResult(
        matched=False,
        match_result="needs_review",
        match_score=88,
        score_breakdown={
            "beauty_continuity": 30,
            "industry_depth": 30,
            "role_evidence": 28,
        },
        analysis_reason="证据不足，需要人工确认。",
        evidence_summary=["缺少完整业绩数据"],
        raw_data="",
        parsed_payload={},
    )
    calls: dict[str, object] = {"upserts": [], "hits": [], "coze_calls": 0}

    monkeypatch.setattr(
        result_filter_module,
        "get_settings",
        lambda: SimpleNamespace(coze_analysis_version="test-v1"),
    )
    monkeypatch.setattr(result_filter, "_wait", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(result_filter, "_record_entered_candidate_detail", lambda **_kwargs: None)
    monkeypatch.setattr(result_filter, "_is_intention_communication_page", lambda _root: False)
    monkeypatch.setattr(result_filter, "_preserve_high_value_candidate", lambda **_kwargs: None)
    monkeypatch.setattr(
        result_filter,
        "_tap_continue_communication",
        lambda **_kwargs: (_ for _ in ()).throw(
            AssertionError("needs_review must never enter the communication flow")
        ),
    )
    monkeypatch.setattr(
        result_filter_module,
        "find_profile_communication_action",
        lambda _root: ("立即沟通", (1, 1)),
    )
    monkeypatch.setattr(
        result_filter_module,
        "CandidateProfileCollector",
        lambda device: SimpleNamespace(collect_current_profile=lambda: report),
    )
    monkeypatch.setattr(
        result_filter_module,
        "get_candidate_record_by_fingerprint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        result_filter_module,
        "get_candidate_record_by_experience_hash",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        result_filter_module,
        "get_analysis_cache_record",
        lambda *_args, **_kwargs: cache_record,
    )

    def analyze(*_args, **_kwargs):
        calls["coze_calls"] = int(calls["coze_calls"]) + 1
        return needs_review_result

    monkeypatch.setattr(result_filter_module, "analyze_resume_record_with_coze", analyze)
    monkeypatch.setattr(
        result_filter_module,
        "upsert_analysis_cache_record",
        lambda *_args, **kwargs: calls["upserts"].append(kwargs) or 1,  # type: ignore[union-attr]
    )
    monkeypatch.setattr(
        result_filter_module,
        "record_candidate_search_hit",
        lambda *_args, **kwargs: calls["hits"].append(kwargs) or 1,  # type: ignore[union-attr]
    )

    outcome = result_filter._collect_profile_for_candidate(
        seed=SearchTaskSeed(
            brand_keyword="珀莱雅",
            role_keyword="达人营销负责人",
            query_text="达人营销负责人",
        ),
        card=CandidateCard(
            fingerprint="card-needs-review",
            raw_text="张女士 34岁 本科 达人营销负责人 上海",
            bounds="[0,0][100,100]",
        ),
    )
    calls["back_calls"] = device.back_calls
    return outcome, calls


def test_fresh_needs_review_is_persisted_and_hit_as_reviewed_without_message(monkeypatch) -> None:
    outcome, calls = _run_needs_review_candidate(
        monkeypatch,
        write_to_db=True,
        cache_record=None,
    )

    assert outcome.analysis_status == "待复核"
    assert outcome.match_result == "needs_review"
    assert outcome.hit_status == "reviewed"
    assert outcome.message_send_success is False
    assert outcome.message_send_reason == "not_attempted"
    assert calls["coze_calls"] == 1
    assert len(calls["upserts"]) == 1
    assert calls["upserts"][0]["analysis_status"] == "待复核"  # type: ignore[index]
    assert calls["hits"][0]["hit_status"] == "reviewed"  # type: ignore[index]
    assert calls["back_calls"] == 1


def test_cached_needs_review_repairs_legacy_status_and_never_messages(monkeypatch) -> None:
    outcome, calls = _run_needs_review_candidate(
        monkeypatch,
        write_to_db=True,
        cache_record={
            "analysis_status": "不匹配",
            "match_result": "needs_review",
            "match_score": 88,
            "score_breakdown_json": (
                '{"beauty_continuity":30,"industry_depth":30,"role_evidence":28}'
            ),
            "analysis_reason": "证据不足，需要人工确认。",
            "evidence_summary_json": '["缺少完整业绩数据"]',
        },
    )

    assert outcome.analysis_status == "待复核"
    assert outcome.hit_status == "reviewed"
    assert outcome.message_send_reason == "not_attempted"
    assert calls["coze_calls"] == 0
    assert len(calls["upserts"]) == 1
    repaired = calls["upserts"][0]  # type: ignore[index]
    assert repaired["analysis_status"] == "待复核"
    assert repaired["match_score"] == 88
    assert repaired["score_breakdown"] == {
        "beauty_continuity": 30,
        "industry_depth": 30,
        "role_evidence": 28,
    }
    assert repaired["analysis_reason"] == "证据不足，需要人工确认。"
    assert repaired["evidence_summary"] == ["缺少完整业绩数据"]
    assert calls["hits"][0]["hit_status"] == "reviewed"  # type: ignore[index]


def test_cached_needs_review_with_correct_status_is_not_rewritten(monkeypatch) -> None:
    outcome, calls = _run_needs_review_candidate(
        monkeypatch,
        write_to_db=True,
        cache_record={
            "analysis_status": "待复核",
            "match_result": "needs_review",
            "match_score": 88,
            "score_breakdown_json": "{}",
            "analysis_reason": "证据不足，需要人工确认。",
            "evidence_summary_json": "[]",
        },
    )

    assert outcome.analysis_status == "待复核"
    assert outcome.hit_status == "reviewed"
    assert outcome.message_send_reason == "not_attempted"
    assert calls["coze_calls"] == 0
    assert calls["upserts"] == []


def test_transient_needs_review_cache_status_is_not_rewritten_before_retry_ttl(monkeypatch) -> None:
    outcome, calls = _run_needs_review_candidate(
        monkeypatch,
        write_to_db=True,
        cache_record={
            "analysis_status": "不匹配",
            "match_result": "needs_review",
            "match_score": None,
            "score_breakdown_json": "{}",
            "analysis_reason": "需要人工复核：Coze API 临时异常，系统未进行自动匹配判断。",
            "evidence_summary_json": "[]",
            "updated_at": datetime.now(),
        },
    )

    assert outcome.analysis_status == "待复核"
    assert outcome.hit_status == "reviewed"
    assert outcome.message_send_reason == "not_attempted"
    assert calls["coze_calls"] == 0
    assert calls["upserts"] == []


def test_read_only_needs_review_does_not_write_cache_or_hit(monkeypatch) -> None:
    outcome, calls = _run_needs_review_candidate(
        monkeypatch,
        write_to_db=False,
        cache_record=None,
    )

    assert outcome.analysis_status == "待复核"
    assert outcome.hit_status == "reviewed"
    assert outcome.message_send_reason == "not_attempted"
    assert calls["coze_calls"] == 1
    assert calls["upserts"] == []
    assert calls["hits"] == []
