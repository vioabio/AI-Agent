from __future__ import annotations

import csv
import json
import time
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta, timezone
from pathlib import Path

from sqlalchemy import text

from liepin_rpa.communication_status_policy import SENT_HIT_STATUSES
from liepin_rpa.config import Settings
from liepin_rpa.db import ensure_resume_experience_schema, get_engine
from liepin_rpa.reporting_rules import (
    ACTIONABLE_NEEDS_REVIEW_MIN_SCORE,
    is_actionable_needs_review_result,
    is_not_relevant_result,
)

EXPORT_COLUMNS = [
    "关键词",
    "品牌",
    "岗位",
    "候选人",
    "候选人指纹",
    "命中状态",
    "动作结果",
    "是否发送",
    "Coze状态",
    "Coze结果",
    "Coze分数",
    "未发送/跳过原因",
    "Coze原因",
    "证据摘要",
    "简历内容",
    "命中时间",
    "分析时间",
    "联系记录ID",
]

LOCAL_TIMEZONE = timezone(timedelta(hours=8))


@dataclass(slots=True)
class ExportScope:
    checkpoint_file: Path | None
    window_start_at: datetime | None
    window_end_at: datetime | None
    source_label: str


@dataclass(slots=True)
class ExportResult:
    output_file: Path
    row_count: int
    total_keywords: int
    candidate_rows: int
    sent_count: int
    needs_review_count: int
    no_candidate_keywords: int
    window_start_at: datetime | None
    window_end_at: datetime | None


def export_search_results(
    settings: Settings,
    *,
    scope: ExportScope,
    output_file: Path | None = None,
) -> ExportResult:
    ensure_resume_experience_schema(settings)
    keywords = load_executed_keywords_from_logs(
        settings,
        window_start_at=scope.window_start_at,
        window_end_at=scope.window_end_at,
    )
    if not keywords:
        keywords = load_checkpoint_keywords(scope.checkpoint_file) if scope.checkpoint_file else []
    keyword_set = {item["search_keyword"] for item in keywords}
    rows = _load_hit_rows(
        settings,
        window_start_at=scope.window_start_at,
        window_end_at=scope.window_end_at,
    )
    if keyword_set:
        rows = [row for row in rows if str(row.get("search_keyword") or "") in keyword_set]

    export_rows = [_build_export_row(row) for row in rows]
    seen_keywords = {str(row.get("search_keyword") or "") for row in rows}
    for keyword in keywords:
        if keyword["search_keyword"] in seen_keywords:
            continue
        export_rows.append(_build_no_candidate_row(keyword))

    export_rows.sort(
        key=lambda item: (
            str(item.get("命中时间") or "9999-99-99 99:99:99"),
            str(item.get("关键词") or ""),
            str(item.get("候选人") or ""),
        )
    )
    resolved_output = output_file or _default_output_path(settings, scope.source_label)
    write_result_csv(export_rows, resolved_output)
    return ExportResult(
        output_file=resolved_output,
        row_count=len(export_rows),
        total_keywords=len(keywords) if keywords else len({row["关键词"] for row in export_rows}),
        candidate_rows=sum(1 for row in export_rows if row["候选人"]),
        sent_count=sum(1 for row in export_rows if row["是否发送"] == "是"),
        needs_review_count=sum(
            1 for row in export_rows if _is_counted_needs_review(row["Coze结果"], row["Coze分数"])
        ),
        no_candidate_keywords=sum(
            1 for row in export_rows if row["命中状态"] == "no_candidate_in_batch"
        ),
        window_start_at=scope.window_start_at,
        window_end_at=scope.window_end_at,
    )


def load_checkpoint_keywords(checkpoint_file: Path | None) -> list[dict[str, str]]:
    if checkpoint_file is None or not checkpoint_file.exists():
        return []
    try:
        payload = json.loads(checkpoint_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    keys: set[str] = set()
    completed = payload.get("completed_keyword_keys")
    if isinstance(completed, list):
        keys.update(str(item) for item in completed if item)
    failed = payload.get("failed_keyword_attempts")
    if isinstance(failed, dict):
        keys.update(str(key) for key in failed if key)
    return sorted(
        (_keyword_record_from_key(key) for key in keys),
        key=lambda item: (item["品牌"], item["岗位"]),
    )


def load_executed_keywords_from_logs(
    settings: Settings,
    *,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> list[dict[str, str]]:
    if window_start_at is None or window_end_at is None:
        return []
    log_path = settings.logs_dir / "app.log"
    if not log_path.exists():
        return []
    start_utc = _local_datetime_to_utc(window_start_at)
    end_utc = _local_datetime_to_utc(window_end_at)
    keywords: dict[str, dict[str, str]] = {}
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if '"event": "keyword_run_start"' not in line:
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError:
            continue
        timestamp = _parse_log_timestamp(payload.get("timestamp"))
        if timestamp is None or timestamp < start_utc or timestamp >= end_utc:
            continue
        brand = str(payload.get("brand_keyword") or "").strip()
        role = str(payload.get("role_keyword") or "").strip()
        if not brand:
            continue
        keyword = _keyword_record_from_key(f"{brand}|||{role}")
        keywords.setdefault(keyword["search_keyword"], keyword)
    return list(keywords.values())


def resolve_latest_run_scope(
    settings: Settings,
    *,
    checkpoint_file: Path | None = None,
) -> ExportScope:
    state_path = settings.artifacts_dir / "search_workflow_run_state.json"
    state = _read_json(state_path)
    context = state.get("context") if isinstance(state.get("context"), dict) else {}
    state_checkpoint = (
        _optional_path(context.get("checkpoint_file")) if isinstance(context, dict) else None
    )
    resolved_checkpoint = checkpoint_file or state_checkpoint
    return ExportScope(
        checkpoint_file=resolved_checkpoint,
        window_start_at=_datetime_from_epoch(state.get("started_at")),
        window_end_at=_datetime_from_epoch(state.get("finished_at")),
        source_label="latest_run",
    )


def resolve_today_scope(
    settings: Settings,
    *,
    checkpoint_file: Path | None = None,
    today: date | None = None,
) -> ExportScope:
    local_date = today or date.today()
    window_start = datetime.combine(local_date, datetime.min.time())
    return ExportScope(
        checkpoint_file=checkpoint_file,
        window_start_at=window_start,
        window_end_at=window_start + timedelta(days=1),
        source_label=local_date.isoformat(),
    )


def resolve_custom_scope(
    *,
    checkpoint_file: Path | None = None,
    window_start_at: datetime | None = None,
    window_end_at: datetime | None = None,
) -> ExportScope:
    return ExportScope(
        checkpoint_file=checkpoint_file,
        window_start_at=window_start_at,
        window_end_at=window_end_at,
        source_label="custom",
    )


def write_result_csv(rows: Iterable[dict[str, object]], output_file: Path) -> None:
    output_file.parent.mkdir(parents=True, exist_ok=True)
    with output_file.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=EXPORT_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({column: row.get(column, "") for column in EXPORT_COLUMNS})


def format_summary(result: ExportResult) -> dict[str, object]:
    return {
        "output_file": str(result.output_file),
        "row_count": result.row_count,
        "total_keywords": result.total_keywords,
        "candidate_rows": result.candidate_rows,
        "sent_count": result.sent_count,
        "needs_review_count": result.needs_review_count,
        "no_candidate_keywords": result.no_candidate_keywords,
        "window_start_at": _format_datetime(result.window_start_at),
        "window_end_at": _format_datetime(result.window_end_at),
    }


def _load_hit_rows(
    settings: Settings,
    *,
    window_start_at: datetime | None,
    window_end_at: datetime | None,
) -> list[dict[str, object]]:
    engine = get_engine(settings)
    conditions = []
    params: dict[str, object] = {"analysis_version": settings.coze_analysis_version}
    if window_start_at is not None:
        conditions.append("h.created_at >= :window_start_at")
        params["window_start_at"] = window_start_at
    if window_end_at is not None:
        conditions.append("h.created_at < :window_end_at")
        params["window_end_at"] = window_end_at
    where_clause = "WHERE " + " AND ".join(conditions) if conditions else ""
    sql = text(
        f"""
        SELECT h.id AS hit_id,
               h.candidate_name,
               h.candidate_fingerprint,
               h.search_keyword,
               h.hit_status,
               h.created_at AS hit_created_at,
               c.analysis_status,
               c.match_result,
               c.match_score,
               c.analysis_reason,
               c.evidence_summary_json,
               COALESCE(c.experience, r.experience) AS experience,
               c.updated_at AS analysis_updated_at,
               r.id AS contacted_record_id
        FROM candidate_resume_search_hits h
        LEFT JOIN (
            SELECT cache.*
            FROM candidate_resume_analysis_cache cache
            INNER JOIN (
                SELECT candidate_fingerprint, MAX(id) AS max_id
                FROM candidate_resume_analysis_cache
                WHERE analysis_version = :analysis_version
                GROUP BY candidate_fingerprint
            ) latest
              ON latest.candidate_fingerprint = cache.candidate_fingerprint
             AND latest.max_id = cache.id
        ) c
          ON c.candidate_fingerprint = h.candidate_fingerprint
        LEFT JOIN candidate_resume_experience_records r
          ON r.candidate_fingerprint = h.candidate_fingerprint
        {where_clause}
        ORDER BY h.created_at ASC, h.id ASC
        """
    )
    with engine.begin() as connection:
        return [dict(row) for row in connection.execute(sql, params).mappings().all()]


def _build_export_row(row: dict[str, object]) -> dict[str, object]:
    keyword = str(row.get("search_keyword") or "")
    brand, role = split_search_keyword(keyword)
    hit_status = str(row.get("hit_status") or "")
    match_result = str(row.get("match_result") or "")
    match_score = row.get("match_score")
    analysis_status = str(row.get("analysis_status") or "")
    sent = hit_status in SENT_HIT_STATUSES
    return {
        "关键词": keyword,
        "品牌": brand,
        "岗位": role,
        "候选人": row.get("candidate_name") or "",
        "候选人指纹": row.get("candidate_fingerprint") or "",
        "命中状态": hit_status,
        "动作结果": format_hit_status(hit_status, analysis_status, match_result, match_score),
        "是否发送": "是" if sent else "否",
        "Coze状态": analysis_status,
        "Coze结果": match_result,
        "Coze分数": match_score if match_score is not None else "",
        "未发送/跳过原因": ""
        if sent
        else format_skip_reason(
            hit_status,
            analysis_status,
            match_result,
            match_score,
        ),
        "Coze原因": row.get("analysis_reason") or "",
        "证据摘要": _format_evidence_summary(row.get("evidence_summary_json")),
        "简历内容": row.get("experience") or "",
        "命中时间": _format_datetime(row.get("hit_created_at")),
        "分析时间": _format_datetime(row.get("analysis_updated_at")),
        "联系记录ID": row.get("contacted_record_id") or "",
    }


def _build_no_candidate_row(keyword: dict[str, str]) -> dict[str, object]:
    return {
        "关键词": keyword["search_keyword"],
        "品牌": keyword["品牌"],
        "岗位": keyword["岗位"],
        "候选人": "",
        "候选人指纹": "",
        "命中状态": "no_candidate_in_batch",
        "动作结果": "本批无可处理候选人",
        "是否发送": "否",
        "Coze状态": "",
        "Coze结果": "",
        "Coze分数": "",
        "未发送/跳过原因": "本批未采集到可处理候选人",
        "Coze原因": "",
        "证据摘要": "",
        "命中时间": "",
        "分析时间": "",
        "联系记录ID": "",
    }


def format_hit_status(
    hit_status: str,
    analysis_status: str = "",
    match_result: str = "",
    match_score: object = None,
) -> str:
    if hit_status == "sent":
        return "已发送消息"
    if hit_status == "immediate_job_confirmed":
        return "已确认岗位并发送"
    if hit_status == "skipped_recent_continue":
        return "7天内已沟通过，跳过"
    if hit_status.startswith("send_failed:"):
        return "发送失败"
    if hit_status == "continue_without_db_record":
        return "继续沟通但无历史记录，未发送"
    if is_not_relevant_result(
        match_result,
        analysis_status=analysis_status,
    ) or (match_result in (None, "") and hit_status == "not_relevant"):
        return "Coze未通过，未发送"
    if _is_counted_needs_review(match_result, match_score):
        return "需人工复核，未发送"
    if match_result == "needs_review":
        return f"Coze未达{ACTIONABLE_NEEDS_REVIEW_MIN_SCORE}分待复核线，未发送"
    if hit_status == "reviewed":
        return "已评估，未发送"
    if hit_status == "intention_communication_skipped":
        return "仅可意向沟通，跳过"
    if hit_status == "unsupported_communication_skipped":
        return "沟通按钮状态不支持，跳过"
    if hit_status == "profile_collection_skipped":
        return "简历采集失败，跳过"
    if hit_status == "job_intent_city_rejected":
        return "求职意向未明确上海，跳过"
    if hit_status == "no_candidate_in_batch":
        return "本批无可处理候选人"
    return hit_status or "未知"


def format_skip_reason(
    hit_status: str,
    analysis_status: str = "",
    match_result: str = "",
    match_score: object = None,
) -> str:
    if hit_status in SENT_HIT_STATUSES:
        return ""
    if hit_status == "continue_without_db_record":
        return "页面显示继续沟通，但库里没有历史沟通记录，按保护逻辑不发送"
    if hit_status == "skipped_recent_continue":
        return "最近7天内已沟通过"
    if hit_status.startswith("send_failed:"):
        return hit_status.removeprefix("send_failed:")
    if _is_counted_needs_review(match_result, match_score):
        return "Coze返回需人工复核"
    if match_result == "needs_review":
        return f"Coze分数未达到{ACTIONABLE_NEEDS_REVIEW_MIN_SCORE}分待复核线"
    if is_not_relevant_result(
        match_result,
        analysis_status=analysis_status,
    ) or (match_result in (None, "") and hit_status == "not_relevant"):
        return "Coze判断不匹配"
    if hit_status == "reviewed":
        return "已评估但未进入发送条件"
    if hit_status == "intention_communication_skipped":
        return "只支持意向沟通，不走正式消息"
    if hit_status == "unsupported_communication_skipped":
        return "详情页没有识别到立即沟通/继续沟通"
    if hit_status == "profile_collection_skipped":
        return "简历采集失败"
    if hit_status == "job_intent_city_rejected":
        return "求职意向未明确包含上海；当前所在地和搜索城市不作为替代证据"
    if hit_status == "no_candidate_in_batch":
        return "本批未采集到可处理候选人"
    return hit_status or ""


def _is_counted_needs_review(match_result: object, match_score: object) -> bool:
    return is_actionable_needs_review_result(match_result, match_score)


def split_search_keyword(keyword: str) -> tuple[str, str]:
    if "，" in keyword:
        brand, role = keyword.split("，", 1)
        return brand.strip(), role.strip()
    if "," in keyword:
        brand, role = keyword.split(",", 1)
        return brand.strip(), role.strip()
    return keyword.strip(), ""


def _keyword_record_from_key(key: str) -> dict[str, str]:
    if "|||" in key:
        brand, role = key.split("|||", 1)
    else:
        brand, role = split_search_keyword(key)
    brand = brand.strip()
    role = role.strip()
    return {
        "品牌": brand,
        "岗位": role,
        "search_keyword": f"{brand}，{role}" if role else brand,
    }


def _format_evidence_summary(value: object) -> str:
    payload = _parse_json_value(value)
    if isinstance(payload, list):
        return "；".join(str(item).strip() for item in payload if str(item).strip())
    if isinstance(value, str):
        return value
    return ""


def _parse_json_value(value: object) -> object:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return None


def _read_json(path: Path) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return dict(payload) if isinstance(payload, dict) else {}


def _optional_path(value: object) -> Path | None:
    if not isinstance(value, str) or not value.strip():
        return None
    return Path(value)


def _datetime_from_epoch(value: object) -> datetime | None:
    if not isinstance(value, (int, float)):
        return None
    return datetime.fromtimestamp(value)


def _format_datetime(value: object) -> str:
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    return ""


def _local_datetime_to_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=LOCAL_TIMEZONE).astimezone(UTC)
    return value.astimezone(UTC)


def _parse_log_timestamp(value: object) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def _default_output_path(settings: Settings, source_label: str) -> Path:
    safe_label = "".join(ch if ch.isalnum() or ch in {"-", "_"} else "_" for ch in source_label)
    timestamp = datetime.fromtimestamp(time.time()).strftime("%Y%m%d_%H%M%S")
    return (
        settings.artifacts_dir / "search_results" / f"search_results_{safe_label}_{timestamp}.csv"
    )
