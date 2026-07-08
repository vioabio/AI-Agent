from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from liepin_rpa.device.client import DeviceClient


@dataclass(slots=True)
class DebugBundleArtifacts:
    bundle_dir: Path
    screenshot_path: Path
    hierarchy_path: Path
    context_path: Path


def create_unknown_page_debug_bundle(
    device: DeviceClient,
    output_dir: Path,
    reason: str,
    current_app: dict[str, object],
) -> DebugBundleArtifacts:
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    bundle_dir = output_dir / f"unknown_page_{stamp}"
    bundle_dir.mkdir(parents=True, exist_ok=True)

    screenshot_path = device.screenshot(bundle_dir / "screenshot.png")
    hierarchy_path = device.dump_hierarchy(bundle_dir / "hierarchy.xml")
    context_path = bundle_dir / "context.json"
    context_path.write_text(
        json.dumps(
            {
                "reason": reason,
                "current_app": current_app,
                "created_at": datetime.now().isoformat(),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    return DebugBundleArtifacts(
        bundle_dir=bundle_dir,
        screenshot_path=screenshot_path,
        hierarchy_path=hierarchy_path,
        context_path=context_path,
    )
