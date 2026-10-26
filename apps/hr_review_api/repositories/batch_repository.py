from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from apps.hr_review_api.domain.errors import BatchNotFoundError

SHANGHAI_TIMEZONE = timezone(timedelta(hours=8))


@dataclass(frozen=True, slots=True)
class BatchRecord:
    batch_id: str
    started_at: datetime
    ended_at: datetime
    label: str


class BatchRepository:
    """只读搜索批次状态；不会修改 checkpoint、运行锁或批次文件。"""

    def __init__(self, path: Path) -> None:
        self.path = path

    def list_completed(self) -> tuple[BatchRecord, ...]:
        if not self.path.exists():
            return ()
        try:
            payload: object = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise BatchNotFoundError("搜索批次状态文件无法读取") from exc
        if not isinstance(payload, dict):
            raise BatchNotFoundError("搜索批次状态格式不正确")
        records = payload.get("completed_batches")
        if not isinstance(records, list):
            return ()
        batches = [self._parse_record(item) for item in records if isinstance(item, dict)]
        return tuple(sorted(batches, key=lambda item: item.ended_at, reverse=True))

    def get(self, batch_id: str) -> BatchRecord:
        batch = next((item for item in self.list_completed() if item.batch_id == batch_id), None)
        if batch is None:
            raise BatchNotFoundError("指定搜索批次不存在")
        return batch

    @staticmethod
    def _parse_record(item: dict[object, object]) -> BatchRecord:
        batch_id = item.get("batch_id")
        started = item.get("started_at")
        ended = item.get("ended_at")
        if not isinstance(batch_id, str) or not batch_id:
            raise BatchNotFoundError("搜索批次缺少必要字段")
        if not isinstance(started, str) or not started:
            raise BatchNotFoundError("搜索批次缺少必要字段")
        if not isinstance(ended, str) or not ended:
            raise BatchNotFoundError("搜索批次缺少必要字段")
        try:
            started_at = _parse_local_time(started)
            ended_at = _parse_local_time(ended)
        except ValueError as exc:
            raise BatchNotFoundError("搜索批次时间格式不正确") from exc
        if ended_at <= started_at:
            raise BatchNotFoundError("搜索批次时间窗口不正确")
        label = f"{ended_at:%Y-%m-%d %H:%M} 完成"
        return BatchRecord(batch_id=batch_id, started_at=started_at, ended_at=ended_at, label=label)


def _parse_local_time(value: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=SHANGHAI_TIMEZONE)
    return parsed.astimezone(SHANGHAI_TIMEZONE)
