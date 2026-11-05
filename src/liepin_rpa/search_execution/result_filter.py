from __future__ import annotations

from liepin_rpa.analysis_execution import has_analysis_execution_failure

import json
import random
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree

from PIL import Image
from rapidfuzz import fuzz
import xxhash
from liepin_rpa.analysis_contract import (
    AnalysisContract,
    ResumeAnalysisResult,
    analysis_contract_from_job_profile,
    is_transient_analysis_review,
    transient_analysis_cache_retry_due,
)
from liepin_rpa.analysis_gateway import (
    PROVIDER_COZE,
    PROVIDER_OPENAI_COMPATIBLE,
    analyze_resume_record,
)
from liepin_rpa.batch_trace import BatchTrace, trace_for_seed
from liepin_rpa.candidate_decision_rules import (
    ANALYSIS_STATUS_MATCHED,
    ANALYSIS_STATUS_NOT_RELEVANT,
    allows_automatic_communication,
    analysis_status_for_match_result,
    is_needs_review_match_result,
    is_not_relevant_match_result,
    is_relevant_match_result,
)
from liepin_rpa.candidate_locator import (
    extract_platform_locator_evidence,
    is_official_resume_share_link,
)
from liepin_rpa.config import get_settings
from liepin_rpa.db import (
    build_candidate_fingerprint,
    build_candidate_experience_text,
    build_experience_hash,
    get_candidate_record_by_experience_hash,
    get_candidate_record_by_fingerprint,
    get_candidate_record_by_strong_identity_evidence,
    get_analysis_cache_record,
    list_candidate_source_locators,
    record_candidate_search_hit,
    record_candidate_source_locator,
    update_candidate_source_locator_favorite,
    update_candidate_source_locator_identity,
    update_candidate_source_locator_share_link,
    upsert_analysis_cache_record,
    upsert_contacted_candidate_record,
    was_analyzed_in_prior_batch_within_days,
    was_contacted_within_days,
)
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector
from liepin_rpa.profile_collection import CandidateProfileCollector
from liepin_rpa.profile_collection.collector import (
    ProfileCollectionRecoverableError,
    extract_candidate_interaction_note,
)
from liepin_rpa.profile_collection.resume_schema import (
    UNIFIED_BACHELOR_QUALIFIED,
    UNIFIED_BACHELOR_UNKNOWN,
    evaluate_unified_bachelor_requirement,
    has_explicit_job_intent_city,
    sanitize_candidate_name,
)
from liepin_rpa.reporting_rules import ACTIONABLE_NEEDS_REVIEW_MIN_SCORE
from liepin_rpa.share_quota import DailyShareQuotaStateStore
from liepin_rpa.search_execution.job_selection import choose_job_before_chat_if_present
from liepin_rpa.search_execution.search_pages import SearchFlowError, SearchPageNavigator
from liepin_rpa.task_generation.config_loader import load_job_profile
from liepin_rpa.task_generation.models import SearchTaskSeed
from liepin_rpa.ui_controls import (
    any_label,
    any_label_contains,
    find_back_button,
    find_chat_input_center,
    find_chat_send_button,
    find_clickable_labeled_center,
    find_profile_communication_action,
    node_label,
    parse_bounds,
)

RESULT_SWIPE_START_X = 540
RESULT_SWIPE_START_Y = 1960
RESULT_SWIPE_END_X = 540
RESULT_SWIPE_END_Y = 720
RESULT_SWIPE_DURATION = 0.30
CHAT_SEND_MAX_ATTEMPTS = 2
RECENT_CONTACT_SKIP_DAYS = 7
RECENT_ANALYSIS_SKIP_DAYS = 7
MIN_CANDIDATE_AGE = 32
MAX_CANDIDATE_AGE = 44
ALLOWED_EDUCATION_KEYWORDS = ("本科", "硕士", "博士", "MBA", "EMBA", "统招本科")
REJECTED_EDUCATION_KEYWORDS = ("大专", "专科", "中专", "高中")
INTENTION_COMMUNICATION_LABEL = "意向沟通"
CHAT_MESSAGE_TEMPLATE = (
    "您好，我是上海智美医院管理集团的HR。我们正在急招{role}"
    "，看到您的过往经历非常契合。想简单跟您确认下，您目前还在看机会吗？"
    "如果方便的话，期待您发一份简历过来，我们安排后续沟通。"
)
LOGGER = get_logger(__name__)
SHARE_QUOTA_EXHAUSTED_LABELS = (
    "分享次数已用完",
    "今日分享次数已用完",
    "今日分享次数已达上限",
    "分享次数达到上限",
    "分享次数已达上限",
    "分享额度已用完",
    "明日再来分享",
)


@dataclass(slots=True)
class CandidateCard:
    fingerprint: str
    raw_text: str
    bounds: str
    result_iteration: int | None = None
    result_rank: int | None = None


@dataclass(slots=True)
class CardScreeningResult:
    selected: bool
    score: int
    positive_groups: list[str]
    negative_groups: list[str]
    reasons: list[str]


@dataclass(slots=True)
class FilterIteration:
    iteration: int
    visible_candidates: int
    newly_screened: int
    screened_total: int
    matched_candidate: str | None
    stagnant_swipes: int


@dataclass(slots=True)
class FilterRunReport:
    brand_keyword: str
    role_keyword: str
    screened_candidates: int
    junior_college_rejected: int
    matched: bool
    matched_candidate: str | None
    matched_candidates: list[str]
    profiles_collected: int
    end_reason: str
    iterations: list[FilterIteration]
    candidate_outcomes: list["CandidateProcessOutcome"]


@dataclass(slots=True)
class MessageSendResult:
    success: bool
    attempts: int
    reason: str
    screenshot_path: str | None


@dataclass(slots=True)
class CandidateProcessOutcome:
    candidate_name: str | None
    candidate_fingerprint: str
    search_keyword: str
    brand_keyword: str
    role_keyword: str
    communication_label: str | None
    analysis_status: str
    match_result: str | None
    match_score: int | None
    score_breakdown: dict[str, int]
    analysis_reason: str | None
    evidence_summary: list[str]
    hit_status: str
    continue_without_db_record: bool
    message_send_success: bool
    message_send_reason: str
    message_send_attempts: int
    message_screenshot_path: str | None
    recent_experience_summary: str | None = None
    matched_experience_summary: str | None = None
    candidate_age: int | None = None
    profile_collection_completed: bool = False
    execution_status: str | None = None
    analysis_job_id: str | None = None


class ResultFilterError(SearchFlowError):
    """Raised when result-page filtering cannot continue safely."""


class ResultPageFilter:
    def __init__(
        self,
        device: DeviceClient,
        detector=None,
        settle_seconds: float = 3.0,
        max_stagnant_swipes: int = 3,
        write_to_db: bool = True,
        send_messages: bool = True,
        max_profiles_per_keyword: int = 5,
        ai_first_selection: bool = False,
        target_candidate_fingerprints: set[str] | None = None,
        selection_mode: str | None = None,
        job_profile_path: Path | None = None,
        jd_card_min_score: int | None = None,
        search_batch_id: str | None = None,
        search_batch_job_id: str | None = None,
        analysis_dispatcher=None,
    ) -> None:
        self.device = device
        self.detector = detector or build_page_detector()
        self.settle_seconds = settle_seconds
        self.max_stagnant_swipes = max_stagnant_swipes
        self.write_to_db = write_to_db
        self.send_messages = send_messages
        if analysis_dispatcher is not None and (send_messages or not write_to_db or not job_profile_path or not search_batch_id):
            raise ValueError("Queued collection requires DB writes, an explicit job/batch, and messages disabled")
        self.analysis_dispatcher = analysis_dispatcher
        self.max_profiles_per_keyword = max(1, int(max_profiles_per_keyword))
        resolved_mode = (selection_mode or ("ai_first" if ai_first_selection else "legacy")).strip().lower()
        if resolved_mode not in {"legacy", "ai_first", "jd_smart"}:
            raise ValueError("selection_mode must be legacy, ai_first, or jd_smart")
        if analysis_dispatcher is not None and resolved_mode != "jd_smart":
            raise ValueError("Queued collection requires JD smart rules")
        self.selection_mode = resolved_mode
        self.ai_first_selection = resolved_mode == "ai_first"
        self.target_candidate_fingerprints = {
            item.strip() for item in (target_candidate_fingerprints or set()) if item.strip()
        }
        self.job_profile_path = job_profile_path
        self.job_profile = (
            load_job_profile(job_profile_path)
            if resolved_mode == "jd_smart" and job_profile_path
            else None
        )
        configured_min_score = None
        if isinstance(self.job_profile, dict):
            card_config = self.job_profile.get("card_screening")
            if isinstance(card_config, dict):
                configured_min_score = card_config.get("minimum_score")
        self.jd_card_min_score = int(
            jd_card_min_score
            if jd_card_min_score is not None
            else (configured_min_score if configured_min_score is not None else 3)
        )
        self.search_batch_id = search_batch_id.strip() if search_batch_id else None
        self.search_batch_job_id = (
            search_batch_job_id.strip() if search_batch_job_id else None
        )
        self._current_batch_trace: BatchTrace | None = None
        self.session_card_fingerprints: set[str] = set()
        self.navigator = SearchPageNavigator(
            device=device,
            detector=self.detector,
            settle_seconds=settle_seconds,
        )

    def search_and_filter_seed(
        self,
        seed: SearchTaskSeed,
        output_dir=None,
    ) -> FilterRunReport:
        self._current_batch_trace = self._batch_trace_for_seed(seed)
        self._log(
            "filter_seed_start",
            "Starting result filtering for keyword",
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            search_batch_id=(
                self._current_batch_trace.parent_batch_id
                if self._current_batch_trace is not None
                else None
            ),
            search_batch_job_id=(
                self._current_batch_trace.batch_job_id
                if self._current_batch_trace is not None
                else None
            ),
            search_task_id=(
                self._current_batch_trace.search_task_id
                if self._current_batch_trace is not None
                else None
            ),
        )
        result_state = self.navigator.navigate_to_search_results(seed)
        if result_state == PageState.EMPTY_RESULT:
            self._log(
                "filter_seed_empty_result",
                "Search returned empty result page",
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
            )
            return self._build_report(
                seed=seed,
                screened_candidates=0,
                junior_college_rejected=0,
                matched_candidates=[],
                end_reason="empty_result",
                iterations=[],
                candidate_outcomes=[],
            )
        return self.run_current_result_page(seed=seed, output_dir=output_dir)

    def run_current_result_page(
        self,
        seed: SearchTaskSeed,
        output_dir=None,
    ) -> FilterRunReport:
        ready_state = self.navigator.ensure_result_context(seed)
        if ready_state == PageState.EMPTY_RESULT:
            self._log(
                "filter_result_context_empty",
                "Result context resolved to empty result page",
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
            )
            return self._build_report(
                seed=seed,
                screened_candidates=0,
                junior_college_rejected=0,
                matched_candidates=[],
                end_reason="empty_result",
                iterations=[],
                candidate_outcomes=[],
            )

        screened_fingerprints: set[str] = set()
        rejected_fingerprints: set[str] = set()
        collected_profile_fingerprints: set[str] = set()
        candidate_outcomes: list[CandidateProcessOutcome] = []
        junior_college_rejected = 0
        stagnant_swipes = 0
        iterations: list[FilterIteration] = []
        matched_candidates: list[str] = []
        previous_visible_fingerprints: tuple[str, ...] = ()

        for iteration_index in range(1, 200):
            root = self._capture_result_list_root(seed)
            visible_cards = self.extract_visible_candidate_cards(root)
            for rank, visible_card in enumerate(visible_cards, start=1):
                visible_card.result_iteration = iteration_index
                visible_card.result_rank = rank
            card_screenings: dict[str, CardScreeningResult] = {}
            if self.selection_mode == "jd_smart":
                card_screenings = {
                    card.fingerprint: self.score_jd_candidate_card(card.raw_text, seed)
                    for card in visible_cards
                }
                visible_cards = sorted(
                    visible_cards,
                    key=lambda card: (
                        -card_screenings[card.fingerprint].score,
                        card.result_rank or 0,
                    ),
                )
            visible_fingerprints = tuple(card.fingerprint for card in visible_cards)
            newly_screened = 0
            matched_card: CandidateCard | None = None
            self._log(
                "result_page_iteration",
                "Scanning candidates on result page",
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
                iteration=iteration_index,
                visible_candidates=len(visible_cards),
                screened_total=len(screened_fingerprints),
            )

            for card in visible_cards:
                if card.fingerprint not in screened_fingerprints:
                    screened_fingerprints.add(card.fingerprint)
                    newly_screened += 1
                if card.fingerprint in collected_profile_fingerprints:
                    continue
                if card.fingerprint in self.session_card_fingerprints:
                    continue
                if self.is_rejected_education_candidate(card.raw_text):
                    if card.fingerprint not in rejected_fingerprints:
                        rejected_fingerprints.add(card.fingerprint)
                        junior_college_rejected += 1
                    continue
                card_screening: CardScreeningResult | None = None
                if self.selection_mode == "legacy":
                    if not self.is_exact_candidate_match(card.raw_text, seed):
                        continue
                elif self.selection_mode == "jd_smart":
                    card_screening = card_screenings[card.fingerprint]
                    if not card_screening.selected:
                        continue
                if self.has_known_education(card.raw_text) and not self.is_allowed_education_candidate(
                    card.raw_text
                ):
                    self._log(
                        "result_candidate_education_rejected",
                        "Skipped candidate before profile entry because education is below requirement",
                        brand_keyword=seed.brand_keyword,
                        role_keyword=seed.role_keyword,
                        candidate_card=card.raw_text,
                    )
                    continue
                candidate_age = self.extract_candidate_age_from_card(card.raw_text)
                if (
                    candidate_age is not None
                    and self._should_enforce_age_range()
                    and not self.is_candidate_age_allowed(candidate_age)
                ):
                    self._log(
                        "result_candidate_age_rejected",
                        "Skipped candidate before profile entry because age is out of range",
                        brand_keyword=seed.brand_keyword,
                        role_keyword=seed.role_keyword,
                        candidate_age=candidate_age,
                        candidate_card=card.raw_text,
                    )
                    continue
                self._log(
                    (
                        "result_candidate_selected_for_ai"
                        if self.selection_mode == "ai_first"
                        else (
                            "result_candidate_jd_smart_match"
                            if self.selection_mode == "jd_smart"
                            else "result_candidate_keyword_match"
                        )
                    ),
                    (
                        "Selected candidate for profile collection and AI analysis"
                        if self.ai_first_selection
                        else "Found candidate card matching exact keyword on result page"
                    ),
                    brand_keyword=seed.brand_keyword,
                    role_keyword=seed.role_keyword,
                    selection_mode=self.selection_mode,
                    search_pack_id=seed.search_pack_id,
                    card_score=card_screening.score if card_screening else None,
                    card_score_reasons=card_screening.reasons if card_screening else [],
                    candidate_card=card.raw_text,
                )
                matched_card = card
                break

            iterations.append(
                FilterIteration(
                    iteration=iteration_index,
                    visible_candidates=len(visible_cards),
                    newly_screened=newly_screened,
                    screened_total=len(screened_fingerprints),
                    matched_candidate=matched_card.raw_text if matched_card else None,
                    stagnant_swipes=stagnant_swipes,
                )
            )

            if matched_card is not None:
                self.session_card_fingerprints.add(matched_card.fingerprint)
                candidate_outcome = self._collect_profile_for_candidate(seed=seed, card=matched_card)
                if candidate_outcome.profile_collection_completed:
                    collected_profile_fingerprints.add(matched_card.fingerprint)
                matched_candidates.append(matched_card.raw_text)
                candidate_outcomes.append(candidate_outcome)
                if (
                    self.selection_mode in {"ai_first", "jd_smart"}
                    and len(collected_profile_fingerprints) >= self.max_profiles_per_keyword
                ):
                    self._log(
                        "result_profile_limit_reached",
                        "Stopped result scan after reaching per-keyword profile limit",
                        brand_keyword=seed.brand_keyword,
                        role_keyword=seed.role_keyword,
                        profiles_collected=len(collected_profile_fingerprints),
                        max_profiles_per_keyword=self.max_profiles_per_keyword,
                    )
                    return self._build_report(
                        seed=seed,
                        screened_candidates=len(screened_fingerprints),
                        junior_college_rejected=junior_college_rejected,
                        matched_candidates=matched_candidates,
                        end_reason="profile_limit_reached",
                        iterations=iterations,
                        candidate_outcomes=candidate_outcomes,
                    )
                previous_visible_fingerprints = visible_fingerprints
                stagnant_swipes = 0
                continue

            if iteration_index > 1 and self._is_stagnant(
                previous_visible_fingerprints,
                visible_fingerprints,
                newly_screened,
            ):
                stagnant_swipes += 1
            else:
                stagnant_swipes = 0

            if stagnant_swipes >= self.max_stagnant_swipes:
                self._log(
                    "result_page_exhausted",
                    "Stopping result page scan because swipe became stagnant",
                    brand_keyword=seed.brand_keyword,
                    role_keyword=seed.role_keyword,
                    stagnant_swipes=stagnant_swipes,
                    screened_total=len(screened_fingerprints),
                )
                break

            previous_visible_fingerprints = visible_fingerprints
            self._log(
                "result_page_swipe",
                "Swiping result page for more candidates",
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
                iteration=iteration_index,
            )
            self.device.swipe(
                RESULT_SWIPE_START_X,
                RESULT_SWIPE_START_Y,
                RESULT_SWIPE_END_X,
                RESULT_SWIPE_END_Y,
                duration=RESULT_SWIPE_DURATION,
            )
            self._wait()

        return self._build_report(
            seed=seed,
            screened_candidates=len(screened_fingerprints),
            junior_college_rejected=junior_college_rejected,
            matched_candidates=matched_candidates,
            end_reason="result_list_exhausted",
            iterations=iterations,
            candidate_outcomes=candidate_outcomes,
        )

    @staticmethod
    def extract_visible_candidate_cards(root: ElementTree.Element) -> list[CandidateCard]:
        cards: list[CandidateCard] = []
        for node in root.iter("node"):
            if node.attrib.get("package", "") != "com.lietou.mishu":
                continue
            if node.attrib.get("clickable") != "true":
                continue
            bounds = node.attrib.get("bounds", "")
            box = parse_bounds(bounds)
            if box is None:
                continue
            if box.y1 < 360:
                continue
            if box.width < 850 or box.height < 150:
                continue
            raw_text = " ".join(node.attrib.get("content-desc", "").split())
            if not raw_text:
                continue
            cards.append(
                CandidateCard(
                    fingerprint=ResultPageFilter.build_card_fingerprint(raw_text),
                    raw_text=raw_text,
                    bounds=bounds,
                )
            )
        return cards

    @staticmethod
    def build_card_fingerprint(raw_text: str) -> str:
        normalized = " ".join(raw_text.split())
        first_line = normalized.split(" ")[0] if normalized else ""
        digest = xxhash.xxh3_128_hexdigest(normalized.encode("utf-8"))
        return f"{first_line}|{digest}"

    @staticmethod
    def is_exact_candidate_match(raw_text: str, seed: SearchTaskSeed) -> bool:
        normalized = " ".join(raw_text.split())
        return seed.brand_keyword in normalized and seed.role_keyword in normalized

    def score_jd_candidate_card(
        self,
        raw_text: str,
        seed: SearchTaskSeed,
    ) -> CardScreeningResult:
        normalized = " ".join(raw_text.split())
        folded = normalized.casefold()
        card_config = (
            self.job_profile.get("card_screening", {})
            if isinstance(self.job_profile, dict)
            else {}
        )
        if not isinstance(card_config, dict):
            card_config = {}
        score = 0
        positive_groups: list[str] = []
        negative_groups: list[str] = []
        reasons: list[str] = []
        for raw_group in card_config.get("positive_groups", []):
            if not isinstance(raw_group, dict):
                continue
            terms = self._normalized_terms(raw_group.get("terms"))
            matched_terms = [term for term in terms if term.casefold() in folded]
            if not matched_terms:
                continue
            weight = int(raw_group.get("weight") or 0)
            group_id = str(raw_group.get("id") or "positive")
            score += weight
            positive_groups.append(group_id)
            reasons.append(f"+{weight} {group_id}: {', '.join(matched_terms[:3])}")
        for raw_group in card_config.get("negative_groups", []):
            if not isinstance(raw_group, dict):
                continue
            terms = self._normalized_terms(raw_group.get("terms"))
            matched_terms = [term for term in terms if term.casefold() in folded]
            if not matched_terms:
                continue
            weight = int(raw_group.get("weight") or 0)
            group_id = str(raw_group.get("id") or "negative")
            score += weight
            negative_groups.append(group_id)
            reasons.append(f"{weight:+d} {group_id}: {', '.join(matched_terms[:3])}")
        hard_reject_groups = set(
            self._normalized_terms(card_config.get("hard_reject_groups"))
        )
        rejected_group = hard_reject_groups.intersection(negative_groups)
        if rejected_group:
            reasons.append(
                "hard_reject_group: " + ", ".join(sorted(rejected_group))
            )
            return CardScreeningResult(
                False,
                score,
                positive_groups,
                negative_groups,
                reasons,
            )
        if seed.brand_keyword and seed.brand_keyword.casefold() in folded:
            brand_bonus = int(card_config.get("target_brand_bonus") or 0)
            score += brand_bonus
            if brand_bonus:
                reasons.append(f"+{brand_bonus} target_brand: {seed.brand_keyword}")
        broad_titles = self._normalized_terms(card_config.get("broad_title_terms"))
        required_directions = self._normalized_terms(card_config.get("required_direction_terms"))
        has_broad_title = any(term.casefold() in folded for term in broad_titles)
        has_direction = any(term.casefold() in folded for term in required_directions)
        if has_broad_title and not has_direction:
            reasons.append("broad_title_without_daren_direction")
            return CardScreeningResult(False, score, positive_groups, negative_groups, reasons)
        required_primary_groups = set(
            self._normalized_terms(card_config.get("required_any_positive_groups"))
        )
        if required_primary_groups and not required_primary_groups.intersection(positive_groups):
            reasons.append("missing_required_daren_or_live_direction")
            return CardScreeningResult(False, score, positive_groups, negative_groups, reasons)
        required_support_groups = set(
            self._normalized_terms(card_config.get("required_any_support_groups"))
        )
        if required_support_groups and not required_support_groups.intersection(positive_groups):
            reasons.append("missing_required_leadership_or_target_industry")
            return CardScreeningResult(False, score, positive_groups, negative_groups, reasons)
        selection_paths = card_config.get("selection_paths")
        if isinstance(selection_paths, list) and selection_paths:
            matched_path = self._match_card_selection_path(
                selection_paths=selection_paths,
                positive_groups=set(positive_groups),
            )
            if matched_path is None:
                reasons.append("missing_required_finance_combination")
                return CardScreeningResult(False, score, positive_groups, negative_groups, reasons)
            reasons.append(f"selection_path: {matched_path}")
        selected = score >= self.jd_card_min_score and bool(positive_groups)
        return CardScreeningResult(selected, score, positive_groups, negative_groups, reasons)

    @staticmethod
    def _match_card_selection_path(
        *,
        selection_paths: list[object],
        positive_groups: set[str],
    ) -> str | None:
        for raw_path in selection_paths:
            if not isinstance(raw_path, dict):
                continue
            path_id = str(raw_path.get("id") or "selection_path")
            all_of_groups = set(ResultPageFilter._normalized_terms(raw_path.get("all_of_groups")))
            any_of_groups = set(ResultPageFilter._normalized_terms(raw_path.get("any_of_groups")))
            if not all_of_groups.issubset(positive_groups):
                continue
            if any_of_groups and not any_of_groups.intersection(positive_groups):
                continue
            return path_id
        return None

    @staticmethod
    def _normalized_terms(value: object) -> list[str]:
        if not isinstance(value, list):
            return []
        return [str(item).strip() for item in value if str(item).strip()]

    def _should_enforce_age_range(self) -> bool:
        if self.selection_mode != "jd_smart":
            return True
        card_config = (
            self.job_profile.get("card_screening", {})
            if isinstance(self.job_profile, dict)
            else {}
        )
        return bool(card_config.get("enforce_age_range", False)) if isinstance(card_config, dict) else False

    @staticmethod
    def extract_candidate_age_from_card(raw_text: str) -> int | None:
        normalized = " ".join(raw_text.split())
        match = re.search(r"(?<!\d)([1-9]\d?)\s*" + chr(0x5C81), normalized)
        if not match:
            return None
        age = int(match.group(1))
        if 16 <= age <= 80:
            return age
        return None

    def is_candidate_age_allowed(self, age: int) -> bool:
        minimum_age = MIN_CANDIDATE_AGE
        maximum_age = MAX_CANDIDATE_AGE
        if isinstance(self.job_profile, dict):
            card_config = self.job_profile.get("card_screening", {})
            if isinstance(card_config, dict):
                minimum_age = int(card_config.get("minimum_age") or minimum_age)
                maximum_age = int(card_config.get("maximum_age") or maximum_age)
        return minimum_age <= age <= maximum_age

    @staticmethod
    def is_junior_college_candidate(raw_text: str) -> bool:
        return ResultPageFilter.is_rejected_education_candidate(raw_text)

    @staticmethod
    def is_rejected_education_candidate(raw_text: str) -> bool:
        normalized = " ".join(raw_text.split())
        return any(keyword in normalized for keyword in REJECTED_EDUCATION_KEYWORDS)

    @staticmethod
    def is_allowed_education_candidate(raw_text: str) -> bool:
        normalized = " ".join(raw_text.split())
        return any(keyword in normalized for keyword in ALLOWED_EDUCATION_KEYWORDS)

    @staticmethod
    def has_known_education(raw_text: str) -> bool:
        normalized = " ".join(raw_text.split())
        return any(
            keyword in normalized
            for keyword in (*ALLOWED_EDUCATION_KEYWORDS, *REJECTED_EDUCATION_KEYWORDS)
        )

    @staticmethod
    def _is_stagnant(
        previous_visible_fingerprints: Iterable[str],
        current_visible_fingerprints: Iterable[str],
        newly_screened: int,
    ) -> bool:
        previous_set = set(previous_visible_fingerprints)
        current_set = set(current_visible_fingerprints)
        if not previous_set and not current_set:
            return True
        if newly_screened > 0:
            return False
        overlap = previous_set & current_set
        return current_set == previous_set or len(overlap) >= max(1, len(current_set) - 1)

    def _capture_result_list_root(self, seed: SearchTaskSeed) -> ElementTree.Element:
        for _ in range(5):
            hierarchy_text = self.device.dump_hierarchy_text()
            detection = self.detector.detect_from_xml_text(hierarchy_text)
            if detection.state == PageState.RESULT_LIST:
                return ElementTree.fromstring(hierarchy_text)
            if detection.state == PageState.CANDIDATE_PROFILE:
                self._log(
                    "result_page_recover_from_profile",
                    "Recovered back from candidate profile to result list",
                    page_state=detection.state.value,
                )
                self.device.press_back()
                self._wait(1.5)
                continue
            if detection.state in (
                PageState.SEARCH_CONDITION,
                PageState.SEARCH_ENTRY,
                PageState.HOME_RECOMMEND,
                PageState.EMPTY_RESULT,
            ):
                self._log(
                    "result_page_recover_result_context",
                    "Result page drifted away during filtering, attempting to recover result context",
                    page_state=detection.state.value,
                    brand_keyword=seed.brand_keyword,
                    role_keyword=seed.role_keyword,
                )
                try:
                    recovered_state = self.navigator.ensure_result_context(seed)
                except SearchFlowError as exc:
                    self._capture_debug_screenshot(
                        status="result_context_recovery_failed",
                        seed=seed,
                    )
                    raise ResultFilterError(str(exc)) from exc
                if recovered_state == PageState.EMPTY_RESULT:
                    self._capture_debug_screenshot(
                        status="result_context_empty_during_filter",
                        seed=seed,
                    )
                    raise ResultFilterError(
                        "Result page switched to empty result while filtering current keyword"
                    )
                self._wait(1.5)
                continue
            self._capture_debug_screenshot(
                status=f"unexpected_page_{detection.state.value}",
                seed=seed,
            )
            raise ResultFilterError(
                f"Expected current page to stay on result list, got: {detection.state.value}"
            )
        self._capture_debug_screenshot(status="result_context_recovery_exhausted", seed=seed)
        raise ResultFilterError("Unable to recover result list during filtering")

    def _collect_profile_for_candidate(
        self,
        *,
        seed: SearchTaskSeed,
        card: CandidateCard,
    ) -> CandidateProcessOutcome:
        bounds = parse_bounds(card.bounds)
        if bounds is None:
            raise ResultFilterError(f"Candidate card bounds are invalid: {card.bounds}")

        self._log(
            "candidate_detail_open",
            "Opening candidate detail page",
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            candidate_card=card.raw_text,
        )
        self.device.tap(*bounds.center)
        self._wait(3.5)

        detail_root = ElementTree.fromstring(self.device.dump_hierarchy_text())
        try:
            current_app = self.device.current_app()
        except Exception:
            current_app = {}
        platform_locator = extract_platform_locator_evidence(
            detail_root,
            current_app=current_app,
        )
        platform_interaction_note = extract_candidate_interaction_note(detail_root)
        locator_record_id = self._record_entered_candidate_detail(
            seed=seed,
            card=card,
            platform_locator=platform_locator,
            platform_interaction_note=platform_interaction_note,
        )
        if self._is_intention_communication_page(detail_root):
            return self._handle_intention_communication_candidate(
                seed=seed,
                card=card,
                detail_root=detail_root,
            )
        communication_action = find_profile_communication_action(detail_root)
        communication_label = communication_action[0] if communication_action is not None else None
        if communication_label is None:
            self._log(
                "candidate_profile_without_communication_action",
                "Candidate profile has no supported communication action; collection and AI continue",
                candidate_name=self._extract_candidate_name_from_card(card.raw_text),
                search_keyword=seed.effective_query,
            )

        collector = CandidateProfileCollector(device=self.device)
        self._log(
            "candidate_profile_collect_start",
            "Starting candidate profile collection",
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
        )
        try:
            report = collector.collect_current_profile()
        except ProfileCollectionRecoverableError as exc:
            return self._handle_profile_collection_recoverable_error(
                seed=seed,
                card=card,
                reason=str(exc),
            )
        except RuntimeError as exc:
            fallback_root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            if self._is_intention_communication_page(fallback_root):
                return self._handle_intention_communication_candidate(
                    seed=seed,
                    card=card,
                    detail_root=fallback_root,
                )
            raise
        structured_resume = report.structured_resume
        experience = build_candidate_experience_text(
            sections=report.sections,
            structured_resume=structured_resume,
        )

        settings = get_settings()
        basic_info = structured_resume.get("basic_info", {})
        candidate_name = sanitize_candidate_name(basic_info.get("name") if isinstance(basic_info, dict) else None)
        candidate_age = self._normalize_optional_int(
            basic_info.get("age") if isinstance(basic_info, dict) else None
        )
        candidate_education = self._normalize_optional_str(
            basic_info.get("education") if isinstance(basic_info, dict) else None
        )
        if candidate_name is None:
            candidate_name = self._extract_candidate_name_from_card(card.raw_text)
        search_keyword = seed.effective_query
        calculated_fingerprint = build_candidate_fingerprint(
            candidate_name=candidate_name,
            structured_resume=structured_resume,
            experience=experience,
        )
        experience_hash = build_experience_hash(experience)
        candidate_fingerprint = calculated_fingerprint
        existing_record = None
        if self.write_to_db:
            existing_by_fingerprint = get_candidate_record_by_fingerprint(
                settings,
                calculated_fingerprint,
            )
            existing_by_experience = (
                get_candidate_record_by_experience_hash(settings, experience_hash)
                if (experience or "").strip()
                else None
            )
            # The profile may expose a masked name on one run and a full name on
            # another. Identical normalized resume text must reuse the existing
            # contacted identity so the 7-day skip and history stay effective.
            existing_record = existing_by_fingerprint or existing_by_experience
            if existing_by_experience is not None:
                canonical_fingerprint = str(
                    existing_by_experience.get("candidate_fingerprint") or calculated_fingerprint
                )
                if canonical_fingerprint != calculated_fingerprint:
                    self._log(
                        "candidate_identity_reused_by_experience",
                        "Reused contacted candidate identity from identical resume text",
                        candidate_name=candidate_name,
                        calculated_fingerprint=calculated_fingerprint,
                        canonical_fingerprint=canonical_fingerprint,
                    )
                candidate_fingerprint = canonical_fingerprint
                existing_record = existing_by_experience
            job_profile_id = (
                str(self.job_profile.get("id") or "").strip()
                if isinstance(self.job_profile, dict)
                else ""
            )
            if existing_by_experience is None and job_profile_id:
                existing_by_evidence = get_candidate_record_by_strong_identity_evidence(
                    settings,
                    candidate_name=candidate_name,
                    candidate_age=candidate_age,
                    job_profile_id=job_profile_id,
                    candidate_card_text=card.raw_text,
                    experience=experience,
                    exclude_fingerprint=calculated_fingerprint,
                )
                if existing_by_evidence is not None:
                    canonical_fingerprint = str(
                        existing_by_evidence.get("candidate_fingerprint")
                        or calculated_fingerprint
                    )
                    candidate_fingerprint = canonical_fingerprint
                    existing_record = existing_by_evidence
                    # 人工处置可能写在后来生成的别名 fingerprint 上。身份回归历史
                    # fingerprint 时仍必须继承禁止沟通/报告事实，不能因归一化丢失。
                    if existing_by_fingerprint is not None:
                        for field_name in (
                            "manual_review_status",
                            "manual_review_feedback",
                            "manual_suppress_communication",
                            "manual_suppress_report",
                            "manual_reviewed_at",
                        ):
                            field_value = existing_by_fingerprint.get(field_name)
                            if field_value not in (None, "", 0, False):
                                existing_record[field_name] = field_value
                    self._log(
                        "candidate_identity_reused_by_strong_evidence",
                        "Reused historical candidate identity from unique strong evidence",
                        candidate_name=candidate_name,
                        calculated_fingerprint=calculated_fingerprint,
                        canonical_fingerprint=canonical_fingerprint,
                        resume_similarity=existing_by_evidence.get(
                            "identity_resume_similarity"
                        ),
                    )
            if locator_record_id is not None:
                update_candidate_source_locator_identity(
                    settings,
                    locator_id=locator_record_id,
                    candidate_fingerprint=candidate_fingerprint,
                    experience_hash=experience_hash,
                    candidate_name=candidate_name,
                )
        if existing_record is not None and bool(
            existing_record.get("manual_suppress_communication")
        ):
            return self._handle_manual_review_suppressed_candidate(
                seed=seed,
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                communication_label=communication_label,
                candidate_age=candidate_age,
                manual_review_status=self._normalize_optional_str(
                    existing_record.get("manual_review_status")
                ),
                manual_review_feedback=self._normalize_optional_str(
                    existing_record.get("manual_review_feedback")
                ),
                profile_collection_completed=True,
            )
        required_maximum_age = self._required_candidate_maximum_age()
        if (
            required_maximum_age is not None
            and candidate_age is not None
            and candidate_age > required_maximum_age
        ):
            return self._handle_candidate_age_rejected(
                seed=seed,
                card=card,
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                communication_label=communication_label,
                candidate_age=candidate_age,
                maximum_age=required_maximum_age,
            )
        required_job_intent_city = self._required_job_intent_city()
        if required_job_intent_city and not has_explicit_job_intent_city(
            report.sections,
            required_job_intent_city,
        ):
            return self._handle_job_intent_city_rejected_candidate(
                seed=seed,
                card=card,
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                experience_hash=experience_hash,
                experience=experience,
                communication_label=communication_label,
                platform_locator=platform_locator,
                required_city=required_job_intent_city,
                intent_lines=report.sections.get("求职意向", []),
            )
        unified_bachelor_status = evaluate_unified_bachelor_requirement(
            structured_resume.get("education_experiences")
        )
        if (
            self._requires_unified_bachelor()
            and unified_bachelor_status != UNIFIED_BACHELOR_QUALIFIED
        ):
            return self._handle_unified_bachelor_requirement_failed(
                seed=seed,
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                communication_label=communication_label,
                candidate_age=candidate_age,
                requirement_status=unified_bachelor_status,
                education_experiences=structured_resume.get("education_experiences"),
            )
        if (
            self.write_to_db
            and self.search_batch_id
            and was_analyzed_in_prior_batch_within_days(
                settings,
                candidate_fingerprint=candidate_fingerprint,
                days=RECENT_ANALYSIS_SKIP_DAYS,
                current_search_batch_id=self.search_batch_id,
                candidate_name=candidate_name,
                candidate_age=candidate_age,
                candidate_card_text=card.raw_text,
                experience=experience,
            )
        ):
            analysis_status = "7天内已分析跳过"
            hit_status = "skipped_recent_analysis"
            self._record_search_hit_fact(
                settings=settings,
                candidate_fingerprint=candidate_fingerprint,
                candidate_name=candidate_name,
                search_keyword=search_keyword,
                hit_status=hit_status,
            )
            self._log(
                "candidate_recent_analysis_cooldown",
                "Skipped AI analysis because the candidate was analyzed in a prior batch within 7 days",
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                search_batch_id=self.search_batch_id,
                cooldown_days=RECENT_ANALYSIS_SKIP_DAYS,
            )
            self.device.press_back()
            self._wait(2.0)
            return CandidateProcessOutcome(
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                search_keyword=search_keyword,
                brand_keyword=seed.brand_keyword,
                role_keyword=seed.role_keyword,
                communication_label=communication_label,
                analysis_status=analysis_status,
                match_result=None,
                match_score=None,
                score_breakdown={},
                analysis_reason="同一候选人在此前7天内已有成功AI分析，本批不重复分析。",
                evidence_summary=[],
                hit_status=hit_status,
                continue_without_db_record=False,
                message_send_success=False,
                message_send_reason=hit_status,
                message_send_attempts=0,
                message_screenshot_path=None,
                candidate_age=candidate_age,
                profile_collection_completed=True,
            )
        if self.analysis_dispatcher is not None:
            decision = self.analysis_dispatcher.submit(batch_id=self.search_batch_id,
                candidate_fingerprint=candidate_fingerprint, candidate_name=candidate_name or "未命名候选人",
                job_profile_id=str(self.job_profile.get("id") or ""),
                resume_record={**structured_resume, "experience": experience, "sections": report.sections})
            self.device.press_back()
            self._wait(2.0)
            return CandidateProcessOutcome(candidate_name=candidate_name, candidate_fingerprint=candidate_fingerprint,
                search_keyword=search_keyword, brand_keyword=seed.brand_keyword, role_keyword=seed.role_keyword,
                communication_label=communication_label, analysis_status="等待分析" if decision.state == "queued" else "待人工核验",
                match_result=None, match_score=None, score_breakdown={}, analysis_reason=decision.reason_code,
                evidence_summary=[], hit_status="analysis_queued" if decision.state == "queued" else "analysis_intake_review",
                continue_without_db_record=False, message_send_success=False, message_send_reason="queued_analysis_no_communication",
                message_send_attempts=0, message_screenshot_path=None, candidate_age=candidate_age,
                profile_collection_completed=True, analysis_job_id=decision.job_id)
        analysis_settings = self._analysis_settings(settings)
        analysis_version = analysis_settings.coze_analysis_version
        analysis_cache_record = (
            get_analysis_cache_record(
                settings,
                candidate_fingerprint=candidate_fingerprint,
                experience_hash=experience_hash,
                analysis_version=analysis_version,
            )
            if self.write_to_db
            else None
        )
        continue_without_db_record = communication_label == "继续沟通" and existing_record is None
        self._log(
            "candidate_profile_collect_complete",
            "Collected candidate profile data",
            candidate_name=candidate_name,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            experience_length=len(experience or ""),
            communication_label=communication_label,
            candidate_fingerprint=candidate_fingerprint,
            continue_without_db_record=continue_without_db_record,
        )
        if continue_without_db_record:
            self._log(
                "continue_without_db_record",
                "Page shows continue communication but database has no prior contacted record",
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                search_keyword=search_keyword,
            )
        analysis_status = "未分析"
        analysis_result = self._analysis_result_from_cache_record(analysis_cache_record)
        if transient_analysis_cache_retry_due(analysis_result, analysis_cache_record):
            self._log(
                "candidate_transient_analysis_cache_expired",
                "Cached transient model API failure expired and will be analyzed again",
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                analysis_version=analysis_version,
            )
            analysis_result = None
        hit_status = "reviewed"
        message_send_result = MessageSendResult(
            success=False,
            attempts=0,
            reason="not_attempted",
            screenshot_path=None,
        )

        if (
            self.write_to_db
            and communication_label == "继续沟通"
            and existing_record is not None
            and was_contacted_within_days(
                settings,
                candidate_fingerprint=candidate_fingerprint,
                days=RECENT_CONTACT_SKIP_DAYS,
            )
        ):
            analysis_status = str(existing_record.get("analysis_status") or "已沟通跳过")
            message_send_result = MessageSendResult(
                success=False,
                attempts=0,
                reason="skipped_recent_continue",
                screenshot_path=None,
            )
            hit_status = "skipped_recent_continue"
            if self.write_to_db:
                self._record_search_hit_fact(
                    settings=settings,
                    candidate_fingerprint=candidate_fingerprint,
                    candidate_name=candidate_name,
                    search_keyword=search_keyword,
                    hit_status=hit_status,
                )
            self._log_candidate_processing(
                candidate_name=candidate_name,
                search_keyword=search_keyword,
                analysis_status=analysis_status,
                message_send_result=message_send_result,
            )
        else:
            if analysis_result is not None:
                cached_analysis_status = self._normalize_optional_str(
                    analysis_cache_record.get("analysis_status")
                )
                analysis_status = analysis_status_for_match_result(
                    analysis_result.match_result
                )
                # Communication is authorized only by the normalized tri-state result.
                # Do not let a stale/contradictory boolean field turn needs_review or
                # not_relevant into an outbound message.
                automatic_communication_allowed = allows_automatic_communication(
                    analysis_result.match_result
                ) and not has_analysis_execution_failure(
                    analysis_result.analysis_reason, execution_status=analysis_result.execution_status
                )
                if (
                    self.write_to_db
                    and cached_analysis_status != analysis_status
                    # Updating the cache also refreshes updated_at. Never postpone
                    # the ten-minute retry window for a transient model API failure.
                    and not is_transient_analysis_review(analysis_result)
                ):
                    upsert_analysis_cache_record(
                        settings,
                        candidate_fingerprint=candidate_fingerprint,
                        experience_hash=experience_hash,
                        experience=experience,
                        analysis_version=analysis_version,
                        analysis_status=analysis_status,
                        match_result=analysis_result.match_result,
                        match_score=analysis_result.match_score,
                        score_breakdown=analysis_result.score_breakdown,
                        analysis_reason=analysis_result.analysis_reason,
                        evidence_summary=analysis_result.evidence_summary,
                        execution_status=getattr(analysis_result, "execution_status", None),
                        error_code=getattr(analysis_result, "error_code", None),
                        retryable=getattr(analysis_result, "retryable", None),
                        retry_after=getattr(analysis_result, "retry_after", None),
                        analysis_run_id=getattr(analysis_result, "analysis_run_id", None),
                        analyzed_text_hash=getattr(analysis_result, "analyzed_text_hash", None),
                        job_profile_id=str(self.job_profile.get("id") or "") if self.job_profile else None,
                    )
                    self._log(
                        "candidate_analysis_cache_status_repaired",
                        "Repaired cached analysis status from normalized match result",
                        candidate_name=candidate_name,
                        candidate_fingerprint=candidate_fingerprint,
                        previous_analysis_status=cached_analysis_status,
                        analysis_status=analysis_status,
                        match_result=analysis_result.match_result,
                    )
                self._log(
                    "candidate_analysis_cache_hit",
                    "Reused cached analysis result",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    analysis_status=analysis_status,
                    match_result=analysis_result.match_result,
                    match_score=analysis_result.match_score,
                )
            else:
                resume_record = self._build_analysis_resume_record(
                    candidate_name=candidate_name,
                    search_keyword=search_keyword,
                    experience=experience,
                    age=candidate_age,
                    education=candidate_education,
                )
                self._log(
                    "candidate_ai_analysis_start",
                    "Submitting candidate record for AI analysis",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                )
                analysis_response = analyze_resume_record(
                    analysis_settings,
                    resume_record=resume_record,
                    prompt_path=self._analysis_prompt_path(),
                    analysis_contract=self._analysis_contract(),
                )
                analysis_result = analysis_response
                self._log(
                    "candidate_ai_analysis_complete",
                    "Received AI analysis result",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    matched=analysis_response.matched,
                    match_result=analysis_response.match_result,
                )
                automatic_communication_allowed = allows_automatic_communication(
                    analysis_response.match_result
                ) and not has_analysis_execution_failure(
                    analysis_response.analysis_reason, execution_status=analysis_response.execution_status
                )
                analysis_status = analysis_status_for_match_result(
                    analysis_response.match_result
                )
                if self.write_to_db:
                    upsert_analysis_cache_record(
                        settings,
                        candidate_fingerprint=candidate_fingerprint,
                        experience_hash=experience_hash,
                        experience=experience,
                        analysis_version=analysis_version,
                        analysis_status=analysis_status,
                        match_result=analysis_response.match_result,
                        match_score=analysis_response.match_score,
                        score_breakdown=analysis_response.score_breakdown,
                        analysis_reason=analysis_response.analysis_reason,
                        evidence_summary=analysis_response.evidence_summary,
                        execution_status=getattr(analysis_response, "execution_status", None),
                        error_code=getattr(analysis_response, "error_code", None),
                        retryable=getattr(analysis_response, "retryable", None),
                        retry_after=getattr(analysis_response, "retry_after", None),
                        analysis_run_id=getattr(analysis_response, "analysis_run_id", None),
                        analyzed_text_hash=getattr(analysis_response, "analyzed_text_hash", None),
                        job_profile_id=str(self.job_profile.get("id") or "") if self.job_profile else None,
                    )

            self._preserve_high_value_candidate(
                settings=settings,
                locator_record_id=locator_record_id,
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                experience_hash=experience_hash,
                analysis_result=analysis_result,
            )

            # The formal candidate snapshot is required by HR review and access
            # auditing, not only by successful outbound communication.  Persist
            # every successful scored analysis while avoiding timestamp churn
            # when the same cached resume/result is encountered again.
            should_refresh_candidate_snapshot = bool(
                self.write_to_db
                and analysis_result is not None
                and analysis_result.match_result
                in {"relevant", "needs_review", "not_relevant"}
                and analysis_result.match_score is not None
                and not has_analysis_execution_failure(
                    analysis_result.analysis_reason, execution_status=analysis_result.execution_status
                )
                and (
                    existing_record is None
                    or str(existing_record.get("experience_hash") or "")
                    != experience_hash
                    or existing_record.get("match_score") != analysis_result.match_score
                    or str(existing_record.get("analysis_status") or "")
                    != analysis_status
                )
            )
            if should_refresh_candidate_snapshot:
                self._log(
                    "candidate_db_snapshot_write_start",
                    "Writing successful analyzed candidate snapshot into database",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    match_result=analysis_result.match_result,
                    match_score=analysis_result.match_score,
                )
                upsert_contacted_candidate_record(
                    settings,
                    candidate_fingerprint=candidate_fingerprint,
                    candidate_name=candidate_name,
                    search_keyword=search_keyword,
                    experience=experience,
                    experience_hash=experience_hash,
                    match_score=analysis_result.match_score,
                    analysis_status=analysis_status,
                )

            target_allowed = (
                not self.target_candidate_fingerprints
                or candidate_fingerprint in self.target_candidate_fingerprints
            )
            global_contact_cooldown = (
                automatic_communication_allowed
                and self.write_to_db
                and was_contacted_within_days(
                    settings,
                    candidate_fingerprint=candidate_fingerprint,
                    days=RECENT_CONTACT_SKIP_DAYS,
                )
            )
            if (
                automatic_communication_allowed
                and communication_label is not None
                and target_allowed
            ):
                if global_contact_cooldown:
                    self._log(
                        "candidate_global_contact_cooldown",
                        "Skipped automatic message because the candidate has a recent sent event",
                        candidate_name=candidate_name,
                        candidate_fingerprint=candidate_fingerprint,
                    )
                    message_send_result = MessageSendResult(
                        success=False,
                        attempts=0,
                        reason="skipped_global_cooldown",
                        screenshot_path=None,
                    )
                elif self.send_messages:
                    self._log(
                        "candidate_message_flow_start",
                        "Candidate matched, starting communication flow",
                        candidate_name=candidate_name,
                        role_keyword=self._extract_role_keyword(seed),
                        communication_label=communication_label,
                    )
                    message_send_result = self._tap_continue_communication(
                        candidate_name=candidate_name,
                        role_keyword=self._extract_role_keyword(seed),
                        button_label=communication_label,
                    )
                else:
                    self._log(
                        "candidate_message_flow_skipped",
                        "Candidate matched but message sending is disabled for this run",
                        candidate_name=candidate_name,
                        role_keyword=self._extract_role_keyword(seed),
                        communication_label=communication_label,
                    )
                    message_send_result = MessageSendResult(
                        success=False,
                        attempts=0,
                        reason="message_sending_disabled",
                        screenshot_path=None,
                    )
            elif (
                automatic_communication_allowed
                and communication_label is not None
                and not target_allowed
            ):
                self._log(
                    "candidate_message_target_guard_skipped",
                    "Skipped matched candidate because it is outside the explicit target allowlist",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    role_keyword=self._extract_role_keyword(seed),
                )
                message_send_result = MessageSendResult(
                    success=False,
                    attempts=0,
                    reason="target_candidate_not_allowed",
                    screenshot_path=None,
                )

            hit_status = self._derive_hit_status(
                analysis_status=analysis_status,
                match_result=(
                    analysis_result.match_result
                    if analysis_result is not None
                    else None
                ),
                message_send_result=message_send_result,
                continue_without_db_record=continue_without_db_record,
            )
            if self.write_to_db:
                self._record_search_hit_fact(
                    settings=settings,
                    candidate_fingerprint=candidate_fingerprint,
                    candidate_name=candidate_name,
                    search_keyword=search_keyword,
                    hit_status=hit_status,
                )

            if self.write_to_db and message_send_result.success:
                self._warn_if_fingerprint_collision(
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    existing_record=existing_record,
                    current_experience=experience,
                )

            self._log_candidate_processing(
                candidate_name=candidate_name,
                search_keyword=search_keyword,
                analysis_status=analysis_status,
                message_send_result=message_send_result,
            )

        self._log(
            "candidate_detail_return_to_results",
            "Returning from candidate detail page to result list",
            candidate_name=candidate_name,
            analysis_status=analysis_status,
        )
        self.device.press_back()
        self._wait(2.0)
        return CandidateProcessOutcome(
            candidate_name=candidate_name if isinstance(candidate_name, str) else None,
            candidate_fingerprint=candidate_fingerprint,
            search_keyword=search_keyword,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            communication_label=communication_label,
            analysis_status=analysis_status,
            match_result=analysis_result.match_result if analysis_result is not None else None,
            match_score=analysis_result.match_score if analysis_result is not None else None,
            score_breakdown=analysis_result.score_breakdown if analysis_result is not None else {},
            analysis_reason=analysis_result.analysis_reason if analysis_result is not None else None,
            execution_status=analysis_result.execution_status if analysis_result is not None else None,
            evidence_summary=analysis_result.evidence_summary if analysis_result is not None else [],
            hit_status=hit_status,
            continue_without_db_record=continue_without_db_record,
            message_send_success=message_send_result.success,
            message_send_reason=message_send_result.reason,
            message_send_attempts=message_send_result.attempts,
            message_screenshot_path=message_send_result.screenshot_path,
            candidate_age=candidate_age,
            profile_collection_completed=True,
        )

    def _handle_intention_communication_candidate(
        self,
        *,
        seed: SearchTaskSeed,
        card: CandidateCard,
        detail_root: ElementTree.Element,
    ) -> CandidateProcessOutcome:
        settings = get_settings()
        candidate_name = self._extract_candidate_name_from_card(card.raw_text)
        search_keyword = seed.effective_query
        analysis_status = "意向沟通跳过"
        hit_status = "intention_communication_skipped"
        screenshot_path = self._capture_debug_screenshot(
            status="intention_communication_skipped",
            seed=seed,
            candidate_name=candidate_name,
        )
        self._log(
            "candidate_skipped_intention_communication",
            "Skipped candidate because only intention communication is available",
            candidate_name=candidate_name,
            search_keyword=search_keyword,
            candidate_card_fingerprint=card.fingerprint,
        )
        self.device.press_back()
        self._wait(2.0)
        return CandidateProcessOutcome(
            candidate_name=candidate_name,
            candidate_fingerprint=card.fingerprint,
            search_keyword=search_keyword,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            communication_label=INTENTION_COMMUNICATION_LABEL,
            analysis_status=analysis_status,
            match_result=None,
            match_score=None,
            score_breakdown={},
            analysis_reason="候选人仅支持意向沟通，已直接跳过并返回搜索结果页。",
            evidence_summary=[f"详情页存在“{INTENTION_COMMUNICATION_LABEL}”入口，不支持立即沟通。"],
            hit_status=hit_status,
            continue_without_db_record=False,
            message_send_success=False,
            message_send_reason=hit_status,
            message_send_attempts=0,
            message_screenshot_path=screenshot_path,
        )

    def _handle_unsupported_communication_candidate(
        self,
        *,
        seed: SearchTaskSeed,
        card: CandidateCard,
        reason: str,
    ) -> CandidateProcessOutcome:
        candidate_name = self._extract_candidate_name_from_card(card.raw_text)
        search_keyword = seed.effective_query
        screenshot_path = self._capture_debug_screenshot(
            status="unsupported_communication_skipped",
            seed=seed,
            candidate_name=candidate_name,
        )
        self._log(
            "candidate_skipped_unsupported_communication",
            "Skipped candidate because communication action is neither immediate nor continue",
            candidate_name=candidate_name,
            search_keyword=search_keyword,
            reason=reason,
        )
        self.device.press_back()
        self._wait(2.0)
        return CandidateProcessOutcome(
            candidate_name=candidate_name,
            candidate_fingerprint=card.fingerprint[:64],
            search_keyword=search_keyword,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            communication_label=None,
            analysis_status="沟通方式不支持跳过",
            match_result=None,
            match_score=None,
            score_breakdown={},
            analysis_reason="候选人详情页未出现立即沟通或继续沟通按钮，已直接跳过并返回搜索结果页。",
            evidence_summary=[],
            hit_status="unsupported_communication_skipped",
            continue_without_db_record=False,
            message_send_success=False,
            message_send_reason="unsupported_communication_skipped",
            message_send_attempts=0,
            message_screenshot_path=screenshot_path,
        )

    def _handle_profile_collection_recoverable_error(
        self,
        *,
        seed: SearchTaskSeed,
        card: CandidateCard,
        reason: str,
    ) -> CandidateProcessOutcome:
        candidate_name = self._extract_candidate_name_from_card(card.raw_text)
        search_keyword = seed.effective_query
        screenshot_path = self._capture_debug_screenshot(
            status="profile_collection_skipped",
            seed=seed,
            candidate_name=candidate_name,
        )
        if self.write_to_db:
            self._record_search_hit_fact(
                settings=get_settings(),
                candidate_fingerprint=card.fingerprint,
                candidate_name=candidate_name,
                search_keyword=search_keyword,
                hit_status="profile_collection_skipped",
            )
        self._log(
            "candidate_profile_collection_skipped",
            "Skipped current candidate after recoverable profile collection failure",
            candidate_name=candidate_name,
            search_keyword=search_keyword,
            candidate_card_fingerprint=card.fingerprint,
            error_message=reason,
        )
        self._tap_back_button_or_fallback()
        self._wait(2.0)
        return CandidateProcessOutcome(
            candidate_name=candidate_name,
            candidate_fingerprint=card.fingerprint,
            search_keyword=search_keyword,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            communication_label=None,
            analysis_status="采集失败跳过",
            match_result=None,
            match_score=None,
            score_breakdown={},
            analysis_reason=f"候选人详情采集异常，已跳过当前候选人并继续本关键词。{reason}",
            evidence_summary=[],
            hit_status="profile_collection_skipped",
            continue_without_db_record=False,
            message_send_success=False,
            message_send_reason="profile_collection_skipped",
            message_send_attempts=0,
            message_screenshot_path=screenshot_path,
        )

    def _handle_job_intent_city_rejected_candidate(
        self,
        *,
        seed: SearchTaskSeed,
        card: CandidateCard,
        candidate_name: str | None,
        candidate_fingerprint: str,
        experience_hash: str,
        experience: str | None,
        communication_label: str | None,
        platform_locator,
        required_city: str,
        intent_lines: list[str],
    ) -> CandidateProcessOutcome:
        settings = get_settings()
        search_keyword = seed.effective_query
        hit_status = "job_intent_city_rejected"
        intent_text = "；".join(item.strip() for item in intent_lines if item.strip())
        if self.write_to_db:
            self._record_search_hit_fact(
                settings=settings,
                candidate_fingerprint=candidate_fingerprint,
                candidate_name=candidate_name,
                search_keyword=search_keyword,
                hit_status=hit_status,
            )
        self._log(
            "candidate_job_intent_city_rejected",
            "Skipped candidate because job-intent city does not explicitly match requirement",
            candidate_name=candidate_name,
            candidate_fingerprint=candidate_fingerprint,
            required_city=required_city,
            job_intent=intent_text,
            current_city_not_used=True,
        )
        self.device.press_back()
        self._wait(2.0)
        return CandidateProcessOutcome(
            candidate_name=candidate_name,
            candidate_fingerprint=candidate_fingerprint,
            search_keyword=search_keyword,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            communication_label=communication_label,
            analysis_status="求职意向城市不符",
            match_result="not_relevant",
            match_score=None,
            score_breakdown={},
            analysis_reason=f"求职意向未明确包含{required_city}，已在AI分析和沟通前严格跳过。",
            evidence_summary=[f"求职意向原文：{intent_text or '未填写/未采集到'}"],
            hit_status=hit_status,
            continue_without_db_record=False,
            message_send_success=False,
            message_send_reason=hit_status,
            message_send_attempts=0,
            message_screenshot_path=None,
            profile_collection_completed=True,
        )

    def _handle_candidate_age_rejected(
        self,
        *,
        seed: SearchTaskSeed,
        card: CandidateCard,
        candidate_name: str | None,
        candidate_fingerprint: str,
        communication_label: str | None,
        candidate_age: int,
        maximum_age: int,
    ) -> CandidateProcessOutcome:
        settings = get_settings()
        search_keyword = seed.effective_query
        hit_status = "candidate_age_rejected"
        if self.write_to_db:
            self._record_search_hit_fact(
                settings=settings,
                candidate_fingerprint=candidate_fingerprint,
                candidate_name=candidate_name,
                search_keyword=search_keyword,
                hit_status=hit_status,
            )
        self._log(
            "candidate_age_rejected_after_profile_collection",
            "Skipped candidate before AI because profile age exceeds the job requirement",
            candidate_name=candidate_name,
            candidate_fingerprint=candidate_fingerprint,
            candidate_age=candidate_age,
            maximum_age=maximum_age,
        )
        self.device.press_back()
        self._wait(2.0)
        return CandidateProcessOutcome(
            candidate_name=candidate_name,
            candidate_fingerprint=candidate_fingerprint,
            search_keyword=search_keyword,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            communication_label=communication_label,
            analysis_status="年龄不符合",
            match_result="not_relevant",
            match_score=None,
            score_breakdown={},
            analysis_reason=f"候选人年龄为{candidate_age}岁，超过岗位要求的{maximum_age}岁，已在AI分析和沟通前跳过。",
            evidence_summary=[f"候选人详情年龄：{candidate_age}岁"],
            hit_status=hit_status,
            continue_without_db_record=False,
            message_send_success=False,
            message_send_reason=hit_status,
            message_send_attempts=0,
            message_screenshot_path=None,
            candidate_age=candidate_age,
            profile_collection_completed=True,
        )

    def _handle_unified_bachelor_requirement_failed(
        self,
        *,
        seed: SearchTaskSeed,
        candidate_name: str | None,
        candidate_fingerprint: str,
        communication_label: str | None,
        candidate_age: int | None,
        requirement_status: str,
        education_experiences: object,
    ) -> CandidateProcessOutcome:
        settings = get_settings()
        search_keyword = seed.effective_query
        is_unknown = requirement_status == UNIFIED_BACHELOR_UNKNOWN
        hit_status = (
            "unified_bachelor_unverified"
            if is_unknown
            else "unified_bachelor_rejected"
        )
        analysis_status = "统招学历待确认" if is_unknown else "统招学历不符合"
        match_result = "needs_review" if is_unknown else "not_relevant"
        education_evidence = self._education_evidence_summary(education_experiences)
        if self.write_to_db:
            self._record_search_hit_fact(
                settings=settings,
                candidate_fingerprint=candidate_fingerprint,
                candidate_name=candidate_name,
                search_keyword=search_keyword,
                hit_status=hit_status,
            )
        self._log(
            "candidate_unified_bachelor_requirement_failed",
            "Skipped candidate before AI and communication because unified bachelor requirement was not verified",
            candidate_name=candidate_name,
            candidate_fingerprint=candidate_fingerprint,
            requirement_status=requirement_status,
            education_evidence=education_evidence,
        )
        self.device.press_back()
        self._wait(2.0)
        reason = (
            "未采集到可核验的教育经历，无法确认统招本科及以上，已在AI分析和沟通前转人工待确认。"
            if is_unknown
            else "教育经历中未发现明确的“统招”本科及以上徽标，已在AI分析和沟通前严格跳过。"
        )
        return CandidateProcessOutcome(
            candidate_name=candidate_name,
            candidate_fingerprint=candidate_fingerprint,
            search_keyword=search_keyword,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            communication_label=communication_label,
            analysis_status=analysis_status,
            match_result=match_result,
            match_score=None,
            score_breakdown={},
            analysis_reason=reason,
            evidence_summary=education_evidence,
            hit_status=hit_status,
            continue_without_db_record=False,
            message_send_success=False,
            message_send_reason=hit_status,
            message_send_attempts=0,
            message_screenshot_path=None,
            candidate_age=candidate_age,
            profile_collection_completed=True,
        )

    @staticmethod
    def _education_evidence_summary(education_experiences: object) -> list[str]:
        if not isinstance(education_experiences, list):
            return ["教育经历：未采集到"]
        evidence = [
            str(item.get("raw_text") or "").strip()
            for item in education_experiences
            if isinstance(item, dict) and str(item.get("raw_text") or "").strip()
        ]
        return evidence[:3] or ["教育经历：未采集到"]

    def _handle_manual_review_suppressed_candidate(
        self,
        *,
        seed: SearchTaskSeed,
        candidate_name: str | None,
        candidate_fingerprint: str,
        communication_label: str | None,
        candidate_age: int | None,
        manual_review_status: str | None,
        manual_review_feedback: str | None,
        profile_collection_completed: bool = False,
    ) -> CandidateProcessOutcome:
        """Honor an HR disposition without overwriting the original AI decision."""

        settings = get_settings()
        status = manual_review_status or "manual_reviewed"
        hit_status = f"manual_review_suppressed:{status}"
        if self.write_to_db:
            self._record_search_hit_fact(
                settings=settings,
                candidate_fingerprint=candidate_fingerprint,
                candidate_name=candidate_name,
                search_keyword=seed.effective_query,
                hit_status=hit_status,
            )
        self._log(
            "candidate_manual_review_suppressed",
            "Skipped AI analysis and communication because HR already dispositioned the candidate",
            candidate_name=candidate_name,
            candidate_fingerprint=candidate_fingerprint,
            manual_review_status=status,
            manual_review_feedback=manual_review_feedback,
        )
        self.device.press_back()
        self._wait(2.0)
        return CandidateProcessOutcome(
            candidate_name=candidate_name,
            candidate_fingerprint=candidate_fingerprint,
            search_keyword=seed.effective_query,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            communication_label=communication_label,
            analysis_status=f"人工已处理：{status}",
            match_result=None,
            match_score=None,
            score_breakdown={},
            analysis_reason=manual_review_feedback,
            evidence_summary=[],
            hit_status=hit_status,
            continue_without_db_record=False,
            message_send_success=False,
            message_send_reason="manual_review_suppressed",
            message_send_attempts=0,
            message_screenshot_path=None,
            candidate_age=candidate_age,
            profile_collection_completed=profile_collection_completed,
        )

    @staticmethod
    def _analysis_result_from_cache_record(
        record: dict[str, object] | None,
    ) -> ResumeAnalysisResult | None:
        if record is None:
            return None
        match_result = ResultPageFilter._normalize_optional_str(record.get("match_result"))
        analysis_status = ResultPageFilter._normalize_optional_str(record.get("analysis_status"))
        return ResumeAnalysisResult(
            matched=is_relevant_match_result(match_result),
            match_result=match_result,
            match_score=ResultPageFilter._normalize_optional_int(record.get("match_score")),
            score_breakdown=ResultPageFilter._parse_score_breakdown(record.get("score_breakdown_json")),
            analysis_reason=ResultPageFilter._normalize_optional_str(record.get("analysis_reason")),
            evidence_summary=ResultPageFilter._parse_string_list(record.get("evidence_summary_json")),
            raw_data="",
            parsed_payload={},
            execution_status=record.get("execution_status"),
            error_code=record.get("error_code"),
            retryable=bool(record.get("retryable")),
            retry_after=record.get("retry_after"),
            analysis_run_id=record.get("analysis_run_id"),
            analyzed_text_hash=record.get("analyzed_text_hash"),
        )

    @staticmethod
    def _is_intention_communication_page(root: ElementTree.Element) -> bool:
        return any_label(root, INTENTION_COMMUNICATION_LABEL) or any_label_contains(
            root,
            INTENTION_COMMUNICATION_LABEL,
        )

    @staticmethod
    def _extract_candidate_name_from_card(raw_text: str) -> str | None:
        normalized = " ".join(raw_text.split())
        if not normalized:
            return None
        return sanitize_candidate_name(normalized.split(" ")[0])

    @staticmethod
    def _parse_score_breakdown(value: object) -> dict[str, int]:
        payload = ResultPageFilter._parse_json_value(value)
        if not isinstance(payload, dict):
            return {}
        result: dict[str, int] = {}
        for key, raw in payload.items():
            if not isinstance(key, str):
                continue
            normalized = ResultPageFilter._normalize_optional_int(raw)
            if normalized is not None:
                result[key] = normalized
        return result

    @staticmethod
    def _parse_string_list(value: object) -> list[str]:
        payload = ResultPageFilter._parse_json_value(value)
        if not isinstance(payload, list):
            return []
        return [item.strip() for item in payload if isinstance(item, str) and item.strip()]

    @staticmethod
    def _parse_json_value(value: object) -> object:
        if not isinstance(value, str) or not value.strip():
            return None
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return None

    @staticmethod
    def _normalize_optional_str(value: object) -> str | None:
        if not isinstance(value, str):
            return None
        normalized = value.strip()
        return normalized or None

    @staticmethod
    def _normalize_optional_int(value: object) -> int | None:
        if isinstance(value, bool):
            return None
        if isinstance(value, int):
            return value
        if isinstance(value, float) and value.is_integer():
            return int(value)
        if isinstance(value, str):
            stripped = value.strip()
            if stripped.isdigit():
                return int(stripped)
        return None

    def _tap_continue_communication(
        self,
        *,
        candidate_name: str | None,
        role_keyword: str,
        button_label: str,
    ) -> MessageSendResult:
        root = ElementTree.fromstring(self.device.dump_hierarchy_text())
        communication_action = find_profile_communication_action(root)
        if communication_action is None:
            self._log(
                "candidate_communication_button_missing",
                "Continue communication button not found on candidate detail page",
                candidate_name=candidate_name,
            )
            screenshot_path = self._capture_debug_screenshot(
                status="communication_button_not_found",
                candidate_name=candidate_name,
            )
            return MessageSendResult(
                success=False,
                attempts=0,
                reason="communication_button_not_found",
                screenshot_path=screenshot_path,
            )

        _, button_center = communication_action
        self._log(
            "candidate_communication_open",
            "Opening chat from candidate detail page",
            candidate_name=candidate_name,
            button_label=button_label,
            tap_x=button_center[0],
            tap_y=button_center[1],
        )
        self.device.tap(*button_center)
        self._wait(2.5)
        if button_label == "立即沟通":
            selected = choose_job_before_chat_if_present(
                self.device,
                role_keyword=role_keyword,
                wait=self._wait,
            )
            self._log(
                "candidate_job_selection_checked",
                "Checked job selection page after immediate communication",
                candidate_name=candidate_name,
                role_keyword=role_keyword,
                job_selection_handled=selected,
            )
            root_after_selection = ElementTree.fromstring(self.device.dump_hierarchy_text())
            chat_input_center = find_chat_input_center(root_after_selection)
            if not selected:
                result = MessageSendResult(
                    success=False,
                    attempts=1,
                    reason="job_selection_not_handled",
                    screenshot_path=self._capture_message_result_screenshot(
                        candidate_name=candidate_name,
                        status="failed",
                    ),
                )
            elif chat_input_center is None:
                result = MessageSendResult(
                    success=False,
                    attempts=1,
                    reason="post_job_selection_chat_not_detected",
                    screenshot_path=self._capture_message_result_screenshot(
                        candidate_name=candidate_name,
                        status="failed",
                    ),
                )
            else:
                # Selecting a job is only an intermediate step. Entering chat must
                # continue through custom-message input and post-send verification.
                result = self._send_chat_message(
                    candidate_name=candidate_name,
                    role_keyword=role_keyword,
                )
            self._tap_back_button_or_fallback()
            self._wait(2.0)
            return result

        result = self._send_chat_message(candidate_name=candidate_name, role_keyword=role_keyword)
        self._tap_back_button_or_fallback()
        self._wait(2.0)
        return result

    def _send_chat_message(
        self,
        *,
        candidate_name: str | None,
        role_keyword: str,
    ) -> MessageSendResult:
        message = CHAT_MESSAGE_TEMPLATE.format(role=role_keyword)
        last_reason = "chat_input_not_found"
        screenshot_path: str | None = None

        for attempt in range(1, CHAT_SEND_MAX_ATTEMPTS + 1):
            self._log(
                "chat_message_attempt_start",
                "Attempting to input and send chat message",
                candidate_name=candidate_name,
                attempt=attempt,
                role_keyword=role_keyword,
            )
            root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            input_center = find_chat_input_center(root)
            if input_center is None:
                break

            self.device.tap(*input_center)
            self._wait(1.2)
            self.device.send_keys(message, clear=True)
            self._wait(1.2)

            input_box = self.device._require_device()(className="android.widget.EditText")
            actual_text = input_box.get_text() if input_box.exists else None
            if actual_text != message:
                last_reason = f"input_verification_failed_attempt_{attempt}"
                self._log(
                    "chat_message_verification_failed",
                    "Chat input verification failed",
                    candidate_name=candidate_name,
                    attempt=attempt,
                    expected_length=len(message),
                    actual_length=len(actual_text or ""),
                )
                continue

            refreshed_root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            send_button = find_chat_send_button(refreshed_root)
            if send_button is None:
                last_reason = f"send_button_not_found_attempt_{attempt}"
                self._log(
                    "chat_send_button_missing",
                    "Chat send button not found after input verification",
                    candidate_name=candidate_name,
                    attempt=attempt,
                )
                continue

            self.device.tap(*send_button)
            self._wait(2.0)

            confirm_input = self.device._require_device()(className="android.widget.EditText")
            confirm_text = confirm_input.get_text() if confirm_input.exists else None
            confirm_root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            confirm_input_center = find_chat_input_center(confirm_root)
            if confirm_text:
                last_reason = f"post_send_input_not_cleared_attempt_{attempt}"
                self._log(
                    "chat_post_send_check_failed",
                    "Chat input still contains text after send attempt",
                    candidate_name=candidate_name,
                    attempt=attempt,
                )
                continue
            if confirm_input_center is None:
                last_reason = f"post_send_page_verification_failed_attempt_{attempt}"
                self._log(
                    "chat_post_send_page_verification_failed",
                    "Chat page verification failed after send attempt",
                    candidate_name=candidate_name,
                    attempt=attempt,
                )
                continue

            screenshot_path = self._capture_message_result_screenshot(
                candidate_name=candidate_name,
                status="success",
            )
            if screenshot_path is None:
                last_reason = f"success_screenshot_failed_attempt_{attempt}"
                self._log(
                    "chat_success_screenshot_failed",
                    "Could not capture success screenshot after send attempt",
                    candidate_name=candidate_name,
                    attempt=attempt,
                )
                continue
            self._log(
                "chat_message_sent",
                "Chat message sent successfully",
                candidate_name=candidate_name,
                attempt=attempt,
                screenshot_path=screenshot_path,
            )
            return MessageSendResult(
                success=True,
                attempts=attempt,
                reason="sent",
                screenshot_path=screenshot_path,
            )

        screenshot_path = self._capture_message_result_screenshot(
            candidate_name=candidate_name,
            status="failed",
        )
        self._log(
            "chat_message_failed",
            "Chat message failed after all attempts",
            candidate_name=candidate_name,
            attempts=CHAT_SEND_MAX_ATTEMPTS,
            reason=last_reason,
            screenshot_path=screenshot_path,
        )
        return MessageSendResult(
            success=False,
            attempts=CHAT_SEND_MAX_ATTEMPTS,
            reason=last_reason,
            screenshot_path=screenshot_path,
        )

    def _capture_message_result_screenshot(
        self,
        *,
        candidate_name: str | None,
        status: str,
    ) -> str | None:
        settings = get_settings()
        screenshot_dir = settings.artifacts_dir / "message_delivery"
        screenshot_dir.mkdir(parents=True, exist_ok=True)
        safe_name = self._slugify(candidate_name or "unknown_candidate")
        screenshot_path = screenshot_dir / f"{safe_name}_{status}.png"
        try:
            self.device.screenshot(screenshot_path)
        except Exception:
            self._log(
                "message_screenshot_capture_failed",
                "Failed to capture message result screenshot",
                candidate_name=candidate_name,
                status=status,
            )
            return None
        return str(screenshot_path)

    def _capture_debug_screenshot(
        self,
        *,
        status: str,
        seed: SearchTaskSeed | None = None,
        candidate_name: str | None = None,
    ) -> str | None:
        settings = get_settings()
        screenshot_dir = settings.artifacts_dir / "debug_screenshots"
        screenshot_dir.mkdir(parents=True, exist_ok=True)
        parts = [
            self._slugify(status),
            self._slugify(seed.brand_keyword) if seed is not None else None,
            self._slugify(seed.role_keyword) if seed is not None else None,
            self._slugify(candidate_name) if candidate_name else None,
            str(int(time.time())),
        ]
        filename = "_".join(part for part in parts if part) + ".png"
        screenshot_path = screenshot_dir / filename
        try:
            self.device.screenshot(screenshot_path)
        except Exception:
            self._log(
                "debug_screenshot_capture_failed",
                "Failed to capture debug screenshot",
                status=status,
                candidate_name=candidate_name,
                brand_keyword=seed.brand_keyword if seed is not None else None,
                role_keyword=seed.role_keyword if seed is not None else None,
            )
            return None
        self._log(
            "debug_screenshot_captured",
            "Captured debug screenshot",
            status=status,
            screenshot_path=str(screenshot_path),
            candidate_name=candidate_name,
            brand_keyword=seed.brand_keyword if seed is not None else None,
            role_keyword=seed.role_keyword if seed is not None else None,
        )
        return str(screenshot_path)

    def _capture_candidate_locator_screenshot(
        self,
        *,
        seed: SearchTaskSeed,
        candidate_name: str | None,
        candidate_fingerprint: str,
    ) -> str | None:
        settings = get_settings()
        screenshot_dir = settings.artifacts_dir / "candidate_locators"
        screenshot_dir.mkdir(parents=True, exist_ok=True)
        filename = "_".join(
            [
                self._slugify(candidate_name or "unknown_candidate"),
                self._slugify(candidate_fingerprint[:12]),
                self._slugify(seed.search_pack_id or "search"),
                str(int(time.time())),
            ]
        ) + ".png"
        screenshot_path = screenshot_dir / filename
        try:
            self.device.screenshot(screenshot_path)
        except Exception:
            self._log(
                "candidate_locator_screenshot_failed",
                "Failed to capture candidate locator screenshot",
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
            )
            return None
        return str(screenshot_path)

    def _copy_candidate_share_link(self) -> str | None:
        """Copy the official share URL while keeping the resume detail page open."""

        try:
            device_info = self.device.device_info()
            width = int(device_info.get("displayWidth") or device_info.get("width") or 1080)
            height = int(device_info.get("displayHeight") or device_info.get("height") or 2388)
            previous_clipboard = self.device.get_clipboard()

            # Second action in the top-right row: share.
            self.device.tap(int(width * 0.72), int(height * 0.083))
            self._wait(0.8)
            share_root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            if self._share_quota_is_exhausted_on_page(share_root):
                self._share_quota_store().mark_exhausted(reason="liepin_ui_quota_message")
                self._dismiss_share_sheet(width, height)
                self._log(
                    "candidate_share_quota_exhausted",
                    "Liepin reported that today's share quota is exhausted",
                )
                return None
            copy_center = self._find_bottom_sheet_action(share_root, "复制链接")
            if copy_center is None:
                more_center = self._find_bottom_sheet_action(share_root, "更多")
                if more_center is not None:
                    self.device.tap(*more_center)
                    self._wait(0.8)
                    share_root = ElementTree.fromstring(self.device.dump_hierarchy_text())
                    if self._share_quota_is_exhausted_on_page(share_root):
                        self._share_quota_store().mark_exhausted(
                            reason="liepin_ui_quota_message"
                        )
                        self._dismiss_share_sheet(width, height)
                        return None
                    copy_center = self._find_bottom_sheet_action(share_root, "复制链接")
            if copy_center is None:
                self._dismiss_share_sheet(width, height)
                self._log(
                    "candidate_share_link_copy_failed",
                    "Share sheet did not expose a copy-link action",
                    reason="copy_link_action_missing",
                )
                return None

            self.device.tap(*copy_center)
            self._wait(0.6)
            post_copy_root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            if self._share_quota_is_exhausted_on_page(post_copy_root):
                self._share_quota_store().mark_exhausted(reason="liepin_ui_quota_message")
                self._dismiss_share_sheet(width, height)
                self._log(
                    "candidate_share_quota_exhausted",
                    "Liepin reported that today's share quota is exhausted after Copy Link",
                )
                return None
            share_link = self.device.get_clipboard()
            self._dismiss_share_sheet(width, height)
            if not is_official_resume_share_link(share_link):
                if share_link == previous_clipboard:
                    self._share_quota_store().mark_exhausted(
                        reason="copy_link_produced_no_clipboard_change"
                    )
                self._log(
                    "candidate_share_link_copy_failed",
                    "Clipboard did not contain an official Liepin resume share URL",
                    reason="invalid_clipboard_url",
                    clipboard_changed=share_link != previous_clipboard,
                )
                return None
            return share_link
        except Exception as exc:
            self._log(
                "candidate_share_link_copy_failed",
                "Failed to copy official candidate share link",
                reason=str(exc),
            )
            return None

    def _persistence_search_batch_id(self) -> str | None:
        """JD smart writes must belong to an explicit parent batch."""

        if self.write_to_db and self.selection_mode == "jd_smart" and not self.search_batch_id:
            raise ResultFilterError("JD smart database writes require a search batch id")
        return self.search_batch_id

    def _record_search_hit_fact(
        self,
        *,
        settings,
        candidate_fingerprint: str,
        candidate_name: str | None,
        search_keyword: str,
        hit_status: str,
    ) -> None:
        if not self.write_to_db:
            return
        record_candidate_search_hit(
            settings,
            candidate_fingerprint=candidate_fingerprint,
            candidate_name=candidate_name,
            search_keyword=search_keyword,
            hit_status=hit_status,
            search_batch_id=self._persistence_search_batch_id(),
        )

    def _batch_trace_for_seed(self, seed: SearchTaskSeed) -> BatchTrace | None:
        if not self.search_batch_id and not self.search_batch_job_id:
            if self.write_to_db and self.selection_mode == "jd_smart":
                raise ResultFilterError(
                    "JD smart database writes require parent batch and batch job ids"
                )
            return None
        if not self.search_batch_id or not self.search_batch_job_id:
            raise ResultFilterError("batch trace must include parent batch and batch job ids")
        job_profile_id = (
            str(self.job_profile.get("id") or "").strip()
            if isinstance(self.job_profile, dict)
            else ""
        )
        if not job_profile_id:
            raise ResultFilterError("batch trace requires a job profile id")
        try:
            return trace_for_seed(
                parent_batch_id=self.search_batch_id,
                batch_job_id=self.search_batch_job_id,
                job_profile_id=job_profile_id,
                seed=seed,
            )
        except ValueError as exc:
            raise ResultFilterError(str(exc)) from exc

    def _record_entered_candidate_detail(
        self,
        *,
        seed: SearchTaskSeed,
        card: CandidateCard,
        platform_locator,
        platform_interaction_note: str | None = None,
    ) -> int | None:
        """Persist account-specific locator evidence on detail entry."""

        if not self.write_to_db:
            return None
        settings = get_settings()
        candidate_name = self._extract_candidate_name_from_card(card.raw_text)
        locator_screenshot_path = self._capture_candidate_locator_screenshot(
            seed=seed,
            candidate_name=candidate_name,
            candidate_fingerprint=card.fingerprint,
        )
        locator_id = record_candidate_source_locator(
            settings,
            candidate_fingerprint=card.fingerprint[:64],
            experience_hash=None,
            candidate_name=candidate_name,
            search_keyword=seed.effective_query,
            source_account_alias=settings.liepin_account_alias,
            source_device_id=settings.device_id,
            job_profile_id=(
                str(self.job_profile.get("id") or "")
                if isinstance(self.job_profile, dict)
                else None
            ),
            search_pack_id=seed.search_pack_id,
            search_batch_id=self._persistence_search_batch_id(),
            result_iteration=card.result_iteration,
            result_rank=card.result_rank,
            candidate_card_fingerprint=card.fingerprint,
            candidate_card_text=card.raw_text,
            platform_candidate_id=platform_locator.platform_candidate_id,
            platform_resume_id=platform_locator.platform_resume_id,
            detail_deep_link=platform_locator.detail_deep_link,
            detail_activity=platform_locator.detail_activity,
            detail_hierarchy_hash=platform_locator.detail_hierarchy_hash,
            locator_quality=platform_locator.locator_quality,
            detail_screenshot_path=locator_screenshot_path,
            platform_interaction_note=platform_interaction_note,
        )
        self._log(
            "candidate_source_locator_recorded",
            "Recorded candidate locator immediately after detail entry",
            candidate_name=candidate_name,
            candidate_card_fingerprint=card.fingerprint,
            locator_id=locator_id,
            platform_interaction_note=platform_interaction_note,
        )
        return locator_id

    def _preserve_high_value_candidate(
        self,
        *,
        settings,
        locator_record_id: int | None,
        candidate_name: str | None,
        candidate_fingerprint: str,
        experience_hash: str,
        analysis_result: ResumeAnalysisResult | None,
    ) -> None:
        """Save a scarce share link, or favorite after the daily quota is spent."""

        if (
            not self.write_to_db
            or locator_record_id is None
            or not self._is_high_value_analysis(analysis_result)
        ):
            return

        existing_share_link = self._find_existing_official_share_link(
            settings=settings,
            candidate_fingerprint=candidate_fingerprint,
            experience_hash=experience_hash,
        )
        if existing_share_link:
            update_candidate_source_locator_share_link(
                settings,
                locator_id=locator_record_id,
                share_link=existing_share_link,
            )
            self._log(
                "candidate_share_link_reused",
                "Reused an existing official share link without consuming share quota",
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                locator_id=locator_record_id,
            )
            return

        quota_store = self._share_quota_store()
        if not quota_store.is_exhausted():
            share_link = self._copy_candidate_share_link()
            if share_link:
                update_candidate_source_locator_share_link(
                    settings,
                    locator_id=locator_record_id,
                    share_link=share_link,
                )
                self._log(
                    "candidate_share_link_recorded",
                    "Saved an official share link for a high-value candidate",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    locator_id=locator_record_id,
                )
                return
            if not quota_store.is_exhausted():
                self._log(
                    "candidate_share_link_unavailable",
                    "Share-link capture failed; preserving the candidate through Favorites",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    locator_id=locator_record_id,
                )

        if self._favorite_current_candidate():
            update_candidate_source_locator_favorite(
                settings,
                locator_id=locator_record_id,
            )
            self._log(
                "candidate_favorite_recorded",
                "Favorited a high-value candidate because today's share quota is exhausted",
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                locator_id=locator_record_id,
            )

    @staticmethod
    def _is_high_value_analysis(analysis_result: ResumeAnalysisResult | None) -> bool:
        if analysis_result is None:
            return False
        if has_analysis_execution_failure(
            analysis_result.analysis_reason, execution_status=analysis_result.execution_status
        ):
            return False
        if is_relevant_match_result(analysis_result.match_result):
            return True
        return bool(
            is_needs_review_match_result(analysis_result.match_result)
            and analysis_result.match_score is not None
            and analysis_result.match_score >= ACTIONABLE_NEEDS_REVIEW_MIN_SCORE
        )

    @staticmethod
    def _share_quota_is_exhausted_on_page(root: ElementTree.Element) -> bool:
        page_text = " ".join(node_label(node) for node in root.iter("node"))
        return any(label in page_text for label in SHARE_QUOTA_EXHAUSTED_LABELS)

    @staticmethod
    def _find_existing_official_share_link(
        *,
        settings,
        candidate_fingerprint: str,
        experience_hash: str,
    ) -> str | None:
        identity_queries = (
            {"experience_hash": experience_hash},
            {"candidate_fingerprint": candidate_fingerprint},
        )
        for identity in identity_queries:
            rows = list_candidate_source_locators(settings, limit=50, **identity)
            for row in rows:
                share_link = str(row.get("detail_deep_link") or "").strip()
                if is_official_resume_share_link(share_link):
                    return share_link
        return None

    def _share_quota_store(self) -> DailyShareQuotaStateStore:
        settings = get_settings()
        return DailyShareQuotaStateStore(
            settings.artifacts_dir / "share_quota_state.json",
            account_alias=settings.liepin_account_alias,
        )

    def _favorite_current_candidate(self) -> bool:
        """Favorite the current detail page without navigating away from it."""

        try:
            device_info = self.device.device_info()
            width = int(device_info.get("displayWidth") or device_info.get("width") or 1080)
            height = int(device_info.get("displayHeight") or device_info.get("height") or 2388)
            root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            if self._page_has_any_label(root, ("已收藏", "取消收藏")) or self._favorite_icon_is_filled(
                width=width,
                height=height,
                stage="before",
            ):
                self._log(
                    "candidate_already_favorited",
                    "Candidate detail already shows a filled Favorite icon",
                )
                return True
            favorite_center = find_clickable_labeled_center(root, ("收藏",))
            if favorite_center is None:
                # The first action in the candidate-detail top-right row is Favorite.
                favorite_center = (int(width * 0.61), int(height * 0.083))
            self.device.tap(*favorite_center)
            self._wait(0.8)
            confirmation_root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            confirmed = self._page_has_any_label(
                confirmation_root,
                ("已收藏", "取消收藏", "收藏成功"),
            ) or self._favorite_icon_is_filled(
                width=width,
                height=height,
                stage="after",
            )
            if not confirmed:
                self._log(
                    "candidate_favorite_unconfirmed",
                    "Tapped Favorite but could not confirm the resulting UI state",
                )
            return confirmed
        except Exception as exc:
            self._log(
                "candidate_favorite_failed",
                "Failed to favorite the current candidate",
                reason=str(exc),
            )
            return False

    def _favorite_icon_is_filled(
        self,
        *,
        width: int,
        height: int,
        stage: str,
    ) -> bool:
        """Confirm Liepin's orange filled star when accessibility has no state."""

        settings = get_settings()
        evidence_dir = settings.artifacts_dir / "favorite_confirmations"
        evidence_dir.mkdir(parents=True, exist_ok=True)
        evidence_path = evidence_dir / f"favorite_{stage}_{int(time.time() * 1000)}.png"
        try:
            self.device.screenshot(evidence_path)
            with Image.open(evidence_path) as screenshot:
                rgb = screenshot.convert("RGB")
                crop = rgb.crop(
                    (
                        int(width * 0.555),
                        int(height * 0.052),
                        int(width * 0.655),
                        int(height * 0.105),
                    )
                )
                orange_pixels = sum(
                    1
                    for red, green, blue in crop.getdata()
                    if red >= 220
                    and 55 <= green <= 180
                    and blue <= 80
                    and red - green >= 70
                )
            self._log(
                "candidate_favorite_visual_check",
                "Checked the top-right Favorite icon fill color",
                stage=stage,
                orange_pixels=orange_pixels,
                evidence_path=str(evidence_path),
            )
            return orange_pixels >= 80
        except Exception as exc:
            self._log(
                "candidate_favorite_visual_check_failed",
                "Failed to inspect Favorite icon fill color",
                stage=stage,
                reason=str(exc),
            )
            return False

    @staticmethod
    def _page_has_any_label(
        root: ElementTree.Element,
        expected_labels: tuple[str, ...],
    ) -> bool:
        return any(
            any(expected in node_label(node) for expected in expected_labels)
            for node in root.iter("node")
        )

    @staticmethod
    def _find_bottom_sheet_action(
        root: ElementTree.Element,
        label: str,
    ) -> tuple[int, int] | None:
        candidates: list[tuple[int, tuple[int, int]]] = []
        for node in root.iter("node"):
            if node_label(node) != label or node.attrib.get("clickable") != "true":
                continue
            bounds = parse_bounds(node.attrib.get("bounds", ""))
            if bounds is None:
                continue
            candidates.append((bounds.y1, bounds.center))
        if not candidates:
            return None
        candidates.sort(key=lambda item: item[0], reverse=True)
        return candidates[0][1]

    def _dismiss_share_sheet(self, width: int, height: int) -> None:
        """Close the share panel through its explicit bottom Cancel action."""

        try:
            root = ElementTree.fromstring(self.device.dump_hierarchy_text())
            cancel_center = self._find_bottom_sheet_action(root, "取消")
            if cancel_center is not None:
                self.device.tap(*cancel_center)
                self._wait(0.3)
                return

            # Some app builds expose the bottom-sheet label without marking
            # the node clickable.  Keep the explicit action semantics and tap
            # its stable bottom position instead of pressing Back (which would
            # leave the candidate detail page).
            page_text = " ".join(node_label(node) for node in root.iter("node"))
            share_markers = ("温馨提示", "复制链接", "招聘目的")
            if "取消" in page_text and any(marker in page_text for marker in share_markers):
                self.device.tap(width // 2, int(height * 0.90))
                self._wait(0.3)
                self._log(
                    "candidate_share_sheet_dismiss_fallback",
                    "Dismissed share sheet using bottom-sheet fallback position",
                )
                return

            # Copying a link may dismiss the Android/Liepin share sheet
            # automatically. In that state a Back press would navigate away from
            # the candidate profile, so absence of the explicit bottom Cancel
            # action must be treated as "already dismissed".
            self._log(
                "candidate_share_sheet_already_dismissed",
                "Share sheet no longer exposes its bottom Cancel action",
            )
        except Exception as exc:
            # Never use Back as a share-sheet fallback: it is indistinguishable
            # from leaving the candidate detail page once the sheet has closed.
            self._log(
                "candidate_share_sheet_dismiss_check_failed",
                "Failed to inspect the share sheet after copying the link",
                reason=str(exc),
            )

    @staticmethod
    def _build_analysis_resume_record(
        *,
        candidate_name: str | None,
        search_keyword: str,
        experience: str | None,
        age: int | None,
        education: str | None,
    ) -> dict[str, object]:
        return {
            "id": 0,
            "candidate_name": candidate_name or "",
            "source_platform": "liepin",
            "search_keyword": search_keyword,
            "experience": experience or "",
            "age": age,
            "education": education or "",
            "analysis_status": "未执行",
        }

    def _analysis_settings(self, settings):
        if self.selection_mode != "jd_smart":
            return settings
        profile = self.job_profile if isinstance(self.job_profile, dict) else {}
        profile_id = str(profile.get("id") or "").strip()
        profile_provider = str(profile.get("analysis_provider") or "").strip().lower()
        provider = profile_provider or settings.resume_analysis_provider.strip().lower()
        if provider not in {PROVIDER_COZE, PROVIDER_OPENAI_COMPATIBLE}:
            raise RuntimeError(f"Unsupported RESUME_ANALYSIS_PROVIDER: {provider}")
        if profile_id == "fde_ai_deployment_engineer_v1":
            if provider != PROVIDER_OPENAI_COMPATIBLE:
                raise RuntimeError("FDE resume analysis only supports openai_compatible")
            return settings.model_copy(
                update={
                    "resume_analysis_provider": PROVIDER_OPENAI_COMPATIBLE,
                    "coze_workflow_id": None,
                    "coze_backup_workflow_id": None,
                    "coze_analysis_version": settings.llm_fde_analysis_version,
                }
            )
        if profile_id == "ai_development_engineer_v1":
            if provider != PROVIDER_OPENAI_COMPATIBLE:
                raise RuntimeError(
                    "AI development resume analysis only supports openai_compatible"
                )
            return settings.model_copy(
                update={
                    "resume_analysis_provider": PROVIDER_OPENAI_COMPATIBLE,
                    "coze_workflow_id": None,
                    "coze_backup_workflow_id": None,
                    "coze_analysis_version": settings.llm_ai_development_analysis_version,
                }
            )
        workflow_key = "daren"
        workflow_key = str(profile.get("coze_workflow_key") or workflow_key).strip()
        if workflow_key == "finance":
            workflow_id = settings.coze_finance_workflow_id
            backup_workflow_id = settings.coze_finance_backup_workflow_id
            analysis_version = (
                settings.llm_finance_analysis_version
                if provider == PROVIDER_OPENAI_COMPATIBLE
                else settings.coze_finance_analysis_version
            )
            missing_setting = "COZE_FINANCE_WORKFLOW_ID"
        else:
            workflow_id = settings.coze_jd_workflow_id
            backup_workflow_id = settings.coze_jd_backup_workflow_id
            analysis_version = (
                settings.llm_jd_analysis_version
                if provider == PROVIDER_OPENAI_COMPATIBLE
                else settings.coze_jd_analysis_version
            )
            missing_setting = "COZE_JD_WORKFLOW_ID"
        if provider == PROVIDER_COZE and not workflow_id:
            raise RuntimeError(
                f"{missing_setting} is required for JD smart mode."
            )
        return settings.model_copy(
            update={
                "resume_analysis_provider": provider,
                "coze_workflow_id": workflow_id,
                "coze_backup_workflow_id": backup_workflow_id,
                "coze_analysis_version": analysis_version,
            }
        )

    def _analysis_prompt_path(self) -> Path | None:
        if not isinstance(self.job_profile, dict):
            return None
        raw_path = str(self.job_profile.get("analysis_prompt_path") or "").strip()
        return Path(raw_path) if raw_path else None

    def _analysis_contract(self) -> AnalysisContract:
        profile = self.job_profile if isinstance(self.job_profile, dict) else None
        return analysis_contract_from_job_profile(profile)

    def _required_job_intent_city(self) -> str | None:
        if self.selection_mode != "jd_smart" or not isinstance(self.job_profile, dict):
            return None
        city = str(self.job_profile.get("required_job_intent_city") or "").strip()
        return city or None

    def _required_candidate_maximum_age(self) -> int | None:
        if self.selection_mode != "jd_smart" or not isinstance(self.job_profile, dict):
            return None
        age_config = self.job_profile.get("age")
        if not isinstance(age_config, dict):
            return None
        return self._normalize_optional_int(age_config.get("maximum"))

    def _requires_unified_bachelor(self) -> bool:
        if self.selection_mode != "jd_smart" or not isinstance(self.job_profile, dict):
            return False
        requirement = self.job_profile.get("education_requirement")
        return bool(
            isinstance(requirement, dict)
            and requirement.get("require_unified_admission") is True
            and str(requirement.get("minimum_degree") or "").strip() == "本科"
        )

    @staticmethod
    def _derive_hit_status(
        *,
        analysis_status: str,
        match_result: str | None,
        message_send_result: MessageSendResult,
        continue_without_db_record: bool,
    ) -> str:
        if message_send_result.success:
            return "message_sent"
        if message_send_result.reason == "skipped_recent_continue":
            return "skipped_recent_continue"
        if message_send_result.reason == "skipped_global_cooldown":
            return "skipped_global_cooldown"
        if continue_without_db_record:
            return "continue_without_db_record"
        if is_not_relevant_match_result(match_result) or (
            match_result is None and analysis_status == ANALYSIS_STATUS_NOT_RELEVANT
        ):
            return "not_relevant"
        if is_relevant_match_result(match_result) or (
            match_result is None and analysis_status == ANALYSIS_STATUS_MATCHED
        ):
            return f"send_failed:{message_send_result.reason}"
        return "reviewed"

    def _warn_if_fingerprint_collision(
        self,
        *,
        candidate_name: str | None,
        candidate_fingerprint: str,
        existing_record: dict[str, object] | None,
        current_experience: str | None,
    ) -> None:
        if existing_record is None:
            return
        previous_experience = existing_record.get("experience")
        if not isinstance(previous_experience, str) or not previous_experience.strip():
            return
        current_text = (current_experience or "").strip()
        if not current_text:
            return
        previous_sample = previous_experience[:3000]
        current_sample = current_text[:3000]
        ratio = fuzz.ratio(previous_sample, current_sample) / 100.0
        token_ratio = fuzz.token_set_ratio(previous_sample, current_sample) / 100.0
        partial_ratio = fuzz.partial_ratio(previous_sample, current_sample) / 100.0
        combined_ratio = max(ratio, token_ratio * 0.92, partial_ratio * 0.85)
        if combined_ratio < 0.55:
            self._log(
                "candidate_fingerprint_collision_warning",
                "Existing record shares fingerprint but experience differs significantly",
                candidate_name=candidate_name,
                candidate_fingerprint=candidate_fingerprint,
                similarity_ratio=round(ratio, 4),
                token_similarity_ratio=round(token_ratio, 4),
                partial_similarity_ratio=round(partial_ratio, 4),
                combined_similarity_ratio=round(combined_ratio, 4),
            )

    def _log_candidate_processing(
        self,
        *,
        candidate_name: str | None,
        search_keyword: str,
        analysis_status: str,
        message_send_result: MessageSendResult,
    ) -> None:
        LOGGER.info(
            "Candidate detail processed",
            extra={
                "event": "candidate_detail_processed",
                "context": {
                    "candidate_name": candidate_name,
                    "search_keyword": search_keyword,
                    "analysis_status": analysis_status,
                    "message_send_success": message_send_result.success,
                    "message_send_attempts": message_send_result.attempts,
                    "message_send_reason": message_send_result.reason,
                    "message_screenshot_path": message_send_result.screenshot_path,
                },
            },
        )

    def _tap_back_button_or_fallback(self) -> None:
        root = ElementTree.fromstring(self.device.dump_hierarchy_text())
        center = find_back_button(root)
        if center is not None:
            self._log(
                "page_back_tap",
                "Tapped visible back button",
                tap_x=center[0],
                tap_y=center[1],
            )
            self.device.tap(*center)
            return
        self._log(
            "page_back_fallback",
            "Falling back to device back action",
        )
        self.device.press_back()

    def _extract_role_keyword(self, seed: SearchTaskSeed) -> str:
        return seed.role_keyword

    @staticmethod
    def _slugify(value: str) -> str:
        cleaned = re.sub(r"[^\w\u4e00-\u9fff-]+", "_", value.strip())
        cleaned = cleaned.strip("_")
        return cleaned or "unknown_candidate"

    def _build_report(
        self,
        *,
        seed: SearchTaskSeed,
        screened_candidates: int,
        junior_college_rejected: int,
        matched_candidates: list[str],
        end_reason: str,
        iterations: list[FilterIteration],
        candidate_outcomes: list[CandidateProcessOutcome],
    ) -> FilterRunReport:
        return FilterRunReport(
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            screened_candidates=screened_candidates,
            junior_college_rejected=junior_college_rejected,
            matched=bool(matched_candidates),
            matched_candidate=matched_candidates[0] if matched_candidates else None,
            matched_candidates=matched_candidates,
            profiles_collected=sum(
                1 for outcome in candidate_outcomes if outcome.profile_collection_completed
            ),
            end_reason=end_reason,
            iterations=iterations,
            candidate_outcomes=candidate_outcomes,
        )

    def _wait(self, seconds: float | None = None) -> None:
        base_seconds = random.uniform(self.settle_seconds, 5.0)
        target_seconds = base_seconds if seconds is None else max(base_seconds, seconds)
        time.sleep(target_seconds)

    @staticmethod
    def _log(event: str, message: str, **context: object) -> None:
        LOGGER.info(
            message,
            extra={
                "event": event,
                "context": context,
            },
        )
