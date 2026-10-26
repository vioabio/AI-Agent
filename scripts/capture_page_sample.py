from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from liepin_rpa.config import get_settings
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.logging_utils import configure_logging
from liepin_rpa.page_detection.debug_bundle import create_unknown_page_debug_bundle
from liepin_rpa.page_detection.runtime import build_page_detector


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", required=True, help="Sample name, e.g. home_recommend")
    parser.add_argument(
        "--fixtures-dir",
        default="tests/fixtures/ui",
        help="Directory to store captured UI fixtures.",
    )
    return parser.parse_args()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()
    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)

    output_dir = Path(args.fixtures_dir) / args.name
    output_dir.mkdir(parents=True, exist_ok=True)

    client = DeviceClient(adb_runner=AdbRunner(settings.adb_executable), device_id=settings.device_id)
    client.connect()
    current_app = client.current_app()
    screenshot_path = client.screenshot(output_dir / "screen.png")
    hierarchy_path = client.dump_hierarchy(output_dir / "hierarchy.xml")

    detector = build_page_detector()
    detection = detector.detect_from_xml(hierarchy_path)
    metadata_path = output_dir / "metadata.json"
    metadata_path.write_text(
        json.dumps(
            {
                "page_name": args.name,
                "package": current_app.get("package"),
                "activity": current_app.get("activity"),
                "detected_state": detection.state.value,
                "matched_signature": detection.matched_signature,
                "screenshot_path": str(screenshot_path),
                "hierarchy_path": str(hierarchy_path),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    if detection.matched_signature is None:
        bundle = create_unknown_page_debug_bundle(
            device=client,
            output_dir=settings.artifacts_dir / "unknown_pages",
            reason=f"unknown page during sample capture: {args.name}",
            current_app=current_app,
        )
        print(
            json.dumps(
                {
                    "status": "unknown",
                    "page_name": args.name,
                    "debug_bundle": str(bundle.bundle_dir),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 1

    print(
        json.dumps(
            {
                "status": "ok",
                "page_name": args.name,
                "detected_state": detection.state.value,
                "matched_signature": detection.matched_signature,
                "metadata_path": str(metadata_path),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
