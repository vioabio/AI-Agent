from __future__ import annotations

import time

from liepin_rpa.device.client import DeviceClient

DEFAULT_LIEPIN_PACKAGE = "com.lietou.mishu"
DEFAULT_LIEPIN_MAIN_ACTIVITY = ".HomeActivity"


def resolve_liepin_package(configured_package: str | None) -> str:
    return (configured_package or "").strip() or DEFAULT_LIEPIN_PACKAGE


def resolve_liepin_main_activity(configured_activity: str | None) -> str | None:
    activity = (configured_activity or "").strip()
    return activity or DEFAULT_LIEPIN_MAIN_ACTIVITY


def ensure_liepin_started(
    device: DeviceClient,
    package: str,
    main_activity: str | None = None,
    settle_seconds: float = 5.0,
    force_restart: bool = False,
) -> None:
    device.wake_up()
    if force_restart:
        device.stop_app(package)
        time.sleep(1.5)
    for attempt in range(3):
        current_app = device.current_app()
        if current_app.get("package") == package:
            return
        if main_activity:
            device.start_activity(package, main_activity)
        else:
            device.start_app(package)
        time.sleep(settle_seconds)
    current_app = device.current_app()
    if current_app.get("package") != package:
        raise RuntimeError(
            f"Failed to bring target app to foreground. expected={package} actual={current_app.get('package')}"
        )
