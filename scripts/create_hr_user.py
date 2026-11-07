from __future__ import annotations

import argparse
import getpass

from sqlalchemy import insert

from apps.hr_review_api.config import HRReviewSettings
from apps.hr_review_api.domain.access_policy import UserRole
from apps.hr_review_api.persistence.tables import hr_user_job_permissions, hr_users
from apps.hr_review_api.runtime import build_engine
from apps.hr_review_api.services.auth_service import AuthService


def main() -> int:
    parser = argparse.ArgumentParser(description="创建 HR 工作台本地账号")
    parser.add_argument("username")
    parser.add_argument("display_name")
    parser.add_argument("--role", choices=[item.value for item in UserRole], default="hr")
    parser.add_argument("--job", action="append", default=[])
    parser.add_argument("--can-view-sensitive", action="store_true")
    args = parser.parse_args()
    password = getpass.getpass("Password: ")
    confirmation = getpass.getpass("Confirm password: ")
    if password != confirmation or len(password) < 12:
        raise SystemExit("密码必须一致且至少 12 位")
    settings = HRReviewSettings()
    engine = build_engine(settings)
    password_hash = AuthService(engine, settings).hash_password(password)
    with engine.begin() as connection:
        result = connection.execute(
            insert(hr_users).values(
                username=args.username.strip(),
                display_name=args.display_name.strip(),
                password_hash=password_hash,
                role=args.role,
                can_view_sensitive=args.can_view_sensitive,
            )
        )
        user_id = int(result.inserted_primary_key[0])
        for job_profile_id in sorted(set(args.job)):
            connection.execute(
                insert(hr_user_job_permissions).values(
                    user_id=user_id,
                    job_profile_id=job_profile_id,
                )
            )
    print(f"Created HR user {args.username} (id={user_id}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
