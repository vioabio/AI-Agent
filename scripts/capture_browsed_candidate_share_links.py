from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path
from xml.etree import ElementTree

from rapidfuzz import fuzz

from liepin_rpa.candidate_locator import is_official_resume_share_link
from liepin_rpa.config import get_settings
from liepin_rpa.db import (
    build_candidate_experience_text,
    build_experience_hash,
    get_analysis_cache_record,
    record_candidate_source_locator,
)
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.profile_collection import CandidateProfileCollector
from liepin_rpa.reporting_rules import is_actionable_report_result
from liepin_rpa.search_execution.result_filter import ResultPageFilter
from liepin_rpa.ui_controls import parse_bounds


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Capture official share links for selected candidates in 浏览过的简历."
    )
    parser.add_argument("--source-json", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--candidate-name", action="append", default=[])
    parser.add_argument("--max-list-swipes", type=int, default=100)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    targets = load_targets(args.source_json, set(args.candidate_name))
    settings = get_settings()
    runner = AdbRunner(settings.adb_executable, settings.device_id)
    device = DeviceClient(runner, settings.device_id)
    device.connect()
    result_filter = ResultPageFilter(
        device=device,
        settle_seconds=0,
        write_to_db=False,
        send_messages=False,
    )
    result_filter._wait = lambda *_args, **_kwargs: time.sleep(0.7)  # type: ignore[method-assign]

    assert_browsed_resume_list(device)
    rewind_browsed_list_to_top(device)
    found: list[dict[str, object]] = []
    pending = {str(item["candidate_fingerprint"]): item for item in targets}
    seen_cards: set[str] = set()
    stagnant = 0

    for iteration in range(1, args.max_list_swipes + 1):
        root = ElementTree.fromstring(device.dump_hierarchy_text())
        cards = extract_browsed_cards(root)
        new_cards = 0
        for card in cards:
            card_key = str(card["text"])
            if card_key not in seen_cards:
                seen_cards.add(card_key)
                new_cards += 1
            target = next(
                (item for item in pending.values() if card_matches_target(card, item)),
                None,
            )
            if target is None:
                continue
            result = capture_current_target(
                device=device,
                result_filter=result_filter,
                settings=settings,
                target=target,
                card=card,
            )
            found.append(result)
            if result.get("verified"):
                pending.pop(str(target["candidate_fingerprint"]), None)
            if not pending:
                break
            time.sleep(1.0)
            root = ElementTree.fromstring(device.dump_hierarchy_text())
            assert "浏览过的简历" in hierarchy_text(root)
        if not pending:
            break
        stagnant = stagnant + 1 if new_cards == 0 else 0
        if stagnant >= 6:
            break
        device.swipe(540, 1950, 540, 520, 0.25)
        time.sleep(0.55)

    payload = {
        "captured_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "source_json": str(args.source_json),
        "target_count": len(targets),
        "verified_count": sum(bool(item.get("verified")) for item in found),
        "results": found,
        "missing": list(pending.values()),
    }
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if not pending else 2


def load_targets(path: Path, selected_names: set[str]) -> list[dict[str, object]]:
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    targets: list[dict[str, object]] = []
    for job in payload.get("jobs", []):
        for candidate in job.get("candidates", []):
            score = candidate.get("match_score")
            qualified = is_actionable_report_result(
                candidate.get("match_result"),
                score,
                analysis_status=candidate.get("analysis_status"),
                analysis_reason=candidate.get("analysis_reason"),
            )
            if not qualified:
                continue
            name = str(candidate.get("candidate_name") or "").strip()
            if selected_names and name not in selected_names:
                continue
            company, position = extract_current_employment(str(candidate.get("resume_content") or ""))
            targets.append(
                {
                    "candidate_name": name,
                    "candidate_fingerprint": candidate.get("candidate_fingerprint"),
                    "experience_hash": candidate.get("experience_hash"),
                    "expected_job": candidate.get("expected_job") or job.get("name"),
                    "analysis_version": job.get("analysis_version"),
                    "match_result": candidate.get("match_result"),
                    "match_score": score,
                    "current_company": company,
                    "current_position": position,
                    "first_hit_at": candidate.get("first_hit_at"),
                }
            )
    targets.sort(key=lambda item: str(item.get("first_hit_at") or ""), reverse=True)
    return targets


def extract_current_employment(resume_content: str) -> tuple[str, str]:
    match = re.search(r"工作内容：([^；]+)；([^；]+)；", resume_content)
    return (match.group(1).strip(), match.group(2).strip()) if match else ("", "")


def assert_browsed_resume_list(device: DeviceClient) -> None:
    root = ElementTree.fromstring(device.dump_hierarchy_text())
    if "浏览过的简历" not in hierarchy_text(root):
        raise RuntimeError("Phone is not on 浏览过的简历.")


def hierarchy_text(root: ElementTree.Element) -> str:
    return "\n".join(
        (node.attrib.get("content-desc") or node.attrib.get("text") or "").strip()
        for node in root.iter("node")
    )


def extract_browsed_cards(root: ElementTree.Element) -> list[dict[str, object]]:
    cards: list[dict[str, object]] = []
    for node in root.iter("node"):
        if node.attrib.get("clickable") != "true":
            continue
        text = (node.attrib.get("content-desc") or node.attrib.get("text") or "").strip()
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        if len(lines) < 3 or "年08月" not in text:
            continue
        bounds = parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        cards.append(
            {
                "candidate_name": lines[0],
                "text": text,
                "bounds": node.attrib.get("bounds"),
                "center": bounds.center,
            }
        )
    return cards


def card_matches_target(card: dict[str, object], target: dict[str, object]) -> bool:
    text = str(card.get("text") or "")
    company = str(target.get("current_company") or "")
    position = str(target.get("current_position") or "")
    return (not company or company in text) and (not position or position in text)


def capture_current_target(
    *,
    device: DeviceClient,
    result_filter: ResultPageFilter,
    settings,
    target: dict[str, object],
    card: dict[str, object],
) -> dict[str, object]:
    device.tap(*card["center"])
    wait_for_detail(device)
    report = CandidateProfileCollector(
        device=device,
        settle_seconds=0.5,
        swipe_duration_seconds=0.2,
        max_stagnant_swipes=3,
    ).collect_current_profile()
    experience = build_candidate_experience_text(
        sections=report.sections,
        structured_resume=report.structured_resume,
    )
    actual_hash = build_experience_hash(experience)
    expected_hash = str(target.get("experience_hash") or "")
    cached = get_analysis_cache_record(
        settings,
        candidate_fingerprint=str(target.get("candidate_fingerprint") or ""),
        experience_hash=expected_hash,
        analysis_version=str(target.get("analysis_version") or ""),
    )
    expected_experience = str((cached or {}).get("experience") or "")
    similarity = calculate_experience_similarity(experience or "", expected_experience)
    basic_info = report.structured_resume.get("basic_info", {})
    detail_name = str(basic_info.get("name") or "") if isinstance(basic_info, dict) else ""
    detail_company = (
        str(basic_info.get("current_company") or "") if isinstance(basic_info, dict) else ""
    )
    detail_position = (
        str(basic_info.get("current_position") or "") if isinstance(basic_info, dict) else ""
    )
    identity_verified = (
        names_compatible(detail_name, str(target.get("candidate_name") or ""))
        and str(target.get("current_company") or "") in detail_company
        and str(target.get("current_position") or "") in detail_position
    )
    hash_verified = actual_hash == expected_hash
    verified = identity_verified and (hash_verified or similarity >= 0.65)
    share_link = None
    locator_id = None
    if verified:
        share_link = result_filter._copy_candidate_share_link()
        if is_official_resume_share_link(share_link):
            screenshot_path = (
                settings.artifacts_dir
                / "candidate_locators"
                / f"{target['candidate_fingerprint']}_share_link.png"
            )
            device.screenshot(screenshot_path)
            locator_id = record_candidate_source_locator(
                settings,
                candidate_fingerprint=str(target["candidate_fingerprint"]),
                experience_hash=expected_hash,
                candidate_name=str(target["candidate_name"]),
                search_keyword=str(target.get("expected_job") or ""),
                source_account_alias=settings.liepin_account_alias,
                source_device_id=settings.device_id,
                job_profile_id=(
                    "daren_live_bd_jd_v1"
                    if "达人" in str(target.get("expected_job") or "")
                    else "finance_shared_management_v1"
                ),
                candidate_card_text=str(card.get("text") or ""),
                detail_deep_link=share_link,
                locator_quality="share_link",
                detail_screenshot_path=str(screenshot_path),
            )
    device.press_back()
    time.sleep(1.2)
    return {
        **target,
        "actual_experience_hash": actual_hash,
        "hash_verified": hash_verified,
        "identity_verified": identity_verified,
        "detail_name": detail_name,
        "detail_company": detail_company,
        "detail_position": detail_position,
        "experience_similarity": similarity,
        "current_experience_length": len(experience or ""),
        "historical_experience_length": len(expected_experience),
        "verified": verified,
        "share_link": share_link,
        "locator_id": locator_id,
    }


def wait_for_detail(device: DeviceClient) -> None:
    for _ in range(20):
        text = device.dump_hierarchy_text()
        if "浏览过的简历" not in text and any(
            marker in text
            for marker in ("求职意向", "展开全部", "工作经历", "立即沟通", "继续沟通")
        ):
            return
        time.sleep(0.5)
    raise RuntimeError("Candidate detail did not finish loading.")


def calculate_experience_similarity(current: str, historical: str) -> float:
    if not current or not historical:
        return 0.0
    current_sample = current[:12000]
    historical_sample = historical[:12000]
    return round(
        max(
            fuzz.ratio(current_sample, historical_sample),
            fuzz.token_set_ratio(current_sample, historical_sample),
            fuzz.partial_ratio(current_sample, historical_sample),
        )
        / 100.0,
        4,
    )


def names_compatible(current: str, historical: str) -> bool:
    current_clean = current.strip()
    historical_clean = historical.strip()
    if not current_clean or not historical_clean:
        return False
    return current_clean == historical_clean or current_clean[0] == historical_clean[0]


def rewind_browsed_list_to_top(device: DeviceClient) -> None:
    previous_head = ""
    stagnant = 0
    for _ in range(80):
        root = ElementTree.fromstring(device.dump_hierarchy_text())
        cards = extract_browsed_cards(root)
        head = str(cards[0]["text"])[:160] if cards else ""
        stagnant = stagnant + 1 if head == previous_head else 0
        if stagnant >= 4:
            return
        previous_head = head
        device.swipe(540, 550, 540, 1950, 0.22)
        time.sleep(0.35)


if __name__ == "__main__":
    raise SystemExit(main())
