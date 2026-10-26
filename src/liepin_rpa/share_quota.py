from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path


@dataclass(frozen=True, slots=True)
class ShareQuotaState:
    local_date: str
    quota_exhausted: bool
    account_alias: str | None = None
    reason: str | None = None
    updated_at: str | None = None


class DailyShareQuotaStateStore:
    """Persist Liepin's account-level daily share-quota state.

    A state from an earlier local date is deliberately ignored so the next
    day's first eligible candidate can try sharing again.
    """

    def __init__(self, path: Path, *, account_alias: str | None = None) -> None:
        self.path = path
        self.account_alias = (account_alias or "").strip() or None

    def load(self) -> ShareQuotaState | None:
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (FileNotFoundError, OSError, ValueError, TypeError):
            return None
        if not isinstance(payload, dict):
            return None
        local_date = str(payload.get("local_date") or "").strip()
        if not local_date:
            return None
        return ShareQuotaState(
            local_date=local_date,
            quota_exhausted=bool(payload.get("quota_exhausted")),
            account_alias=str(payload.get("account_alias") or "").strip() or None,
            reason=str(payload.get("reason") or "").strip() or None,
            updated_at=str(payload.get("updated_at") or "").strip() or None,
        )

    def is_exhausted(self, *, current_date: date | None = None) -> bool:
        state = self.load()
        today = (current_date or date.today()).isoformat()
        return bool(
            state
            and state.local_date == today
            and state.account_alias == self.account_alias
            and state.quota_exhausted
        )

    def mark_exhausted(
        self,
        *,
        reason: str,
        current_date: date | None = None,
    ) -> ShareQuotaState:
        state = ShareQuotaState(
            local_date=(current_date or date.today()).isoformat(),
            quota_exhausted=True,
            account_alias=self.account_alias,
            reason=reason.strip() or "share_quota_exhausted",
            updated_at=datetime.now().astimezone().isoformat(timespec="seconds"),
        )
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary_path.write_text(
            json.dumps(
                {
                    "local_date": state.local_date,
                    "quota_exhausted": state.quota_exhausted,
                    "account_alias": state.account_alias,
                    "reason": state.reason,
                    "updated_at": state.updated_at,
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        temporary_path.replace(self.path)
        return state
