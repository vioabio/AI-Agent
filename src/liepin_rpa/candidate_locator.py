from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Mapping
from urllib.parse import unquote
from xml.etree import ElementTree

import xxhash


_CANDIDATE_ID_KEYS = {
    "candidateid",
    "candidateuid",
    "talentid",
    "talentuid",
    "userid",
    "useruid",
}
_RESUME_ID_KEYS = {
    "resumeid",
    "resumeuid",
    "resid",
    "resuid",
    "encryptedresumeid",
}
_PAIR_PATTERN = re.compile(
    r"(?i)(candidate[_-]?id|candidate[_-]?uid|talent[_-]?id|talent[_-]?uid|"
    r"user[_-]?id|user[_-]?uid|resume[_-]?id|resume[_-]?uid|res[_-]?id|"
    r"res[_-]?uid|encrypted[_-]?resume[_-]?id)\s*[=:]\s*[\"']?([A-Za-z0-9._~-]{4,255})"
)
_URI_PATTERN = re.compile(r"(?i)(?:https?://|[a-z][a-z0-9+.-]*://)[^\s\"'<>]+")


@dataclass(frozen=True, slots=True)
class PlatformLocatorEvidence:
    platform_candidate_id: str | None
    platform_resume_id: str | None
    detail_deep_link: str | None
    detail_activity: str | None
    detail_hierarchy_hash: str
    locator_quality: str


def extract_platform_locator_evidence(
    root: ElementTree.Element,
    *,
    current_app: Mapping[str, object] | None = None,
) -> PlatformLocatorEvidence:
    """Extract only identifiers explicitly exposed by the authorized UI surface.

    The function intentionally does not infer platform IDs from ordinary numbers in
    the resume. It accepts explicit attribute names, query-style key/value pairs,
    and visible deep links when the app exposes them.
    """

    candidate_ids: list[str] = []
    resume_ids: list[str] = []
    deep_links: list[str] = []
    serialized_parts: list[str] = []

    for node in root.iter():
        attributes = sorted((str(key), str(value)) for key, value in node.attrib.items())
        serialized_parts.append(
            f"{node.tag}|" + "|".join(f"{key}={value}" for key, value in attributes)
        )
        for key, raw_value in attributes:
            value = unquote(raw_value.strip())
            normalized_key = _normalize_key(key)
            if normalized_key in _CANDIDATE_ID_KEYS:
                _append_identifier(candidate_ids, value)
            elif normalized_key in _RESUME_ID_KEYS:
                _append_identifier(resume_ids, value)
            _extract_key_value_identifiers(value, candidate_ids, resume_ids)
            for uri in _URI_PATTERN.findall(value):
                cleaned = uri.rstrip(".,;)]}")
                if _is_candidate_deep_link(cleaned):
                    _append_unique(deep_links, cleaned)
                    _extract_key_value_identifiers(cleaned, candidate_ids, resume_ids)

    current_app_payload = dict(current_app or {})
    activity = _clean_optional_string(current_app_payload.get("activity"))
    hierarchy_material = "\n".join(serialized_parts)
    hierarchy_hash = xxhash.xxh3_128_hexdigest(hierarchy_material.encode("utf-8"))
    candidate_id = candidate_ids[0] if candidate_ids else None
    resume_id = resume_ids[0] if resume_ids else None
    deep_link = deep_links[0] if deep_links else None
    if candidate_id or resume_id:
        quality = "platform_id"
    elif deep_link:
        quality = "deep_link"
    else:
        quality = "snapshot"
    return PlatformLocatorEvidence(
        platform_candidate_id=candidate_id,
        platform_resume_id=resume_id,
        detail_deep_link=deep_link,
        detail_activity=activity,
        detail_hierarchy_hash=hierarchy_hash,
        locator_quality=quality,
    )


def build_cross_account_lookup_strategy(locator: Mapping[str, object]) -> dict[str, object]:
    """Return a deterministic, permission-respecting lookup plan for an observation."""

    candidate_id = _clean_optional_string(locator.get("platform_candidate_id"))
    resume_id = _clean_optional_string(locator.get("platform_resume_id"))
    deep_link = _clean_optional_string(locator.get("detail_deep_link"))
    if _is_official_resume_share_link(deep_link):
        method = "share_link_then_verify"
        caveat = "优先在目标猎聘账号打开正式分享链接，打开后仍需用简历正文哈希确认。"
    elif candidate_id or resume_id or deep_link:
        method = "platform_locator_then_verify"
        caveat = "平台定位信息可能受账号可见范围限制，打开后仍需用简历正文哈希确认。"
    else:
        method = "search_hints_then_verify"
        caveat = "当前页面未暴露平台ID或深链，需要按搜索词和卡片特征重搜后核对正文哈希。"
    return {
        "method": method,
        "platform_candidate_id": candidate_id,
        "platform_resume_id": resume_id,
        "detail_deep_link": deep_link,
        "source_account_alias": _clean_optional_string(locator.get("source_account_alias")),
        "search_keyword": _clean_optional_string(locator.get("search_keyword")),
        "candidate_card_text": _clean_optional_string(locator.get("candidate_card_text")),
        "experience_hash": _clean_optional_string(locator.get("experience_hash")),
        "caveat": caveat,
    }


def is_official_resume_share_link(value: str | None) -> bool:
    return _is_official_resume_share_link(_clean_optional_string(value))


def _is_official_resume_share_link(value: str | None) -> bool:
    if not value:
        return False
    lowered = value.lower()
    return lowered.startswith("https://mr.liepin.com/resume/view-share-res?") and "ackey=" in lowered


def _extract_key_value_identifiers(
    value: str,
    candidate_ids: list[str],
    resume_ids: list[str],
) -> None:
    for match in _PAIR_PATTERN.finditer(value):
        key = _normalize_key(match.group(1))
        identifier = match.group(2)
        if key in _CANDIDATE_ID_KEYS:
            _append_identifier(candidate_ids, identifier)
        elif key in _RESUME_ID_KEYS:
            _append_identifier(resume_ids, identifier)


def _is_candidate_deep_link(uri: str) -> bool:
    lowered = uri.lower()
    return "liepin" in lowered and any(
        token in lowered for token in ("candidate", "resume", "talent", "resid", "resuid")
    )


def _normalize_key(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def _append_identifier(target: list[str], value: str) -> None:
    cleaned = value.strip().strip("\"'")
    if 4 <= len(cleaned) <= 255 and re.fullmatch(r"[A-Za-z0-9._~-]+", cleaned):
        _append_unique(target, cleaned)


def _append_unique(target: list[str], value: str) -> None:
    if value and value not in target:
        target.append(value)


def _clean_optional_string(value: object) -> str | None:
    if value is None:
        return None
    cleaned = str(value).strip()
    return cleaned or None
