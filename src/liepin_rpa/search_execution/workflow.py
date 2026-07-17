from __future__ import annotations

import json
import random
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

from liepin_rpa.app_runtime import (
    ensure_liepin_started,
    resolve_liepin_main_activity,
    resolve_liepin_package,
)
from liepin_rpa.config import get_settings
from liepin_rpa.device.client import DeviceConnectionError
from liepin_rpa.logging_utils import get_logger
from liepin_rpa.page_detection.enums import PageState
from liepin_rpa.page_detection.runtime import build_page_detector
from liepin_rpa.search_execution.search_pages import SearchFlowError
from liepin_rpa.search_execution.result_filter import (
    CandidateProcessOutcome,
    FilterRunReport,
    ResultPageFilter,
)
from liepin_rpa.task_generation.models import SearchTaskSeed
from liepin_rpa.ui_controls import find_back_button

LOGGER = get_logger(__name__)


@dataclass(slots=True)
class WorkflowCheckpointStore:
    path: Path

    def load_completed_keys(self) -> set[str]:
        payload = self._load_payload()
        raw_items = payload.get("completed_keyword_keys")
        if not isinstance(raw_items, list):
            return set()
        return {str(item) for item in raw_items if isinstance(item, str) and item.strip()}

    def load_in_progress_keyword(self) -> dict[str, object] | None:
        payload = self._load_payload()
        item = payload.get("in_progress_keyword")
        return dict(item) if isinstance(item, dict) else None

    def mark_started(
        self,
        *,
        seed: SearchTaskSeed,
        index: int,
        total_keywords: int,
    ) -> None:
        payload = self._load_payload()
        payload["in_progress_keyword"] = {
            "key": build_seed_key(seed),
            "index": index,
            "total_keywords": total_keywords,
            "brand_keyword": seed.brand_keyword,
            "role_keyword": seed.role_keyword,
            "started_at": int(time.time()),
        }
        payload["updated_at"] = int(time.time())
        self._save_payload(payload)

    def mark_completed(
        self,
        *,
        seed: SearchTaskSeed,
        index: int,
        total_keywords: int,
        end_reason: str,
    ) -> None:
        payload = self._load_payload()
        completed = {
            str(item)
            for item in payload.get("completed_keyword_keys", [])
            if isinstance(item, str) and item.strip()
        }
        completed.add(build_seed_key(seed))
        payload["completed_keyword_keys"] = sorted(completed)
        payload["last_completed_keyword"] = {
            "index": index,
            "total_keywords": total_keywords,
            "brand_keyword": seed.brand_keyword,
            "role_keyword": seed.role_keyword,
            "end_reason": end_reason,
            "completed_at": int(time.time()),
        }
        payload.pop("in_progress_keyword", None)
        payload["updated_at"] = int(time.time())
        self._save_payload(payload)

    def reset(self) -> None:
        if self.path.exists():
            self.path.unlink()

    def _load_payload(self) -> dict[str, object]:
        if not self.path.exists():
            return {}
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save_payload(self, payload: dict[str, object]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


def build_seed_key(seed: SearchTaskSeed) -> str:
    return f"{seed.brand_keyword}|||{seed.role_keyword}"


@dataclass(slots=True)
class KeywordWorkflowResult:
    index: int
    brand_keyword: str
    role_keyword: str
    matched: bool
    profiles_collected: int
    end_reason: str
    report_path: str
    candidate_outcomes: list[CandidateProcessOutcome]
    error_message: str | None = None


@dataclass(slots=True)
class SearchWorkflowReport:
    total_keywords: int
    matched_keywords: int
    profiles_collected: int
    keyword_runs: list[KeywordWorkflowResult]
    candidate_outcomes: list[CandidateProcessOutcome]
    report_path: str


class SearchWorkflowRunner:
    def __init__(
        self,
        result_filter: ResultPageFilter,
        *,
        checkpoint_store: WorkflowCheckpointStore | None = None,
    ) -> None:
        self.result_filter = result_filter
        self.checkpoint_store = checkpoint_store

    def run(self, seeds: list[SearchTaskSeed], output_dir=None) -> SearchWorkflowReport:
        settings = get_settings()
        keyword_runs: list[KeywordWorkflowResult] = []
        candidate_outcomes: list[CandidateProcessOutcome] = []
        total_profiles_collected = 0
        consecutive_errors = 0

        total_keywords = len(seeds)
        for index, seed in enumerate(seeds, start=1):
            self._throttle_before_keyword(index=index, total_keywords=total_keywords)
            try:
                self._heartbeat_if_needed(index=index)
                self._ensure_target_app_foreground()
            except DeviceConnectionError as exc:
                consecutive_errors += 1
                error_message = str(exc)
                LOGGER.warning(
                    "Keyword workflow run skipped because device heartbeat failed",
                    extra={
                        "event": "keyword_device_heartbeat_failed",
                        "context": {
                            "index": index,
                            "total_keywords": total_keywords,
                            "brand_keyword": seed.brand_keyword,
                            "role_keyword": seed.role_keyword,
                            "error_message": error_message,
                        },
                    },
                )
                failed_result = self._build_failed_keyword_result(index, seed, error_message)
                keyword_runs.append(failed_result)
                self._mark_checkpoint(
                    seed=seed,
                    index=index,
                    total_keywords=total_keywords,
                    end_reason=failed_result.end_reason,
                )
                if consecutive_errors >= max(1, settings.workflow_max_consecutive_keyword_errors):
                    LOGGER.error(
                        "Stopping workflow because consecutive device errors exceeded threshold",
                        extra={
                            "event": "keyword_device_error_circuit_breaker",
                            "context": {
                                "consecutive_errors": consecutive_errors,
                                "threshold": settings.workflow_max_consecutive_keyword_errors,
                                "index": index,
                                "total_keywords": total_keywords,
                            },
                        },
                    )
                    self._print_progress(
                        f"连续 {consecutive_errors} 个关键词设备不可用，已触发熔断保护，停止本轮任务。"
                    )
                    break
                continue
            self._mark_started_checkpoint(
                seed=seed,
                index=index,
                total_keywords=total_keywords,
            )
            LOGGER.info(
                "Starting keyword workflow run",
                extra={
                    "event": "keyword_run_start",
                    "context": {
                        "index": index,
                        "total_keywords": total_keywords,
                        "brand_keyword": seed.brand_keyword,
                        "role_keyword": seed.role_keyword,
                    },
                },
            )
            self._print_progress(
                f"[{index}/{total_keywords}] 开始执行：{seed.brand_keyword} | {seed.role_keyword}"
            )
            run_report: FilterRunReport | None = None
            error_message: str | None = None
            for attempt in range(1, 3):
                try:
                    run_report = self.result_filter.search_and_filter_seed(seed=seed, output_dir=output_dir)
                    break
                except Exception as exc:
                    error_message = str(exc)
                    if attempt >= 2:
                        break
                    LOGGER.warning(
                        "Keyword workflow run failed and will be retried after page recovery",
                        extra={
                            "event": "keyword_run_retry",
                            "context": {
                                "index": index,
                                "total_keywords": total_keywords,
                                "brand_keyword": seed.brand_keyword,
                                "role_keyword": seed.role_keyword,
                                "attempt": attempt,
                                "error_message": error_message,
                            },
                        },
                    )
                    self._print_progress(
                        f"[{index}/{total_keywords}] 当前关键词异常，正在返回搜索页并重试：{seed.brand_keyword} | {seed.role_keyword}"
                    )
                    try:
                        recovered = self._recover_to_search_surface_for_retry(seed=seed)
                    except DeviceConnectionError as recover_exc:
                        error_message = str(recover_exc)
                        recovered = False
                    if not recovered:
                        break
            if run_report is None:
                consecutive_errors += 1
                LOGGER.warning(
                    "Keyword workflow run failed and will be skipped",
                    extra={
                        "event": "keyword_run_failed",
                        "context": {
                            "index": index,
                            "total_keywords": total_keywords,
                            "brand_keyword": seed.brand_keyword,
                            "role_keyword": seed.role_keyword,
                            "error_message": error_message,
                        },
                    },
                )
                failed_result = self._build_failed_keyword_result(
                    index,
                    seed,
                    error_message or "unknown_error",
                )
                keyword_runs.append(failed_result)
                self._mark_checkpoint(
                    seed=seed,
                    index=index,
                    total_keywords=total_keywords,
                    end_reason=failed_result.end_reason,
                )
                if consecutive_errors >= max(1, settings.workflow_max_consecutive_keyword_errors):
                    LOGGER.error(
                        "Stopping workflow because consecutive keyword errors exceeded threshold",
                        extra={
                            "event": "keyword_error_circuit_breaker",
                            "context": {
                                "consecutive_errors": consecutive_errors,
                                "threshold": settings.workflow_max_consecutive_keyword_errors,
                                "index": index,
                                "total_keywords": total_keywords,
                            },
                        },
                    )
                    self._print_progress(
                        f"连续 {consecutive_errors} 个关键词失败，已触发熔断保护，停止本轮任务。"
                    )
                    break
                continue
            consecutive_errors = 0
            keyword_runs.append(self._build_keyword_result(index, run_report))
            self._mark_checkpoint(
                seed=seed,
                index=index,
                total_keywords=total_keywords,
                end_reason=run_report.end_reason,
            )
            candidate_outcomes.extend(run_report.candidate_outcomes)
            total_profiles_collected += run_report.profiles_collected
            LOGGER.info(
                "Completed keyword workflow run",
                extra={
                    "event": "keyword_run_complete",
                    "context": {
                        "index": index,
                        "total_keywords": total_keywords,
                        "brand_keyword": seed.brand_keyword,
                        "role_keyword": seed.role_keyword,
                        "matched": run_report.matched,
                        "profiles_collected": run_report.profiles_collected,
                        "end_reason": run_report.end_reason,
                    },
                },
            )
        return SearchWorkflowReport(
            total_keywords=total_keywords,
            matched_keywords=sum(1 for item in keyword_runs if item.matched),
            profiles_collected=total_profiles_collected,
            keyword_runs=keyword_runs,
            candidate_outcomes=candidate_outcomes,
            report_path="",
        )

    @staticmethod
    def _build_keyword_result(
        index: int,
        run_report: FilterRunReport,
    ) -> KeywordWorkflowResult:
        return KeywordWorkflowResult(
            index=index,
            brand_keyword=run_report.brand_keyword,
            role_keyword=run_report.role_keyword,
            matched=run_report.matched,
            profiles_collected=run_report.profiles_collected,
            end_reason=run_report.end_reason,
            report_path="",
            candidate_outcomes=run_report.candidate_outcomes,
            error_message=None,
        )

    @staticmethod
    def _build_failed_keyword_result(
        index: int,
        seed: SearchTaskSeed,
        error_message: str,
    ) -> KeywordWorkflowResult:
        return KeywordWorkflowResult(
            index=index,
            brand_keyword=seed.brand_keyword,
            role_keyword=seed.role_keyword,
            matched=False,
            profiles_collected=0,
            end_reason="keyword_error",
            report_path="",
            candidate_outcomes=[],
            error_message=error_message,
        )

    @staticmethod
    def _print_progress(message: str) -> None:
        print(message, flush=True)
        sys.stdout.flush()

    def _throttle_before_keyword(self, *, index: int, total_keywords: int) -> None:
        if index <= 1:
            return
        settings = get_settings()
        min_delay = max(0.0, settings.workflow_min_keyword_delay_seconds)
        max_delay = max(min_delay, settings.workflow_max_keyword_delay_seconds)
        if max_delay <= 0:
            return
        delay = random.uniform(min_delay, max_delay)
        self._print_progress(
            f"[{index}/{total_keywords}] 关键词间隔等待 {delay:.1f} 秒，降低高频触发风险"
        )
        time.sleep(delay)

    def _heartbeat_if_needed(self, *, index: int) -> None:
        settings = get_settings()
        interval = max(0, settings.workflow_device_heartbeat_interval)
        if interval <= 0 or index <= 1 or (index - 1) % interval != 0:
            return
        self.result_filter.device.ensure_healthy()

    def _ensure_target_app_foreground(self) -> None:
        settings = get_settings()
        ensure_liepin_started(
            self.result_filter.device,
            resolve_liepin_package(settings.liepin_package),
            resolve_liepin_main_activity(settings.liepin_main_activity),
            settle_seconds=3.0,
        )

    def _mark_checkpoint(
        self,
        *,
        seed: SearchTaskSeed,
        index: int,
        total_keywords: int,
        end_reason: str,
    ) -> None:
        if self.checkpoint_store is None:
            return
        self.checkpoint_store.mark_completed(
            seed=seed,
            index=index,
            total_keywords=total_keywords,
            end_reason=end_reason,
        )

    def _mark_started_checkpoint(
        self,
        *,
        seed: SearchTaskSeed,
        index: int,
        total_keywords: int,
    ) -> None:
        if self.checkpoint_store is None:
            return
        self.checkpoint_store.mark_started(
            seed=seed,
            index=index,
            total_keywords=total_keywords,
        )

    def _recover_to_search_surface_for_retry(self, *, seed: SearchTaskSeed) -> bool:
        detector = build_page_detector()
        try:
            self._ensure_target_app_foreground()
        except Exception:
            pass
        for attempt in range(1, 9):
            try:
                hierarchy_text = self.result_filter.device.dump_hierarchy_text()
                root = ElementTree.fromstring(hierarchy_text)
                detection = detector.detect_from_xml_text(hierarchy_text)
                page_state = detection.state
            except Exception:
                root = None
                page_state = PageState.UNKNOWN

            LOGGER.info(
                "Recovering page state before retrying keyword",
                extra={
                    "event": "keyword_retry_recovery_probe",
                    "context": {
                        "brand_keyword": seed.brand_keyword,
                        "role_keyword": seed.role_keyword,
                        "attempt": attempt,
                        "page_state": page_state.value,
                    },
                },
            )
            if page_state in (
                PageState.HOME_RECOMMEND,
                PageState.SEARCH_ENTRY,
                PageState.SEARCH_CONDITION,
            ):
                return True
            if root is not None:
                if self.result_filter.navigator.dismiss_interruption_if_present(root):
                    time.sleep(random.uniform(3.0, 5.0))
                    continue
                back_center = find_back_button(root)
                if back_center is not None:
                    try:
                        self.result_filter.device.tap(*back_center)
                    except DeviceConnectionError:
                        return False
                    except Exception:
                        return False
                    time.sleep(random.uniform(3.0, 5.0))
                    continue
            try:
                self.result_filter.device.press_back()
            except DeviceConnectionError:
                return False
            except Exception:
                return False
            time.sleep(random.uniform(3.0, 5.0))

        try:
            self.result_filter.navigator._reset_to_search_surface()
            return True
        except Exception:
            return False
