from __future__ import annotations

import time

from liepin_rpa.device.client import DeviceClient

DEFAULT_LIEPIN_PACKAGE = "com.lietou.mishu"


def resolve_liepin_package(configured_package: str | None) -> str:
    return configured_package or DEFAULT_LIEPIN_PACKAGE


def ensure_liepin_started(
    device: DeviceClient,
    package: str,
    settle_seconds: float = 4.0,
) -> None:
    current_app = device.current_app()
    if current_app.get("package") == package:
        return
    device.start_app(package)
    time.sleep(settle_seconds)
