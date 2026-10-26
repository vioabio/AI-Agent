from __future__ import annotations

from datetime import date

from liepin_rpa.share_quota import DailyShareQuotaStateStore


def test_daily_share_quota_exhaustion_resets_on_next_local_date(tmp_path) -> None:
    store = DailyShareQuotaStateStore(
        tmp_path / "share_quota_state.json",
        account_alias="account-a",
    )
    store.mark_exhausted(
        reason="liepin_ui_quota_message",
        current_date=date(2026, 8, 13),
    )

    assert store.is_exhausted(current_date=date(2026, 8, 13)) is True
    assert store.is_exhausted(current_date=date(2026, 8, 14)) is False


def test_share_quota_state_is_scoped_to_account(tmp_path) -> None:
    state_path = tmp_path / "share_quota_state.json"
    account_a = DailyShareQuotaStateStore(state_path, account_alias="account-a")
    account_b = DailyShareQuotaStateStore(state_path, account_alias="account-b")
    account_a.mark_exhausted(reason="test", current_date=date(2026, 8, 13))

    assert account_a.is_exhausted(current_date=date(2026, 8, 13)) is True
    assert account_b.is_exhausted(current_date=date(2026, 8, 13)) is False
