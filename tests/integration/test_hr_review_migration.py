from __future__ import annotations

from io import StringIO

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect


def test_execution_migration_can_render_mysql_sql_without_connecting():
    output = StringIO()
    config = Config("alembic.ini", output_buffer=output)
    config.set_main_option("sqlalchemy.url", "mysql+pymysql://offline:offline@invalid.invalid/offline")
    command.upgrade(config, "20260818_02:20261105_03", sql=True)
    sql = output.getvalue()
    assert "ADD COLUMN execution_status" in sql
    assert "ADD COLUMN retry_after" in sql
    assert "UPDATE candidate_resume_analysis_cache" not in sql


def test_evidence_migration_can_render_mysql_sql_without_connecting():
    output = StringIO()
    config = Config("alembic.ini", output_buffer=output)
    config.set_main_option("sqlalchemy.url", "mysql+pymysql://offline:offline@invalid.invalid/offline")
    command.upgrade(config, "20261105_03:20261105_04", sql=True)
    sql = output.getvalue()
    assert "CREATE TABLE candidate_resume_snapshots" in sql
    assert "CREATE TABLE candidate_resume_analysis_runs" in sql
    assert "ADD COLUMN analysis_run_id" in sql


def test_queue_migration_renders_mysql_without_connecting():
    output = StringIO()
    config = Config("alembic.ini", output_buffer=output)
    config.set_main_option("sqlalchemy.url", "mysql+pymysql://offline:offline@invalid.invalid/offline")
    command.upgrade(config, "20261105_04:20261105_05", sql=True)
    sql = output.getvalue()
    assert "CREATE TABLE analysis_jobs" in sql and "lease_token INTEGER NOT NULL" in sql
    assert "CREATE TABLE analysis_queue_control" in sql and "charged_tokens BIGINT NOT NULL" in sql
    assert "UPDATE candidate_resume" not in sql and "DROP TABLE" not in sql


def test_collection_receipt_migration_renders_mysql_without_connecting():
    output = StringIO()
    config = Config("alembic.ini", output_buffer=output)
    config.set_main_option("sqlalchemy.url", "mysql+pymysql://offline:offline@invalid.invalid/offline")
    command.upgrade(config, "20261105_05:20261105_06", sql=True)
    sql = output.getvalue()
    assert "CREATE TABLE analysis_collection_receipts" in sql
    assert "FOREIGN KEY(job_id) REFERENCES analysis_jobs" in sql
    assert "UPDATE candidate_resume" not in sql


def test_request_accounting_migration_renders_mysql_without_connecting():
    output = StringIO()
    config = Config("alembic.ini", output_buffer=output)
    config.set_main_option("sqlalchemy.url", "mysql+pymysql://offline:offline@invalid.invalid/offline")
    command.upgrade(config, "20261105_06:20261105_07", sql=True)
    sql = output.getvalue()
    assert "CREATE TABLE analysis_request_plans" in sql and "plan_json LONGTEXT NOT NULL" in sql
    assert "CREATE TABLE analysis_request_receipts" in sql
    assert "FOREIGN KEY(attempt_id) REFERENCES analysis_job_attempts" in sql
    assert "UPDATE candidate_resume" not in sql


def test_collected_and_tools_migration_renders_mysql_without_connecting():
    output = StringIO()
    config = Config("alembic.ini", output_buffer=output)
    config.set_main_option("sqlalchemy.url", "mysql+pymysql://offline:offline@invalid.invalid/offline")
    command.upgrade(config, "20261105_07:20261105_08", sql=True)
    sql = output.getvalue()
    assert "CREATE TABLE analysis_collected_inputs" in sql
    assert "CREATE TABLE analysis_tool_sessions" in sql and "CREATE TABLE analysis_tool_rounds" in sql
    assert "UNIQUE (job_id, sequence)" in sql
    assert "UPDATE candidate_resume" not in sql


def test_hr_review_migration_upgrade_and_downgrade(tmp_path) -> None:
    database = tmp_path / "migration.sqlite"
    engine = create_engine(f"sqlite+pysqlite:///{database}", future=True)
    with engine.begin() as connection:
        connection.exec_driver_sql(
            "CREATE TABLE candidate_resume_analysis_cache "
            "(id INTEGER PRIMARY KEY, analysis_reason TEXT)"
        )
        connection.exec_driver_sql(
            "INSERT INTO candidate_resume_analysis_cache VALUES (1, 'historical record')"
        )
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
        connection.exec_driver_sql(
            """
            CREATE TABLE candidate_resume_search_hits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                candidate_fingerprint VARCHAR(64) NOT NULL
            )
            """
        )
        connection.exec_driver_sql(
            """
            CREATE TABLE candidate_resume_source_locators (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                candidate_fingerprint VARCHAR(64) NOT NULL
            )
            """
        )
        connection.exec_driver_sql(
            """
            CREATE TABLE candidate_resume_daily_report_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT
            )
            """
        )
    config = Config("alembic.ini")
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")

    command.upgrade(config, "head")
    inspector = inspect(engine)
    assert "hr_users" in inspector.get_table_names()
    execution_columns = {
        column["name"] for column in inspector.get_columns("candidate_resume_analysis_cache")
    }
    assert {"execution_status", "error_code", "retryable", "retry_after"} <= execution_columns
    assert {"analysis_run_id", "analyzed_text_hash"} <= execution_columns
    assert {"candidate_resume_snapshots", "candidate_resume_analysis_runs", "candidate_resume_evidence_refs"} <= set(inspector.get_table_names())
    from liepin_rpa.analysis_job_schema import queue_metadata
    assert set(queue_metadata.tables) <= set(inspector.get_table_names())
    for table in queue_metadata.sorted_tables:
        actual = {column["name"]: column for column in inspector.get_columns(table.name)}
        assert set(actual) == set(table.c.keys())
        for column in table.c:
            assert actual[column.name]["nullable"] == column.nullable
            assert str(actual[column.name]["type"]) == str(column.type)
    with engine.connect() as connection:
        row = connection.exec_driver_sql(
            "SELECT analysis_reason, execution_status FROM candidate_resume_analysis_cache"
        ).one()
        assert row == ("historical record", None)
    columns = {
        column["name"] for column in inspector.get_columns("candidate_resume_experience_records")
    }
    assert {"manual_reviewed_by", "manual_review_assignee_id", "manual_review_revision"} <= columns
    assert "search_batch_id" in {
        column["name"] for column in inspector.get_columns("candidate_resume_search_hits")
    }
    assert "search_batch_id" in {
        column["name"] for column in inspector.get_columns("candidate_resume_source_locators")
    }
    assert "search_batch_id" in {
        column["name"] for column in inspector.get_columns("candidate_resume_daily_report_runs")
    }
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
    assert "candidate_resume_snapshots" not in inspector.get_table_names()
    assert not set(queue_metadata.tables) & set(inspector.get_table_names())
    assert "execution_status" not in {
        column["name"] for column in inspector.get_columns("candidate_resume_analysis_cache")
    }
    columns = {
        column["name"] for column in inspector.get_columns("candidate_resume_experience_records")
    }
    assert "manual_review_revision" not in columns
    assert "search_batch_id" not in {
        column["name"] for column in inspector.get_columns("candidate_resume_search_hits")
    }
