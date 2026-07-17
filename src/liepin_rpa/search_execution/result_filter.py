from __future__ import annotations

import json
import random
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree

from rapidfuzz import fuzz
from liepin_rpa.coze_workflow import CozeMatchResult, analyze_resume_record_with_coze
from liepin_rpa.config import get_settings
from liepin_rpa.db import (
    build_candidate_fingerprint,
    build_candidate_experience_text,
    build_experience_hash,
    get_candidate_record_by_fingerprint,
    get_analysis_cache_record,
    record_candidate_search_hit,
    upsert_analysis_cache_record,
    upsert_contacted_candidate_record,
    was_contacted_within_days,
)
from liepin_rpa.device.client import DeviceClient
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector
from liepin_rpa.profile_collection import CandidateProfileCollector
from liepin_rpa.profile_collection.collector import ProfileCollectionRecoverableError
from liepin_rpa.profile_collection.resume_schema import sanitize_candidate_name
from liepin_rpa.search_execution.job_selection import choose_job_before_chat_if_present
from liepin_rpa.search_execution.search_pages import SearchFlowError, SearchPageNavigator
from liepin_rpa.task_generation.models import SearchTaskSeed
from liepin_rpa.ui_controls import (
    any_label,
    any_label_contains,
    find_back_button,
    find_chat_input_center,
    find_chat_send_button,
    find_profile_communication_action,
    parse_bounds,
)

RESULT_SWIPE_START_X = 540
RESULT_SWIPE_START_Y = 1960
RESULT_SWIPE_END_X = 540
RESULT_SWIPE_END_Y = 720
RESULT_SWIPE_DURATION = 0.30
CHAT_SEND_MAX_ATTEMPTS = 2
RECENT_CONTACT_SKIP_DAYS = 7
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


@dataclass(slots=True)
class CandidateCard:
    fingerprint: str
    raw_text: str
    bounds: str


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
    ) -> None:
        self.device = device
        self.detector = detector or build_page_detector()
        self.settle_seconds = settle_seconds
        self.max_stagnant_swipes = max_stagnant_swipes
        self.write_to_db = write_to_db
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
        self._log(
            "filter_seed_start",
            "Starting result filtering for keyword",
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
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
                if self.is_rejected_education_candidate(card.raw_text):
                    if card.fingerprint not in rejected_fingerprints:
                        rejected_fingerprints.add(card.fingerprint)
                        junior_college_rejected += 1
                    continue
                if self.is_exact_candidate_match(card.raw_text, seed):
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
                    if candidate_age is not None and not self.is_candidate_age_allowed(candidate_age):
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
                        "result_candidate_keyword_match",
                        "Found candidate card matching exact keyword on result page",
                        brand_keyword=seed.brand_keyword,
                        role_keyword=seed.role_keyword,
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
                candidate_outcome = self._collect_profile_for_candidate(seed=seed, card=matched_card)
                collected_profile_fingerprints.add(matched_card.fingerprint)
                matched_candidates.append(matched_card.raw_text)
                candidate_outcomes.append(candidate_outcome)
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
        head = normalized[:160]
        return f"{first_line}|{head}"

    @staticmethod
    def is_exact_candidate_match(raw_text: str, seed: SearchTaskSeed) -> bool:
        normalized = " ".join(raw_text.split())
        return seed.brand_keyword in normalized and seed.role_keyword in normalized

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

    @staticmethod
    def is_candidate_age_allowed(age: int) -> bool:
        return MIN_CANDIDATE_AGE <= age <= MAX_CANDIDATE_AGE

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
        if self._is_intention_communication_page(detail_root):
            return self._handle_intention_communication_candidate(
                seed=seed,
                card=card,
                detail_root=detail_root,
            )
        communication_action = find_profile_communication_action(detail_root)
        if communication_action is None:
            return self._handle_unsupported_communication_candidate(
                seed=seed,
                card=card,
                reason="candidate_detail_has_no_immediate_or_continue_communication_action",
            )
        communication_label = communication_action[0] if communication_action is not None else None

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
            fallback_action = find_profile_communication_action(fallback_root)
            if fallback_action is None:
                return self._handle_unsupported_communication_candidate(
                    seed=seed,
                    card=card,
                    reason="candidate_detail_runtime_error_without_supported_communication_action",
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
        if candidate_name is None:
            candidate_name = self._extract_candidate_name_from_card(card.raw_text)
        search_keyword = f"{seed.brand_keyword}，{seed.role_keyword}"
        candidate_fingerprint = build_candidate_fingerprint(
            candidate_name=candidate_name,
            structured_resume=structured_resume,
            experience=experience,
        )
        experience_hash = build_experience_hash(experience)
        existing_record = (
            get_candidate_record_by_fingerprint(settings, candidate_fingerprint)
            if self.write_to_db
            else None
        )
        analysis_cache_record = (
            get_analysis_cache_record(
                settings,
                candidate_fingerprint=candidate_fingerprint,
                experience_hash=experience_hash,
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
        analysis_result = self._coze_result_from_cache_record(analysis_cache_record)
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
                record_candidate_search_hit(
                    settings,
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
                analysis_status = str(analysis_cache_record.get("analysis_status") or "未分析")
                coze_matched = analysis_result.matched
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
                resume_record = self._build_coze_resume_record(
                    candidate_name=candidate_name,
                    search_keyword=search_keyword,
                    experience=experience,
                )
                self._log(
                    "candidate_coze_analysis_start",
                    "Submitting candidate record to Coze",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                )
                coze_result = analyze_resume_record_with_coze(
                    settings,
                    resume_record=resume_record,
                )
                analysis_result = coze_result
                self._log(
                    "candidate_coze_analysis_complete",
                    "Received Coze analysis result",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    matched=coze_result.matched,
                    match_result=coze_result.match_result,
                )
                analysis_status = "匹配" if coze_result.matched else "不匹配"
                coze_matched = coze_result.matched
                if self.write_to_db:
                    upsert_analysis_cache_record(
                        settings,
                        candidate_fingerprint=candidate_fingerprint,
                        experience_hash=experience_hash,
                        analysis_status=analysis_status,
                        match_result=coze_result.match_result,
                        match_score=coze_result.match_score,
                        score_breakdown=coze_result.score_breakdown,
                        analysis_reason=coze_result.analysis_reason,
                        evidence_summary=coze_result.evidence_summary,
                    )

            if coze_matched and communication_label is not None:
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

            hit_status = self._derive_hit_status(
                analysis_status=analysis_status,
                message_send_result=message_send_result,
                continue_without_db_record=continue_without_db_record,
            )
            if self.write_to_db:
                record_candidate_search_hit(
                    settings,
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
                self._log(
                    "candidate_db_write_start",
                    "Writing successful contacted candidate into database",
                    candidate_name=candidate_name,
                    candidate_fingerprint=candidate_fingerprint,
                    communication_label=communication_label,
                )
                upsert_contacted_candidate_record(
                    settings,
                    candidate_fingerprint=candidate_fingerprint,
                    candidate_name=candidate_name,
                    search_keyword=search_keyword,
                    experience=experience,
                    match_score=analysis_result.match_score if analysis_result is not None else None,
                    analysis_status=analysis_status,
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
            evidence_summary=analysis_result.evidence_summary if analysis_result is not None else [],
            hit_status=hit_status,
            continue_without_db_record=continue_without_db_record,
            message_send_success=message_send_result.success,
            message_send_reason=message_send_result.reason,
            message_send_attempts=message_send_result.attempts,
            message_screenshot_path=message_send_result.screenshot_path,
            candidate_age=candidate_age,
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
        search_keyword = f"{seed.brand_keyword}，{seed.role_keyword}"
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
        search_keyword = f"{seed.brand_keyword}，{seed.role_keyword}"
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
        search_keyword = f"{seed.brand_keyword}，{seed.role_keyword}"
        screenshot_path = self._capture_debug_screenshot(
            status="profile_collection_skipped",
            seed=seed,
            candidate_name=candidate_name,
        )
        if self.write_to_db:
            record_candidate_search_hit(
                get_settings(),
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
        self.device.press_back()
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
            analysis_reason=f"候选人详情采集过程中设备滑动失败，已跳过当前候选人。{reason}",
            evidence_summary=[],
            hit_status="profile_collection_skipped",
            continue_without_db_record=False,
            message_send_success=False,
            message_send_reason="profile_collection_skipped",
            message_send_attempts=0,
            message_screenshot_path=screenshot_path,
        )

    @staticmethod
    def _coze_result_from_cache_record(record: dict[str, object] | None) -> CozeMatchResult | None:
        if record is None:
            return None
        match_result = ResultPageFilter._normalize_optional_str(record.get("match_result"))
        analysis_status = ResultPageFilter._normalize_optional_str(record.get("analysis_status"))
        return CozeMatchResult(
            matched=bool(
                analysis_status == "匹配"
                or match_result == "relevant"
                or bool(record.get("beauty_relevant") is True)
            ),
            match_result=match_result,
            match_score=ResultPageFilter._normalize_optional_int(record.get("match_score")),
            score_breakdown=ResultPageFilter._parse_score_breakdown(record.get("score_breakdown_json")),
            analysis_reason=ResultPageFilter._normalize_optional_str(record.get("analysis_reason")),
            evidence_summary=ResultPageFilter._parse_string_list(record.get("evidence_summary_json")),
            raw_data="",
            parsed_payload={},
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
                result = MessageSendResult(
                    success=True,
                    attempts=1,
                    reason="immediate_job_confirmed",
                    screenshot_path=self._capture_message_result_screenshot(
                        candidate_name=candidate_name,
                        status="success",
                    ),
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

    @staticmethod
    def _build_coze_resume_record(
        *,
        candidate_name: str | None,
        search_keyword: str,
        experience: str | None,
    ) -> dict[str, object]:
        return {
            "id": 0,
            "candidate_name": candidate_name or "",
            "source_platform": "liepin",
            "search_keyword": search_keyword,
            "experience": experience or "",
            "analysis_status": "未执行",
        }

    @staticmethod
    def _derive_hit_status(
        *,
        analysis_status: str,
        message_send_result: MessageSendResult,
        continue_without_db_record: bool,
    ) -> str:
        if message_send_result.success:
            if message_send_result.reason == "immediate_job_confirmed":
                return "immediate_job_confirmed"
            return "sent"
        if message_send_result.reason == "skipped_recent_continue":
            return "skipped_recent_continue"
        if continue_without_db_record:
            return "continue_without_db_record"
        if analysis_status == "不匹配":
            return "not_relevant"
        if analysis_status == "匹配":
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
            profiles_collected=len(matched_candidates),
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
