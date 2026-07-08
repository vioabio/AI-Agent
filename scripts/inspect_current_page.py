from __future__ import annotations

import json
import sys
import argparse
from pathlib import Path

from liepin_rpa.page_detection.runtime import build_page_detector


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--xml-path",
        default="artifacts/device_diagnose/20260707_162937.xml",
        help="Path to the XML hierarchy file to inspect.",
    )
    return parser.parse_args()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()
    xml_path = Path(args.xml_path)
    detector = build_page_detector()
    result = detector.detect_from_xml(xml_path)
    print(
        json.dumps(
            {
                "xml_path": str(xml_path),
                "state": result.state.value,
                "matched_signature": result.matched_signature,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
