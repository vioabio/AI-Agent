from __future__ import annotations

import json
from pathlib import Path

from sqlalchemy import create_engine

from apps.hr_review_api.config import HRReviewSettings
from apps.hr_review_api.main import create_app


def main() -> int:
    """离线导出唯一 OpenAPI 契约，不连接 MySQL 或外部服务。"""

    engine = create_engine("sqlite+pysqlite:///:memory:", future=True)
    app = create_app(
        settings=HRReviewSettings(HR_REVIEW_DATABASE_URL="sqlite+pysqlite:///:memory:"),
        engine=engine,
    )
    output = Path("openapi/hr-review-openapi.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(app.openapi(), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
