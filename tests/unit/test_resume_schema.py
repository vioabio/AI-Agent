from pathlib import Path

from liepin_rpa.db import build_candidate_experience_text
from liepin_rpa.profile_collection.resume_schema import (
    CROSS_CANDIDATE_CONTAMINATION_WARNING,
    UNIFIED_BACHELOR_NOT_QUALIFIED,
    UNIFIED_BACHELOR_QUALIFIED,
    UNIFIED_BACHELOR_UNKNOWN,
    build_candidate_resume_schema,
    build_candidate_resume_from_debug_path,
    evaluate_unified_bachelor_requirement,
    extract_explicit_job_intent_city,
    has_explicit_job_intent_city,
    parse_basic_info,
    parse_education_experiences,
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
    education = payload["education_experiences"][0]
    assert education["school_name"] == "南华大学"
    assert education["degree"] == "本科"
    assert education["major"] == "市场营销"
    assert education["admission_type"] == "统招"
    assert education["school_tags"] == ["211"]
    assert payload["basic_info"]["education_admission_type"] == "统招"
    assert payload["basic_info"]["unified_bachelor_requirement"] == "qualified"
    assert "统招本科条件：满足" in (
        build_candidate_experience_text(structured_resume=payload) or ""
    )


def test_parses_mobile_education_card_badges_without_confusing_the_school() -> None:
    items, warnings = parse_education_experiences(
        [
            "教育经历",
            "华南理工大学",
            "统招",
            "985",
            "本科 · 财务管理",
            "2011.09-2015.07",
        ]
    )

    assert warnings == []
    assert items == [
        {
            "education_id": "edu_001",
            "school_name": "华南理工大学",
            "major": "财务管理",
            "degree": "本科",
            "admission_type": "统招",
            "school_tags": ["985"],
            "start_date": "2011-09",
            "end_date": "2015-07",
            "raw_text": "华南理工大学 统招 985 本科 · 财务管理 2011.09-2015.07",
        }
    ]


def test_education_parser_ignores_text_before_the_nearest_school() -> None:
    items, warnings = parse_education_experiences(
        [
            "教育经历",
            "参与集团SAP财务信息化项目实施，负责总账数据初始化。",
            "*该段内容已整合附件简历信息",
            "华南理工大学",
            "本科 · 财务管理",
            "统招",
            "985",
            "2011.09-2015.07",
        ]
    )

    assert warnings == []
    assert items[0]["school_name"] == "华南理工大学"
    assert items[0]["admission_type"] == "统招"
    assert items[0]["raw_text"] == (
        "华南理工大学 统招 985 本科 · 财务管理 2011.09-2015.07"
    )


def test_education_parser_rejects_work_periods_with_duration_suffix() -> None:
    items, warnings = parse_education_experiences(
        [
            "教育经历",
            "上海市东华大学",
            "非统招",
            "211",
            "本科 · 会计学",
            "2016.02-2018.06",
            "工作经历",
            "某公司",
            "总账会计",
            "2012.12-2016.09(3年9个月)",
            "中专/中技 · 税务会计",
        ]
    )

    assert warnings == []
    assert len(items) == 1
    assert items[0]["school_name"] == "上海市东华大学"
    assert items[0]["admission_type"] == "非统招"


def test_education_parser_does_not_invent_a_school_from_work_text() -> None:
    items, warnings = parse_education_experiences(
        [
            "教育经历",
            "协助完成报表整理与数据统计，配合日常业务支持工作。",
            "统招",
            "中专/中技 · 税务会计",
            "2007.09-2011.06",
        ]
    )

    assert warnings == []
    assert items[0]["school_name"] is None
    assert items[0]["degree"] == "中专"
    assert items[0]["major"] == "税务会计"
    assert items[0]["admission_type"] == "统招"
    assert items[0]["raw_text"] == "统招 中专/中技 · 税务会计 2007.09-2011.06"


def test_unified_bachelor_requirement_uses_explicit_mobile_badge() -> None:
    assert evaluate_unified_bachelor_requirement([]) == UNIFIED_BACHELOR_UNKNOWN
    assert (
        evaluate_unified_bachelor_requirement(
            [{"degree": "本科", "admission_type": None}]
        )
        == UNIFIED_BACHELOR_NOT_QUALIFIED
    )
    assert (
        evaluate_unified_bachelor_requirement(
            [{"degree": "本科", "admission_type": "非统招"}]
        )
        == UNIFIED_BACHELOR_NOT_QUALIFIED
    )
    assert (
        evaluate_unified_bachelor_requirement(
            [
                {"degree": "本科", "admission_type": "统招"},
                {"degree": "硕士", "admission_type": "非统招"},
            ]
        )
        == UNIFIED_BACHELOR_QUALIFIED
    )


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


def test_resume_schema_trims_other_candidate_cards_from_detail_tail(
    tmp_path: Path,
) -> None:
    payload = build_candidate_resume_schema(
        debug_payload={
            "sections": {
                "_profile_header": [
                    "李女士",
                    "某集团 · 海外财务经理",
                    "上海",
                    "硕士",
                    "工作10年",
                    "33岁",
                ],
                "求职意向": ["财务共享与管理经理", "上海"],
            },
            "collected_text_lines": [
                "工作经历",
                "2022.01-至今(4年)",
                "某集团",
                "海外财务经理",
                "负责海外财务共享项目与SAP实施",
                "苏女士 在线 上海 42岁 硕士 16年 50-55K",
                "另一家公司",
                "财务总监",
            ],
            "iterations": [],
        },
        output_dir=tmp_path,
    )

    full_text = payload["raw_resume"]["full_text"]
    assert "负责海外财务共享项目与SAP实施" in full_text
    assert "苏女士" not in full_text
    assert "另一家公司" not in full_text
    assert CROSS_CANDIDATE_CONTAMINATION_WARNING in payload["parse_metadata"]["warnings"]
    assert payload["parse_metadata"]["requires_manual_review"] is True
