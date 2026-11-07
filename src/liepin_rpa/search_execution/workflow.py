from __future__ import annotations

import json
import os
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

    def load_completed_keys(self, *, catalog_key: str) -> set[str]:
        payload = self._load_payload()
        stored_catalog_key = payload.get("catalog_key")
        if stored_catalog_key and stored_catalog_key != catalog_key:
            return set()
        raw_items = payload.get("completed_keyword_keys")
        if not isinstance(raw_items, list):
            return set()
        return {str(item) for item in raw_items if isinstance(item, str) and item.strip()}

    def load_in_progress_keyword(self, *, catalog_key: str) -> dict[str, object] | None:
        payload = self._load_payload()
        stored_catalog_key = payload.get("catalog_key")
        if stored_catalog_key and stored_catalog_key != catalog_key:
            return None
        item = payload.get("in_progress_keyword")
        return dict(item) if isinstance(item, dict) else None

    def mark_started(
        self,
        *,
        seed: SearchTaskSeed,
        index: int,
        total_keywords: int,
        catalog_key: str,
    ) -> None:
        payload = self._load_payload()
        self._adopt_or_reset_catalog(payload, catalog_key)
        payload["in_progress_keyword"] = {
            "key": build_seed_key(seed),
            "index": index,
            "total_keywords": total_keywords,
            "brand_keyword": seed.brand_keyword,
            "role_keyword": seed.role_keyword,
            "query_text": seed.effective_query,
            "strategy": seed.strategy,
            "search_pack_id": seed.search_pack_id,
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
        catalog_key: str,
    ) -> None:
        payload = self._load_payload()
        self._adopt_or_reset_catalog(payload, catalog_key)
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
            "query_text": seed.effective_query,
            "strategy": seed.strategy,
            "search_pack_id": seed.search_pack_id,
            "end_reason": end_reason,
            "completed_at": int(time.time()),
        }
        payload.pop("in_progress_keyword", None)
        payload["updated_at"] = int(time.time())
        self._save_payload(payload)

    def mark_failed(
        self,
        *,
        seed: SearchTaskSeed,
        index: int,
        total_keywords: int,
        error_message: str,
        catalog_key: str,
    ) -> None:
        payload = self._load_payload()
        self._adopt_or_reset_catalog(payload, catalog_key)
        key = build_seed_key(seed)
        failures = payload.get("failed_keyword_attempts")
        failures = dict(failures) if isinstance(failures, dict) else {}
        previous = failures.get(key)
        previous_count = int(previous.get("attempts") or 0) if isinstance(previous, dict) else 0
        failures[key] = {
            "attempts": previous_count + 1,
            "index": index,
            "total_keywords": total_keywords,
            "brand_keyword": seed.brand_keyword,
            "role_keyword": seed.role_keyword,
            "query_text": seed.effective_query,
            "strategy": seed.strategy,
            "search_pack_id": seed.search_pack_id,
            "error_message": error_message[:500],
            "failed_at": int(time.time()),
        }
        payload["failed_keyword_attempts"] = failures
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
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            return dict(payload) if isinstance(payload, dict) else {}
        except (OSError, json.JSONDecodeError):
            corrupt_path = self.path.with_suffix(self.path.suffix + f".corrupt.{int(time.time())}")
            try:
                os.replace(self.path, corrupt_path)
            except OSError:
                pass
            return {}

    def _save_payload(self, payload: dict[str, object]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        os.replace(temporary_path, self.path)

    @staticmethod
    def _adopt_or_reset_catalog(payload: dict[str, object], catalog_key: str) -> None:
        stored_catalog_key = payload.get("catalog_key")
        if stored_catalog_key and stored_catalog_key != catalog_key:
            payload.clear()
        payload["catalog_key"] = catalog_key


def build_seed_key(seed: SearchTaskSeed) -> str:
    if seed.strategy == "legacy" and not seed.query_text:
        return f"{seed.brand_keyword}|||{seed.role_keyword}"
    return "|||".join(
        (seed.strategy, seed.job_profile_id or "", seed.search_pack_id or "", seed.effective_query)
    )


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
        checkpoint_catalog_key: str = "",
    ) -> None:
        self.result_filter = result_filter
        self.checkpoint_store = checkpoint_store
        self.checkpoint_catalog_key = checkpoint_catalog_key

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
                self._mark_checkpoint_failure(
                    seed=seed,
                    index=index,
                    total_keywords=total_keywords,
                    error_message=error_message,
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
                        "query_text": seed.effective_query,
                        "strategy": seed.strategy,
                        "search_pack_id": seed.search_pack_id,
                    },
                },
            )
            self._print_progress(
                f"[{index}/{total_keywords}] 开始执行：{seed.effective_query}"
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
                self._mark_checkpoint_failure(
                    seed=seed,
                    index=index,
                    total_keywords=total_keywords,
                    error_message=error_message or "unknown_error",
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
                        "query_text": seed.effective_query,
                        "strategy": seed.strategy,
                        "search_pack_id": seed.search_pack_id,
                        "matched": run_report.matched,
                        "screened_candidates": run_report.screened_candidates,
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

    def _ensure_target_app_foreground(self, *, force_restart: bool = False) -> None:
        settings = get_settings()
        ensure_liepin_started(
            self.result_filter.device,
            resolve_liepin_package(settings.liepin_package),
            resolve_liepin_main_activity(settings.liepin_main_activity),
            settle_seconds=3.0,
            force_restart=force_restart,
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
            catalog_key=self.checkpoint_catalog_key,
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
            catalog_key=self.checkpoint_catalog_key,
        )

    def _mark_checkpoint_failure(
        self,
        *,
        seed: SearchTaskSeed,
        index: int,
        total_keywords: int,
        error_message: str,
    ) -> None:
        if self.checkpoint_store is None:
            return
        self.checkpoint_store.mark_failed(
            seed=seed,
            index=index,
            total_keywords=total_keywords,
            error_message=error_message,
            catalog_key=self.checkpoint_catalog_key,
        )

    def _recover_to_search_surface_for_retry(self, *, seed: SearchTaskSeed) -> bool:
        settings = get_settings()
        target_package = resolve_liepin_package(settings.liepin_package)
        detector = build_page_detector()
        try:
            self._ensure_target_app_foreground(force_restart=True)
        except Exception:
            pass
        for attempt in range(1, 9):
            try:
                current_app = self.result_filter.device.current_app()
            except DeviceConnectionError:
                return False
            except Exception:
                current_app = {}
            current_package = str(current_app.get("package") or "")
            if current_package and current_package != target_package:
                LOGGER.info(
                    "Target app is not foreground during keyword retry recovery, restarting it",
                    extra={
                        "event": "keyword_retry_foreground_recovery",
                        "context": {
                            "brand_keyword": seed.brand_keyword,
                            "role_keyword": seed.role_keyword,
                            "attempt": attempt,
                            "current_package": current_package,
                            "target_package": target_package,
                        },
                    },
                )
                try:
                    self._ensure_target_app_foreground(force_restart=True)
                except DeviceConnectionError:
                    return False
                except Exception:
                    pass
                time.sleep(random.uniform(3.0, 5.0))
                continue

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
            if page_state in (PageState.RISK_CONTROL, PageState.NETWORK_ERROR):
                LOGGER.info(
                    "Restarting target app after recoverable blocking page during keyword retry",
                    extra={
                        "event": "keyword_retry_blocking_page_restart",
                        "context": {
                            "brand_keyword": seed.brand_keyword,
                            "role_keyword": seed.role_keyword,
                            "attempt": attempt,
                            "page_state": page_state.value,
                        },
                    },
                )
                try:
                    self._ensure_target_app_foreground(force_restart=True)
                except DeviceConnectionError:
                    return False
                except Exception:
                    pass
                time.sleep(random.uniform(3.0, 5.0))
                continue
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
