from __future__ import annotations

import re
from dataclasses import dataclass
from difflib import SequenceMatcher


PRIVATE_NAME_PATTERN = re.compile(
    r"^(?P<surname>[\u3400-\u9fff]{1,2})(?:[＊*·•]{1,3}|先生|女士)$"
)
FULL_CHINESE_NAME_PATTERN = re.compile(r"^[\u3400-\u9fff]{2,4}$")
COMPOUND_CHINESE_SURNAMES = frozenset(
    {
        "欧阳",
        "司马",
        "上官",
        "诸葛",
        "夏侯",
        "东方",
        "皇甫",
        "尉迟",
        "公孙",
        "慕容",
    }
)
_AGE_RE = re.compile(
    r"(?:年龄|岁数)[:：]?\s*(\d{2})(?:岁|周岁)?|(?<!\d)(\d{2})(?:岁|周岁)(?!\d)"
)
_SPACE_RE = re.compile(r"\s+")


@dataclass(frozen=True, slots=True)
class HistoricalCandidateEvidence:
    candidate_fingerprint: str
    candidate_name: str | None
    candidate_age: int | None
    job_profile_id: str | None
    candidate_card_text: str | None
    experience: str | None


@dataclass(frozen=True, slots=True)
class CandidateIdentityMatch:
    candidate_fingerprint: str
    resume_similarity: float


def canonicalize_candidate_name(value: object) -> str:
    """统一平台脱敏姓名；不同完整姓名仍保持不同。"""

    profile = _candidate_name_profile(value)
    if profile is None:
        return ""
    surname, is_private, observed_name = profile
    return f"{surname}*" if is_private else observed_name.casefold()


def candidate_names_are_compatible(first: object, second: object) -> bool:
    """允许同姓的脱敏名/称谓/完整名互认，但拒绝两个不同完整姓名。"""

    first_profile = _candidate_name_profile(first)
    second_profile = _candidate_name_profile(second)
    if first_profile is None or second_profile is None:
        return False
    first_surname, first_is_private, first_name = first_profile
    second_surname, second_is_private, second_name = second_profile
    if first_surname != second_surname:
        return False
    return (
        first_is_private
        or second_is_private
        or first_name.casefold() == second_name.casefold()
    )


def extract_candidate_age(card_text: object) -> int | None:
    match = _AGE_RE.search(str(card_text or ""))
    if match is None:
        return None
    age = int(match.group(1) or match.group(2))
    return age if 16 <= age <= 80 else None


def candidate_identity_key(
    *,
    candidate_name: object,
    candidate_card_text: object,
    job_profile_id: object,
    age: int | None,
    source_account_alias: object,
) -> tuple[str, str, int, str] | None:
    """生成保守身份分组键；采集账号不是人员身份的一部分。"""

    profile = _candidate_name_profile(candidate_name)
    card_text = str(candidate_card_text or "").strip()
    job = str(job_profile_id or "").strip()
    _ = source_account_alias
    if profile is None or not card_text or not job or age is None:
        return None
    surname, _, _ = profile
    normalized_card = _normalize_card(card_text, candidate_name)
    if len(normalized_card) < 20:
        return None
    return surname, job, age, normalized_card


def resume_identity_similarity(
    first: object,
    second: object,
    *,
    first_name: object | None = None,
    second_name: object | None = None,
    left_name: object | None = None,
    right_name: object | None = None,
) -> float:
    """计算移除姓名形态差异后的简历正文相似度。"""

    resolved_first_name = first_name if first_name is not None else left_name
    resolved_second_name = second_name if second_name is not None else right_name
    first_text = _normalize_resume(first, resolved_first_name)
    second_text = _normalize_resume(second, resolved_second_name)
    if len(first_text) < 200 or len(second_text) < 200:
        return 0.0
    return SequenceMatcher(None, first_text, second_text, autojunk=False).ratio()


def card_identity_similarity(
    first: object,
    second: object,
    *,
    first_name: object | None = None,
    second_name: object | None = None,
    left_name: object | None = None,
    right_name: object | None = None,
) -> float:
    """计算移除姓名和平台互动提示后的结果卡片相似度。"""

    resolved_first_name = first_name if first_name is not None else left_name
    resolved_second_name = second_name if second_name is not None else right_name
    first_text = _normalize_card(first, resolved_first_name)
    second_text = _normalize_card(second, resolved_second_name)
    if len(first_text) < 12 or len(second_text) < 12:
        return 0.0
    return SequenceMatcher(None, first_text, second_text, autojunk=False).ratio()


def candidate_identity_evidence_matches(
    *,
    first_name: object,
    first_age: int | None,
    first_card_text: object,
    first_experience: object,
    second_name: object,
    second_age: int | None,
    second_card_text: object,
    second_experience: object,
    minimum_card_similarity: float = 0.85,
    minimum_resume_similarity: float = 0.8,
) -> bool:
    """用姓名、年龄、卡片和简历四项证据判断历史身份别名。"""

    if first_age is None or second_age is None or first_age != second_age:
        return False
    if not candidate_names_are_compatible(first_name, second_name):
        return False
    if (
        card_identity_similarity(
            first_card_text,
            second_card_text,
            first_name=first_name,
            second_name=second_name,
        )
        < minimum_card_similarity
    ):
        return False
    return (
        resume_identity_similarity(
            first_experience,
            second_experience,
            first_name=first_name,
            second_name=second_name,
        )
        >= minimum_resume_similarity
    )


def select_historical_candidate_identity(
    *,
    candidate_name: str | None,
    candidate_age: int | None,
    job_profile_id: str | None,
    candidate_card_text: str | None,
    experience: str | None,
    candidates: list[HistoricalCandidateEvidence],
    minimum_card_similarity: float = 0.85,
    minimum_resume_similarity: float = 0.8,
) -> CandidateIdentityMatch | None:
    """仅在同岗位强证据唯一指向一个历史身份时复用 fingerprint。"""

    if (
        not candidate_name
        or candidate_age is None
        or not job_profile_id
        or not candidate_card_text
        or not experience
    ):
        return None
    matches: dict[str, float] = {}
    for candidate in candidates:
        if candidate.job_profile_id != job_profile_id:
            continue
        if not candidate_identity_evidence_matches(
            first_name=candidate_name,
            first_age=candidate_age,
            first_card_text=candidate_card_text,
            first_experience=experience,
            second_name=candidate.candidate_name,
            second_age=candidate.candidate_age,
            second_card_text=candidate.candidate_card_text,
            second_experience=candidate.experience,
            minimum_card_similarity=minimum_card_similarity,
            minimum_resume_similarity=minimum_resume_similarity,
        ):
            continue
        similarity = resume_identity_similarity(
            experience,
            candidate.experience,
            first_name=candidate_name,
            second_name=candidate.candidate_name,
        )
        previous = matches.get(candidate.candidate_fingerprint, 0.0)
        matches[candidate.candidate_fingerprint] = max(previous, similarity)

    if len(matches) != 1:
        return None
    fingerprint, similarity = next(iter(matches.items()))
    return CandidateIdentityMatch(
        candidate_fingerprint=fingerprint,
        resume_similarity=similarity,
    )


def _normalize_card(value: object, candidate_name: object) -> str:
    normalized = _compact(value).casefold()
    profile = _candidate_name_profile(candidate_name)
    if profile is not None:
        surname, _, observed_name = profile
        private_name = f"{surname}*".casefold()
        normalized = normalized.replace(observed_name.casefold(), private_name)
        normalized = re.sub(
            rf"{re.escape(surname.casefold())}(?:[＊*·•]{{1,3}}|先生|女士)",
            private_name,
            normalized,
        )
        normalized = re.sub(
            rf"^{re.escape(surname.casefold())}[\u3400-\u9fff＊*·•]{{0,3}}(?=\d{{2}}(?:岁|周岁))",
            private_name,
            normalized,
            count=1,
        )
    normalized = re.sub(r"你们之前(?:的)?hr找过我\d*", "", normalized, flags=re.IGNORECASE)
    normalized = re.sub(r"hr之前找过我\d*", "", normalized, flags=re.IGNORECASE)
    return normalized


def _normalize_resume(value: object, candidate_name: object) -> str:
    normalized = _compact(value).casefold()
    profile = _candidate_name_profile(candidate_name)
    if profile is not None:
        surname, _, observed_name = profile
        private_name = f"{surname}*".casefold()
        normalized = normalized.replace(observed_name.casefold(), private_name)
        normalized = re.sub(
            rf"{re.escape(surname.casefold())}(?:[＊*·•]{{1,3}}|先生|女士)",
            private_name,
            normalized,
        )
    return normalized


def _candidate_name_profile(value: object) -> tuple[str, bool, str] | None:
    normalized = _compact(value)
    if not normalized:
        return None
    private_match = PRIVATE_NAME_PATTERN.fullmatch(normalized)
    if private_match is not None:
        return private_match.group("surname"), True, normalized
    if FULL_CHINESE_NAME_PATTERN.fullmatch(normalized):
        surname = next(
            (
                item
                for item in COMPOUND_CHINESE_SURNAMES
                if normalized.startswith(item)
            ),
            normalized[0],
        )
        return surname, False, normalized
    folded = normalized.casefold()
    return folded, False, normalized


def _compact(value: object) -> str:
    return _SPACE_RE.sub("", str(value or "").strip())
