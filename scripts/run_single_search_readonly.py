from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from liepin_rpa.app_runtime import ensure_liepin_started, resolve_liepin_package
from liepin_rpa.config import get_settings
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.logging_utils import configure_logging
from liepin_rpa.search_execution import FrozenKeywordCatalog, ReadonlySearchExecutor
from liepin_rpa.task_generation.models import SearchTaskSeed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--brand", required=True, help="Frozen brand keyword.")
    parser.add_argument("--role", required=True, help="Frozen role keyword.")
    parser.add_argument(
        "--output-dir",
        default="outputs/readonly_search",
        help="Directory to store screenshots, hierarchy dumps, and the report.",
    )
    return parser.parse_args()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()

    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)

    seed = SearchTaskSeed(brand_keyword=args.brand, role_keyword=args.role)
    catalog = FrozenKeywordCatalog(
        brands_path=Path("config/brands.yaml"),
        roles_path=Path("config/roles.yaml"),
    )
    catalog.validate_seed(seed)
    query_text = catalog.format_exact_query(seed)

    adb_runner = AdbRunner(settings.adb_executable)
    adb_runner.clear_uiautomator_sessions()
    device = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)
    device.connect()
    ensure_liepin_started(device, resolve_liepin_package(settings.liepin_package))

    executor = ReadonlySearchExecutor(device=device)
    report = executor.run(seed=seed, query_text=query_text, output_dir=Path(args.output_dir))
    print(
        json.dumps(
            {
                "query_text": report.query_text,
                "brand_keyword": report.brand_keyword,
                "role_keyword": report.role_keyword,
                "report_path": report.report_path,
                "steps": [step.step_name for step in report.steps],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
