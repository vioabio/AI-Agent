from __future__ import annotations

import re
from difflib import SequenceMatcher

PRIVATE_NAME_PATTERN = re.compile(r"^(?P<surname>[\u3400-\u9fff]{1,2})(?:\*{1,3}|先生|女士)$")


def canonicalize_candidate_name(value: object) -> str:
    """统一平台脱敏姓名，避免“毛先生”和“毛**”生成不同身份。"""

    normalized = re.sub(r"\s+", "", str(value or "")).strip()
    match = PRIVATE_NAME_PATTERN.fullmatch(normalized)
    if match is None:
        return normalized.casefold()
    return f"{match.group('surname')}*"


def candidate_identity_key(
    *,
    candidate_name: object,
    candidate_card_text: object,
    job_profile_id: object,
    age: int | None,
    source_account_alias: object,
) -> tuple[str, str, int, str, str] | None:
    """为历史双指纹生成保守分组键；证据不足时拒绝归并。"""

    name = re.sub(r"\s+", "", str(candidate_name or "")).strip()
    canonical_name = canonicalize_candidate_name(name)
    card_text = str(candidate_card_text or "").strip()
    job = str(job_profile_id or "").strip()
    account = str(source_account_alias or "").strip()
    if (
        not PRIVATE_NAME_PATTERN.fullmatch(name)
        or not card_text
        or not job
        or age is None
        or not account
    ):
        return None
    normalized_card = re.sub(r"\s+", "", card_text.replace(name, canonical_name)).casefold()
    if len(normalized_card) < 20:
        return None
    return canonical_name, job, age, account, normalized_card


def resume_identity_similarity(
    first: object,
    second: object,
    *,
    first_name: object,
    second_name: object,
) -> float:
    """只用于已有强卡片证据的候选人，正文相似度是第二道防误合并门槛。"""

    first_text = _normalize_resume(first, first_name)
    second_text = _normalize_resume(second, second_name)
    if len(first_text) < 200 or len(second_text) < 200:
        return 0.0
    return SequenceMatcher(None, first_text, second_text, autojunk=False).ratio()


def _normalize_resume(value: object, candidate_name: object) -> str:
    text = str(value or "")
    name = re.sub(r"\s+", "", str(candidate_name or "")).strip()
    if name:
        text = text.replace(name, canonicalize_candidate_name(name))
    return re.sub(r"\s+", "", text).casefold()
