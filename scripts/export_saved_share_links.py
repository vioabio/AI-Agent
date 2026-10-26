from __future__ import annotations

import argparse
import json
from pathlib import Path

from liepin_rpa.candidate_locator import is_official_resume_share_link
from liepin_rpa.config import get_settings
from liepin_rpa.db import list_candidate_source_locators


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--targets-json", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    args = parser.parse_args()

    targets = json.loads(args.targets_json.read_text(encoding="utf-8-sig"))
    settings = get_settings()
    rows: list[dict[str, object]] = []
    for target in targets:
        locators = list_candidate_source_locators(
            settings,
            candidate_fingerprint=str(target["fingerprint"]),
            experience_hash=str(target.get("experience_hash") or ""),
            limit=20,
        )
        locator = next(
            (
                row
                for row in locators
                if is_official_resume_share_link(str(row.get("detail_deep_link") or ""))
            ),
            None,
        )
        rows.append(
            {
                **target,
                "share_link": str((locator or {}).get("detail_deep_link") or ""),
                "locator_id": (locator or {}).get("id"),
                "locator_quality": (locator or {}).get("locator_quality"),
            }
        )
    payload = {
        "target_count": len(targets),
        "verified_count": sum(bool(row["share_link"]) for row in rows),
        "rows": rows,
    }
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"target_count": payload["target_count"], "verified_count": payload["verified_count"]}))
    return 0 if payload["verified_count"] == payload["target_count"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
