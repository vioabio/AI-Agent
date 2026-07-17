from __future__ import annotations

import subprocess
import time
from dataclasses import dataclass

import adbutils


class AdbCommandError(RuntimeError):
    """Raised when an ADB command cannot be executed successfully."""


@dataclass(slots=True)
class AdbRunner:
    executable: str = "adb"
    device_id: str | None = None

    def resolved_executable(self) -> str:
        if self.executable.lower() == "adb":
            try:
                return adbutils.adb_path()
            except Exception:
                return self.executable
        return self.executable

    def run(self, *args: str) -> str:
        executable = self.resolved_executable()
        command = [executable, *self._target_args(*args)]
        try:
            result = subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        except FileNotFoundError as exc:
            raise AdbCommandError(
                f"ADB executable not found: {executable}. "
                "Please install ADB Platform Tools or set ADB_EXECUTABLE."
            ) from exc
        except subprocess.CalledProcessError as exc:
            stderr = (exc.stderr or "").strip()
            stdout = (exc.stdout or "").strip()
            detail = stderr or stdout or str(exc)
            raise AdbCommandError(f"ADB command failed: {detail}") from exc
        return result.stdout.strip()

    def version(self) -> str:
        return self.run("version")

    def devices(self) -> str:
        return self.run("devices", "-l")

    def wait_for_device(self, timeout_seconds: int = 15) -> None:
        executable = self.resolved_executable()
        command = [
            executable,
            *self._target_args("wait-for-device"),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout_seconds,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            raise AdbCommandError(f"ADB wait-for-device failed: {exc}") from exc

    def shell(self, *args: str) -> str:
        return self.run("shell", *args)

    def start_app(self, package: str) -> str:
        return self.shell(
            "monkey",
            "-p",
            package,
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
        )

    def start_activity(self, package: str, activity: str) -> str:
        component = f"{package}/{activity}"
        return self.shell("am", "start", "-n", component)

    def clear_uiautomator_sessions(self) -> None:
        cleanup_commands = [
            ("shell", "am", "force-stop", "com.github.uiautomator"),
            ("shell", "am", "force-stop", "com.github.uiautomator.test"),
            ("shell", "am", "force-stop", "com.github.uiautomator.debug"),
            ("shell", "killall", "uiautomator"),
            ("shell", "killall", "app_process"),
            ("forward", "--remove-all"),
        ]
        for command in cleanup_commands:
            try:
                self.run(*command)
            except AdbCommandError:
                continue
        time.sleep(1.0)

    def recover_device_transport(self) -> None:
        try:
            self.run("start-server")
        except AdbCommandError:
            pass
        try:
            self.clear_uiautomator_sessions()
        except AdbCommandError:
            pass
        try:
            self.wait_for_device(timeout_seconds=15)
        except AdbCommandError:
            pass
        time.sleep(1.0)

    def _target_args(self, *args: str) -> tuple[str, ...]:
        if not self.device_id:
            return args
        if not args:
            return args
        if args[0] in {"devices", "version"}:
            return args
        return ("-s", self.device_id, *args)
