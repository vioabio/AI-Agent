from __future__ import annotations

import argparse

from liepin_rpa.candidate_locator import is_official_resume_share_link
from liepin_rpa.config import get_settings
from liepin_rpa.db import list_candidate_source_locators
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Open a saved official Liepin resume share link on the connected phone."
    )
    identity = parser.add_mutually_exclusive_group(required=True)
    identity.add_argument("--candidate-fingerprint")
    identity.add_argument("--experience-hash")
    identity.add_argument(
        "--from-clipboard",
        action="store_true",
        help="Use the official share URL currently in the connected phone clipboard.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    settings = get_settings()
    runner = AdbRunner(settings.adb_executable, settings.device_id)
    device = DeviceClient(runner, settings.device_id)
    device.connect()

    if args.from_clipboard:
        share_link = device.get_clipboard()
    else:
        rows = list_candidate_source_locators(
            settings,
            candidate_fingerprint=args.candidate_fingerprint,
            experience_hash=args.experience_hash,
            limit=20,
        )
        share_link = next(
            (
                str(row.get("detail_deep_link") or "").strip()
                for row in rows
                if is_official_resume_share_link(str(row.get("detail_deep_link") or ""))
            ),
            None,
        )

    if not is_official_resume_share_link(share_link):
        raise RuntimeError("No official Liepin resume share link was found.")
    runner.shell(
        "am",
        "start",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        f"'{share_link}'",
    )
    print("Opened the saved Liepin resume share link on the connected phone.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
