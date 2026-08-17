from __future__ import annotations

import argparse

from scripts.run_search_workflow import resolve_run_permissions


def _args(*, no_send_message: bool, collect_only: bool) -> argparse.Namespace:
    return argparse.Namespace(
        no_send_message=no_send_message,
        collect_only=collect_only,
    )


def test_default_mode_writes_and_sends() -> None:
    assert resolve_run_permissions(
        _args(no_send_message=False, collect_only=False)
    ) == (True, True)


def test_probe_mode_neither_writes_nor_sends() -> None:
    assert resolve_run_permissions(
        _args(no_send_message=True, collect_only=False)
    ) == (False, False)


def test_collect_only_mode_writes_without_sending() -> None:
    assert resolve_run_permissions(
        _args(no_send_message=False, collect_only=True)
    ) == (True, False)
