from pathlib import Path

from liepin_rpa.task_generation.generator import (
    generate_jd_smart_search_task_seeds,
    generate_search_task_seeds,
)
from liepin_rpa.task_generation.config_loader import load_brand_keywords, load_role_keywords


def test_generate_search_task_seeds_matches_frozen_catalog_size() -> None:
    tasks = generate_search_task_seeds(
        brands_path=Path("config/brands.yaml"),
        roles_path=Path("config/roles.yaml"),
    )

    brands = load_brand_keywords(Path("config/brands.yaml"))
    roles = load_role_keywords(Path("config/roles.yaml"))
    assert len(tasks) == len(brands) * len(roles)
    assert len(brands) == 150
    assert len(roles) == 15
    assert len(tasks) == 2250
    assert len({task.brand_keyword for task in tasks}) == len(brands)
    assert len({task.role_keyword for task in tasks}) == len(roles)
    assert tasks[0].brand_keyword == "海蓝之谜"
    assert tasks[0].role_keyword == "达人营销总监"


def test_finance_job_profile_generates_prioritized_unique_queries() -> None:
    tasks = generate_jd_smart_search_task_seeds(
        Path("config/job_profiles/finance_shared_management.yaml")
    )

    assert len(tasks) == 22
    assert len({task.effective_query for task in tasks}) == 22
    assert all(task.job_profile_id == "finance_shared_management_v1" for task in tasks)
    assert [task.search_pack_id for task in tasks[:6]] == [
        "finance_shared",
        "group_accounting",
        "finance_transformation",
        "finance_shared",
        "group_accounting",
        "finance_transformation",
    ]
    assert [task.effective_query for task in tasks[:3]] == [
        "财务共享中心负责人",
        "集团核算负责人",
        "财务流程优化经理",
    ]
    assert "高级财务经理" not in {task.effective_query for task in tasks}
    assert "财务系统经理" not in {task.effective_query for task in tasks}


def test_daren_job_profile_interleaves_search_packs() -> None:
    tasks = generate_jd_smart_search_task_seeds(
        Path("config/job_profiles/daren_live_bd.yaml")
    )

    assert len(tasks) == 96
    assert [task.search_pack_id for task in tasks[:8]] == [
        "daren_kol",
        "live_commerce",
        "resource_bd",
        "social_content",
        "daren_kol",
        "live_commerce",
        "resource_bd",
        "social_content",
    ]
    assert len({task.effective_query for task in tasks}) == len(tasks)
