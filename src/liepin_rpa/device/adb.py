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

    def resolved_executable(self) -> str:
        if self.executable.lower() == "adb":
            try:
                return adbutils.adb_path()
            except Exception:
                return self.executable
        return self.executable

    def run(self, *args: str) -> str:
        executable = self.resolved_executable()
        try:
            result = subprocess.run(
                [executable, *args],
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

    def shell(self, *args: str) -> str:
        return self.run("shell", *args)

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
