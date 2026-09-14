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

    assert len(tasks) == 42
    assert len({task.effective_query for task in tasks}) == 42
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
    assert {
        "企业财务共享 流程迁移",
        "民营集团 多主体核算",
        "连锁企业 多门店财务",
        "账务整改 往来清理",
        "ERP 系统切换 财务",
        "审计转企业 集团核算",
    }.issubset({task.effective_query for task in tasks})


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


def test_ai_development_job_profile_generates_strict_unique_queries() -> None:
    tasks = generate_jd_smart_search_task_seeds(
        Path("config/job_profiles/ai_development_engineer.yaml")
    )

    assert len(tasks) == 32
    assert len({task.effective_query for task in tasks}) == 32
    assert all(task.job_profile_id == "ai_development_engineer_v1" for task in tasks)
    assert [task.search_pack_id for task in tasks[:8]] == [
        "ai_application_development",
        "ai_backend_engineering",
        "agent_rag_workflow",
        "business_integration",
        "ai_application_development",
        "ai_backend_engineering",
        "agent_rag_workflow",
        "business_integration",
    ]


def test_fde_job_profile_prioritizes_deployment_and_healthcare_queries() -> None:
    tasks = generate_jd_smart_search_task_seeds(
        Path("config/job_profiles/fde_ai_deployment_engineer_draft.yaml")
    )

    queries = {task.effective_query for task in tasks}
    assert len(tasks) == 40
    assert len(queries) == 40
    assert all(task.job_profile_id == "fde_ai_deployment_engineer_v1" for task in tasks)
    assert {
        "医疗AI实施工程师",
        "医美AI实施",
        "大健康AI项目交付",
        "HIS EMR AI集成",
        "RAG 生产部署",
        "AI系统集成工程师",
    }.issubset(queries)
    assert "B端AI产品经理" not in queries
    assert "AI售前解决方案" not in queries


def test_serial_queue_runs_finance_before_daren() -> None:
    script = Path("scripts/run_serial_job_queue.ps1").read_text(encoding="utf-8")

    assert '$jobOrder = @("finance_shared_management_v1", "daren_live_bd_v1")' in script
    assert "legacy active batch detected; preserving daren-first order" in script
