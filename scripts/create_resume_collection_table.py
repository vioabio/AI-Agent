from __future__ import annotations

import sys

from liepin_rpa.config import get_settings
from liepin_rpa.db import (
    RESUME_ANALYSIS_CACHE_TABLE_NAME,
    LEGACY_RESUME_COLLECTION_TABLE_NAME,
    LEGACY_WORK_EXPERIENCE_TABLE_NAME,
    RESUME_EXPERIENCE_TABLE_NAME,
    RESUME_SEARCH_HITS_TABLE_NAME,
    drop_legacy_resume_tables,
    ensure_resume_experience_schema,
    reset_resume_experience_tables,
)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

    settings = get_settings()
    ensure_resume_experience_schema(settings)
    drop_legacy_resume_tables(settings)
    reset_resume_experience_tables(settings)
    print(f"created_or_exists={RESUME_EXPERIENCE_TABLE_NAME}")
    print(f"created_or_exists={RESUME_SEARCH_HITS_TABLE_NAME}")
    print(f"created_or_exists={RESUME_ANALYSIS_CACHE_TABLE_NAME}")
    print(f"dropped_if_exists={LEGACY_RESUME_COLLECTION_TABLE_NAME}")
    print(f"dropped_if_exists={LEGACY_WORK_EXPERIENCE_TABLE_NAME}")
    print(f"truncated={RESUME_EXPERIENCE_TABLE_NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
