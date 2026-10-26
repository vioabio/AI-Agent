from __future__ import annotations

import argparse
import json

from liepin_rpa.candidate_locator import build_cross_account_lookup_strategy
from liepin_rpa.config import get_settings
from liepin_rpa.db import list_candidate_source_locators


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Find saved Liepin source observations for cross-account resume lookup."
    )
    parser.add_argument("--candidate-fingerprint")
    parser.add_argument("--experience-hash")
    parser.add_argument("--platform-candidate-id")
    parser.add_argument("--platform-resume-id")
    parser.add_argument("--limit", type=int, default=20)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    rows = list_candidate_source_locators(
        get_settings(),
        candidate_fingerprint=args.candidate_fingerprint,
        experience_hash=args.experience_hash,
        platform_candidate_id=args.platform_candidate_id,
        platform_resume_id=args.platform_resume_id,
        limit=args.limit,
    )
    payload = [
        {
            "lookup_strategy": build_cross_account_lookup_strategy(row),
            "observation": {
                key: value.isoformat() if hasattr(value, "isoformat") else value
                for key, value in row.items()
            },
        }
        for row in rows
    ]
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
