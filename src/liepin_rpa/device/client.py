from __future__ import annotations

import base64
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, TypeVar

import uiautomator2 as u2

from liepin_rpa.device.adb import AdbCommandError, AdbRunner

T = TypeVar("T")
MAX_DEVICE_RECOVERY_ATTEMPTS = 3


class DeviceConnectionError(RuntimeError):
    """Raised when the Android device cannot be reached."""


def _build_connection_guidance(detail: str) -> str:
    steps = [
        "检查手机是否已解锁，并再次确认 USB 调试授权未失效。",
        "确认当前没有其他 uiautomator2 会话或自动化工具占用设备。",
        "断开并重新连接 USB，随后重新执行 `adb devices -l`。",
    ]
    lowered = detail.lower()
    if "already registered" in lowered or "uiautomation not connected" in lowered:
        steps.extend(
            [
                "这是手机端 UiAutomation 服务残留的典型表现，可先重试一次脚本。",
                "如果仍失败，优先重启手机后再次运行诊断脚本。",
            ]
        )
    return " ".join(steps)


@dataclass(slots=True)
class DeviceClient:
    adb_runner: AdbRunner
    device_id: str | None = None
    _device: u2.Device | None = None
    _last_health_check_at: float = 0.0

    def connect(self, force_reconnect: bool = False) -> None:
        if self._device is not None and not force_reconnect:
            return
        last_detail = "unknown_error"
        for attempt in range(1, MAX_DEVICE_RECOVERY_ATTEMPTS + 1):
            if attempt > 1 or force_reconnect:
                self.adb_runner.recover_device_transport()
                self._device = None
            try:
                self._device = u2.connect(self.device_id)
                return
            except Exception as exc:  # pragma: no cover - depends on real device state
                last_detail = str(exc) or exc.__class__.__name__
                self._device = None
                if attempt < MAX_DEVICE_RECOVERY_ATTEMPTS and (
                    _should_retry_after_cleanup(last_detail)
                    or _is_transient_device_error(last_detail)
                ):
                    continue
                break
        raise DeviceConnectionError(
            "Failed to connect to Android device via uiautomator2 after "
            f"{MAX_DEVICE_RECOVERY_ATTEMPTS} attempts. "
            "Check USB debugging, device authorization, and DEVICE_ID. "
            f"{_build_connection_guidance(last_detail)} "
            f"Original error: {last_detail}"
        )

    def is_online(self) -> bool:
        output = self.adb_runner.devices()
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        if self.device_id:
            return any(
                self.device_id in line and _line_contains_online_device(line)
                for line in lines
            )
        return any(_line_contains_online_device(line) for line in lines)

    def current_app(self) -> dict[str, Any]:
        return self._with_reconnect(lambda: self._require_device().app_current())

    def get_clipboard(self) -> str | None:
        value = self._with_reconnect(lambda: self._require_device().clipboard)
        if value is None:
            return None
        cleaned = str(value).strip()
        return cleaned or None

    def device_info(self) -> dict[str, Any]:
        return self._with_reconnect(lambda: self._require_device().info)

    def start_app(self, package: str) -> None:
        self.adb_runner.start_app(package)

    def start_activity(self, package: str, activity: str) -> None:
        self.adb_runner.start_activity(package, activity)

    def stop_app(self, package: str) -> None:
        self._with_reconnect(lambda: self._require_device().app_stop(package))

    def screenshot(self, path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        self._with_reconnect(lambda: self._require_device().screenshot(str(path)))
        return path

    def dump_hierarchy(self, path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        hierarchy = self._with_reconnect(lambda: self._require_device().dump_hierarchy())
        path.write_text(hierarchy, encoding="utf-8")
        return path

    def dump_hierarchy_text(self) -> str:
        return self._with_reconnect(lambda: self._require_device().dump_hierarchy())

    def press_back(self) -> None:
        self._with_reconnect(lambda: self._require_device().press("back"))

    def press(self, key: str) -> None:
        self._with_reconnect(lambda: self._require_device().press(key))

    def tap(self, x: int, y: int) -> None:
        self._with_reconnect(lambda: self._require_device().click(x, y))

    def send_keys(self, text: str, clear: bool = True) -> None:
        try:
            device = self._require_device()
            try:
                device.set_fastinput_ime(True)
            except Exception:
                pass
            device.send_keys(text, clear=clear)
        except Exception:
            if _contains_non_ascii(text):
                self._send_keys_via_adb_keyboard(text, clear=clear)
                return
            self._send_keys_via_adb_shell(text, clear=clear)

    def swipe(
        self,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        duration: float = 0.2,
    ) -> None:
        duration_ms = max(100, int(duration * 1000))
        self._with_reconnect(
            lambda: self.adb_runner.shell(
                "input",
                "swipe",
                str(x1),
                str(y1),
                str(x2),
                str(y2),
                str(duration_ms),
            )
        )

    def wake_up(self) -> None:
        self._with_reconnect(lambda: self._require_device().screen_on())

    def ensure_unlocked(self) -> None:
        device = self._require_device()
        if not device.info.get("screenOn", True):
            device.screen_on()

    def reset_automation_session(self) -> None:
        self.adb_runner.clear_uiautomator_sessions()
        self._device = None
        self.connect(force_reconnect=True)

    def ensure_healthy(self, *, max_age_seconds: float = 30.0) -> None:
        now = time.monotonic()
        if now - self._last_health_check_at < max_age_seconds:
            return
        last_detail = "unknown_error"
        for _attempt in range(1, MAX_DEVICE_RECOVERY_ATTEMPTS + 1):
            try:
                if not self.is_online():
                    self._device = None
                    raise DeviceConnectionError("Android device is offline or unauthorized.")
                if self._device is None:
                    self.connect(force_reconnect=True)
                self._with_reconnect(lambda: self._require_device().info, retry=True)
                self._last_health_check_at = now
                return
            except Exception as exc:
                last_detail = str(exc) or exc.__class__.__name__
                self._device = None
                self.adb_runner.recover_device_transport()
                time.sleep(1.5)
        self._last_health_check_at = now
        raise DeviceConnectionError(
            "Android device health check failed after "
            f"{MAX_DEVICE_RECOVERY_ATTEMPTS} recovery attempts. "
            f"{_build_connection_guidance(last_detail)} "
            f"Original error: {last_detail}"
        )

    def _require_device(self) -> u2.Device:
        if self._device is None:
            raise DeviceConnectionError("Device is not connected. Call connect() first.")
        return self._device

    def _with_reconnect(self, action: Callable[[], T], *, retry: bool = True) -> T:
        last_detail = "unknown_error"
        for attempt in range(1, MAX_DEVICE_RECOVERY_ATTEMPTS + 1):
            try:
                return action()
            except Exception as exc:  # pragma: no cover - depends on real device state
                if not retry:
                    raise
                last_detail = str(exc) or exc.__class__.__name__
                if not _is_transient_device_error(last_detail):
                    raise
                if attempt >= MAX_DEVICE_RECOVERY_ATTEMPTS:
                    break
                self.reset_automation_session()
        raise DeviceConnectionError(
            "Android device operation failed after reconnect. "
            f"{_build_connection_guidance(last_detail)} "
            f"Original error: {last_detail}"
        )

    def _send_keys_via_adb_keyboard(self, text: str, clear: bool = True) -> None:
        def _send() -> None:
            self.adb_runner.shell("ime", "enable", "com.github.uiautomator/.AdbKeyboard")
            self.adb_runner.shell("ime", "set", "com.github.uiautomator/.AdbKeyboard")
            if clear:
                self.adb_runner.shell("am", "broadcast", "-a", "ADB_CLEAR_TEXT")
            payload = base64.b64encode(text.encode("utf-8")).decode("ascii")
            self.adb_runner.shell("am", "broadcast", "-a", "ADB_INPUT_B64", "--es", "msg", payload)

        self._with_reconnect(_send)

    def _send_keys_via_adb_shell(self, text: str, clear: bool = True) -> None:
        def _send() -> None:
            if clear:
                self.press("clear")
            shell_text = text.replace(" ", "%s")
            self.adb_runner.shell("input", "text", shell_text)

        self._with_reconnect(_send)


def _line_contains_online_device(line: str) -> bool:
    parts = line.split()
    return len(parts) >= 2 and parts[1] == "device"


def _should_retry_after_cleanup(detail: str) -> bool:
    lowered = detail.lower()
    return "already registered" in lowered or "uiautomation not connected" in lowered


def _is_transient_device_error(detail: str) -> bool:
    lowered = detail.lower()
    return any(
        marker in lowered
        for marker in (
            "uiautomation not connected",
            "already registered",
            "connection refused",
            "connection reset",
            "httpconnectionpool",
            "read timed out",
            "device offline",
            "not found",
            "exit status 255",
            "closed",
            "broken pipe",
        )
    )


def _contains_non_ascii(text: str) -> bool:
    return any(ord(char) > 127 for char in text)
