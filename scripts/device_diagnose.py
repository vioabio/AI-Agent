from __future__ import annotations

import json
import sys

from liepin_rpa.config import get_settings
from liepin_rpa.device.client import DeviceConnectionError
from liepin_rpa.device.diagnose import run_device_diagnosis
from liepin_rpa.logging_utils import configure_logging, get_logger


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    settings = get_settings()
    configure_logging(settings.log_level, settings.logs_dir)
    logger = get_logger(__name__)

    try:
        artifacts = run_device_diagnosis(settings)
    except DeviceConnectionError as exc:
        logger.error(
            str(exc),
            extra={
                "event": "device_diagnosis_failed",
                "context": {"device_id": settings.device_id},
            },
        )
        print(str(exc), file=sys.stderr)
        return 1
    except Exception as exc:  # pragma: no cover - defensive wrapper
        logger.exception(
            "Unexpected device diagnosis failure",
            extra={
                "event": "device_diagnosis_unexpected_failure",
                "context": {"device_id": settings.device_id},
            },
        )
        print(str(exc), file=sys.stderr)
        return 1

    payload = {
        "report_path": str(artifacts.report_path),
        "screenshot_path": str(artifacts.screenshot_path),
        "hierarchy_path": str(artifacts.hierarchy_path),
    }
    logger.info(
        "Device diagnosis completed",
        extra={"event": "device_diagnosis_completed", "context": payload},
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
