from pathlib import Path

from liepin_rpa.profile_collection.resume_schema import (
    build_candidate_resume_from_debug_path,
    extract_explicit_job_intent_city,
    has_explicit_job_intent_city,
    parse_basic_info,
)

FIXTURES_DIR = Path(__file__).parents[1] / "fixtures"


def test_build_candidate_resume_from_debug_report() -> None:
    payload = build_candidate_resume_from_debug_path(
        FIXTURES_DIR / "resume_debug_report.json",
        search_brand_keyword="珀莱雅",
        search_role_keyword="达播总监",
        search_task_id="task_000123",
    )

    assert payload["schema_version"] == "1.0"
    assert payload["source"]["search_brand_keyword"] == "珀莱雅"
    assert payload["source"]["search_role_keyword"] == "达播总监"
    assert payload["basic_info"]["name"] == "李女士"
    assert payload["basic_info"]["current_company"] == "珀莱雅"
    assert payload["basic_info"]["expected_city"] == "上海"
    assert payload["basic_info"]["job_intent_raw"] == "达人营销总监 上海"
    assert payload["work_experiences"]
    assert payload["work_experiences"][0]["company"]["raw_name"] == "珀莱雅"
    assert payload["education_experiences"]


def test_explicit_shanghai_job_intent_is_required() -> None:
    assert has_explicit_job_intent_city({"求职意向": ["期望城市：上海"]}, "上海")
    assert has_explicit_job_intent_city({"求职意向": ["上海/杭州均可"]}, "上海")
    assert not has_explicit_job_intent_city({"求职意向": ["期望城市：杭州"]}, "上海")
    assert not has_explicit_job_intent_city({}, "上海")


def test_current_city_does_not_replace_job_intent_city() -> None:
    sections = {
        "_profile_header": ["张女士", "某公司 · 财务经理", "上海"],
        "求职意向": ["财务共享经理", "杭州"],
    }

    assert not has_explicit_job_intent_city(sections, "上海")
    assert extract_explicit_job_intent_city(sections["求职意向"]) is None


def test_negated_shanghai_job_intent_is_rejected() -> None:
    assert not has_explicit_job_intent_city({"求职意向": ["不考虑上海"]}, "上海")
    assert not has_explicit_job_intent_city({"求职意向": ["上海除外"]}, "上海")


def test_profile_header_skips_previous_hr_contact_banner_and_badge() -> None:
    parsed = parse_basic_info(
        {
            "_profile_header": [
                "你们之前HR找过我",
                "2",
                "毛先生",
                "某公司 · 红人负责人",
                "上海",
                "本科",
                "工作12年",
                "35岁",
            ]
        }
    )

    assert parsed["name"] == "毛先生"
    assert parsed["current_company"] == "某公司"
    assert parsed["current_position"] == "红人负责人"
    assert parsed["city"] == "上海"
    assert parsed["education"] == "本科"
    assert parsed["work_years"] == 12
    assert parsed["age"] == 35
