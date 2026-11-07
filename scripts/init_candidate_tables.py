from __future__ import annotations

import argparse
import sys

from liepin_rpa.candidate_db import CREATE_TABLES_SQL
from liepin_rpa.config import get_settings
from liepin_rpa.db import execute_sql_script


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--print-only",
        action="store_true",
        help="Print the SQL without executing it.",
    )
    return parser.parse_args()


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()
    settings = get_settings()

    if args.print_only:
        print(CREATE_TABLES_SQL)
        return 0

    execute_sql_script(settings, CREATE_TABLES_SQL)
    print(
        f"Initialized candidate tables in database `{settings.mysql_database}` "
        f"on host `{settings.mysql_host}`."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
