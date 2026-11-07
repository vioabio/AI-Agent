from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from liepin_rpa.config import Settings
from liepin_rpa.device.adb import AdbRunner
from liepin_rpa.device.client import DeviceClient, DeviceConnectionError


@dataclass(slots=True)
class DiagnosisArtifacts:
    report_path: Path
    screenshot_path: Path
    hierarchy_path: Path


def build_troubleshooting_steps() -> list[str]:
    return [
        "确认手机已开启开发者选项和 USB 调试。",
        "确认手机已通过 USB 连接并在设备上允许当前电脑调试。",
        "执行 `adb devices -l` 检查设备状态是否为 device，而不是 unauthorized。",
        "如配置了 DEVICE_ID，确认其与 `adb devices -l` 输出完全一致。",
        "如果 uiautomator2 首次连接失败，可重试并确认设备未被系统弹窗阻塞。",
    ]


def timestamp_now() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def build_diagnosis_report(
    adb_version: str,
    adb_devices: str,
    device_info: dict[str, Any],
    current_app: dict[str, Any],
    screenshot_path: Path,
    hierarchy_path: Path,
    device_id: str | None,
) -> dict[str, Any]:
    return {
        "generated_at": datetime.now().isoformat(),
        "device_id": device_id,
        "adb_version": adb_version,
        "adb_devices": adb_devices,
        "device_info": device_info,
        "current_app": current_app,
        "artifacts": {
            "screenshot": str(screenshot_path),
            "hierarchy_xml": str(hierarchy_path),
        },
    }


def run_device_diagnosis(settings: Settings) -> DiagnosisArtifacts:
    diagnose_dir = settings.device_diagnose_dir
    diagnose_dir.mkdir(parents=True, exist_ok=True)

    adb_runner = AdbRunner(settings.adb_executable, device_id=settings.device_id)
    adb_version = adb_runner.version()
    adb_devices = adb_runner.devices()

    client = DeviceClient(adb_runner=adb_runner, device_id=settings.device_id)

    if not client.is_online():
        guidance = "\n".join(f"- {step}" for step in build_troubleshooting_steps())
        raise DeviceConnectionError(
            "No authorized Android device is currently online.\n"
            f"ADB output:\n{adb_devices}\n"
            f"Troubleshooting:\n{guidance}"
        )

    client.connect()
    device_info = client.device_info()
    current_app = client.current_app()
    stamp = timestamp_now()
    screenshot_path = diagnose_dir / f"{stamp}.png"
    hierarchy_path = diagnose_dir / f"{stamp}.xml"
    report_path = diagnose_dir / f"{stamp}.json"

    client.screenshot(screenshot_path)
    client.dump_hierarchy(hierarchy_path)

    report = build_diagnosis_report(
        adb_version=adb_version,
        adb_devices=adb_devices,
        device_info=device_info,
        current_app=current_app,
        screenshot_path=screenshot_path,
        hierarchy_path=hierarchy_path,
        device_id=settings.device_id,
    )
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    return DiagnosisArtifacts(
        report_path=report_path,
        screenshot_path=screenshot_path,
        hierarchy_path=hierarchy_path,
    )
