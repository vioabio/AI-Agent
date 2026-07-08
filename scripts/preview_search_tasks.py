from __future__ import annotations

import json
import sys
from dataclasses import asdict
from pathlib import Path

from liepin_rpa.task_generation.generator import generate_search_task_seeds


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

    tasks = generate_search_task_seeds(
        brands_path=Path("config/brands.yaml"),
        roles_path=Path("config/roles.yaml"),
    )
    payload = {
        "brand_count": len({task.brand_keyword for task in tasks}),
        "role_count": len({task.role_keyword for task in tasks}),
        "task_count": len(tasks),
        "first_five": [asdict(task) for task in tasks[:5]],
        "last_five": [asdict(task) for task in tasks[-5:]],
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
