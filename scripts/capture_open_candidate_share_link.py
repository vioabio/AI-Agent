from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from xml.etree import ElementTree

from liepin_rpa.candidate_locator import is_official_resume_share_link
from liepin_rpa.config import get_settings
from liepin_rpa.db import record_candidate_source_locator
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.search_execution.result_filter import ResultPageFilter


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Copy and persist the official share link for the resume currently open on the phone."
    )
    parser.add_argument("--targets-json", type=Path, required=True)
    parser.add_argument(
        "--use-current-clipboard",
        action="store_true",
        help="Persist a share link that has just been copied and verified on the current page.",
    )
    return parser.parse_args()


def _visible_labels(xml_text: str) -> list[str]:
    root = ElementTree.fromstring(xml_text)
    labels: list[str] = []
    for node in root.iter("node"):
        label = (node.attrib.get("content-desc") or node.attrib.get("text") or "").strip()
        if label and label not in labels:
            labels.append(label)
    return labels


def _names_compatible(visible: str, expected: str) -> bool:
    visible = visible.strip()
    expected = expected.strip()
    if not visible or not expected:
        return False
    return visible == expected or visible[0] == expected[0]


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()
    targets = json.loads(args.targets_json.read_text(encoding="utf-8-sig"))
    settings = get_settings()
    runner = AdbRunner(settings.adb_executable, settings.device_id)
    device = DeviceClient(runner, settings.device_id)
    device.connect()

    labels = _visible_labels(device.dump_hierarchy_text())
    if not labels or not any(marker in labels for marker in ("求职意向", "立即沟通", "继续沟通")):
        raise RuntimeError("Phone is not on a candidate resume detail page.")
    matching_pairs = [
        (label, item)
        for label in labels
        for item in targets
        if _names_compatible(label, str(item.get("name") or ""))
    ]
    if len(matching_pairs) == 1:
        visible_name, matched_target = matching_pairs[0]
        matching = [matched_target]
    else:
        visible_name = ""
        matching = []
    if len(matching) != 1:
        raise RuntimeError(
            f"Current candidate {visible_name!r} does not uniquely match the target list."
        )
    target = matching[0]

    result_filter = ResultPageFilter(
        device=device,
        settle_seconds=0,
        write_to_db=False,
        send_messages=False,
    )
    result_filter._wait = lambda *_args, **_kwargs: time.sleep(0.7)  # type: ignore[method-assign]
    share_link = (
        device.get_clipboard()
        if args.use_current_clipboard
        else result_filter._copy_candidate_share_link()
    )
    if not is_official_resume_share_link(share_link):
        raise RuntimeError("The copied clipboard value is not an official Liepin resume share link.")

    screenshot_path = (
        settings.artifacts_dir
        / "candidate_locators"
        / f"{target['fingerprint']}_share_link.png"
    )
    screenshot_path.parent.mkdir(parents=True, exist_ok=True)
    device.screenshot(screenshot_path)
    locator_id = record_candidate_source_locator(
        settings,
        candidate_fingerprint=str(target["fingerprint"]),
        experience_hash=str(target.get("experience_hash") or ""),
        candidate_name=str(target.get("name") or visible_name),
        search_keyword=str(target.get("keywords") or ""),
        source_account_alias=settings.liepin_account_alias,
        source_device_id=settings.device_id,
        job_profile_id="daren_live_bd_jd_v1",
        candidate_card_text="\n".join(labels[:12]),
        detail_deep_link=share_link,
        locator_quality="share_link",
        detail_screenshot_path=str(screenshot_path),
    )
    print(
        json.dumps(
            {
                "status": "saved",
                "candidate_name": target.get("name"),
                "match_score": target.get("score"),
                "locator_id": locator_id,
                "share_link_verified": True,
                "screenshot_path": str(screenshot_path),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
