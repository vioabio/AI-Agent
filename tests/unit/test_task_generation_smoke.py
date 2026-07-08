from pathlib import Path

from liepin_rpa.task_generation.generator import generate_search_task_seeds


def test_generate_search_task_seeds_matches_frozen_catalog_size() -> None:
    tasks = generate_search_task_seeds(
        brands_path=Path("config/brands.yaml"),
        roles_path=Path("config/roles.yaml"),
    )

    assert len(tasks) == 636
    assert len({task.brand_keyword for task in tasks}) == 53
    assert len({task.role_keyword for task in tasks}) == 12
