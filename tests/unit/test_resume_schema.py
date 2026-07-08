from pathlib import Path

from liepin_rpa.profile_collection.resume_schema import build_candidate_resume_from_debug_path


def test_build_candidate_resume_from_debug_report() -> None:
    payload = build_candidate_resume_from_debug_path(
        Path("outputs/profile_collection/live_current_profile/report.json"),
        search_brand_keyword="珀莱雅",
        search_role_keyword="达播总监",
        search_task_id="task_000123",
    )

    assert payload["schema_version"] == "1.0"
    assert payload["source"]["search_brand_keyword"] == "珀莱雅"
    assert payload["source"]["search_role_keyword"] == "达播总监"
    assert payload["basic_info"]["name"] == "李女士"
    assert payload["basic_info"]["current_company"] == "珀莱雅"
    assert payload["work_experiences"]
    assert payload["work_experiences"][0]["company"]["raw_name"] == "珀莱雅"
    assert payload["education_experiences"]
    assert payload["raw_resume"]["screenshots"]
