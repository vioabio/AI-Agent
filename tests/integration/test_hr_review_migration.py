from __future__ import annotations

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect


def test_hr_review_migration_upgrade_and_downgrade(tmp_path) -> None:
    database = tmp_path / "migration.sqlite"
    engine = create_engine(f"sqlite+pysqlite:///{database}", future=True)
    with engine.begin() as connection:
        connection.exec_driver_sql(
            """
            CREATE TABLE candidate_resume_experience_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                candidate_fingerprint VARCHAR(64) NOT NULL UNIQUE,
                manual_review_status VARCHAR(64),
                manual_review_feedback TEXT,
                manual_suppress_communication INTEGER NOT NULL DEFAULT 0,
                manual_suppress_report INTEGER NOT NULL DEFAULT 0,
                manual_reviewed_at DATETIME
            )
            """
        )
    config = Config("alembic.ini")
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")

    command.upgrade(config, "head")
    inspector = inspect(engine)
    assert "hr_users" in inspector.get_table_names()
    columns = {
        column["name"] for column in inspector.get_columns("candidate_resume_experience_records")
    }
    assert {"manual_reviewed_by", "manual_review_assignee_id", "manual_review_revision"} <= columns
    with engine.begin() as connection:
        connection.exec_driver_sql(
            """
            INSERT INTO hr_users (username, display_name, password_hash, role)
            VALUES ('sqlite-autoincrement', 'SQLite 测试', 'not-a-real-hash', 'read_only')
            """
        )
        assert (
            connection.exec_driver_sql(
                "SELECT id FROM hr_users WHERE username = 'sqlite-autoincrement'"
            ).scalar_one()
            == 1
        )

    command.downgrade(config, "base")
    inspector = inspect(engine)
    assert "hr_users" not in inspector.get_table_names()
    columns = {
        column["name"] for column in inspector.get_columns("candidate_resume_experience_records")
    }
    assert "manual_review_revision" not in columns
