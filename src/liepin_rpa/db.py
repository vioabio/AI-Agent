from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from contextlib import closing
from datetime import datetime, timedelta
from functools import lru_cache

import xxhash
from sqlalchemy import (
    BigInteger,
    Column,
    DateTime,
    MetaData,
    String,
    Table,
    Text,
    TIMESTAMP,
    UniqueConstraint,
    create_engine,
    func,
    inspect,
    insert,
    select,
    text,
)
from sqlalchemy.engine import Engine, URL
from sqlalchemy.pool import QueuePool
from sqlalchemy.dialects.mysql import insert as mysql_insert

from liepin_rpa.config import Settings
from liepin_rpa.logging_utils import get_logger

RESUME_EXPERIENCE_TABLE_NAME = "candidate_resume_experience_records"
RESUME_SEARCH_HITS_TABLE_NAME = "candidate_resume_search_hits"
RESUME_ANALYSIS_CACHE_TABLE_NAME = "candidate_resume_analysis_cache"
DAILY_REPORT_RUNS_TABLE_NAME = "candidate_resume_daily_report_runs"
LEGACY_RESUME_COLLECTION_TABLE_NAME = "candidate_resume_collection_records"
LEGACY_WORK_EXPERIENCE_TABLE_NAME = "candidate_resume_work_experiences"

WORK_PREFIX = "工作内容："
PROJECT_PREFIX = "项目内容："
PROFILE_PREFIX = "候选人信息："
ITEM_SEPARATOR = "；"
BLOCK_SEPARATOR = " "
DEFAULT_ANALYSIS_STATUS = "未执行"
LOGGER = get_logger(__name__)

metadata = MetaData()

resume_experience_records = Table(
    RESUME_EXPERIENCE_TABLE_NAME,
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("candidate_fingerprint", String(64), nullable=False, unique=True, index=True),
    Column("candidate_name", String(255), nullable=True, index=True),
    Column("source_platform", String(64), nullable=False, server_default=text("'liepin'")),
    Column("search_keyword", String(255), nullable=True),
    Column("experience", Text, nullable=True),
    Column("match_score", BigInteger, nullable=True),
    Column(
        "analysis_status",
        String(64),
        nullable=False,
        server_default=text(f"'{DEFAULT_ANALYSIS_STATUS}'"),
    ),
    Column("created_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
)

resume_search_hits = Table(
    RESUME_SEARCH_HITS_TABLE_NAME,
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("candidate_fingerprint", String(64), nullable=False, index=True),
    Column("candidate_name", String(255), nullable=True),
    Column("search_keyword", String(255), nullable=False, index=True),
    Column("hit_status", String(64), nullable=False, server_default=text("'hit'")),
    Column("created_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
)

resume_analysis_cache = Table(
    RESUME_ANALYSIS_CACHE_TABLE_NAME,
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("candidate_fingerprint", String(64), nullable=False, index=True),
    Column("experience_hash", String(64), nullable=False),
    Column("analysis_status", String(64), nullable=False),
    Column("match_result", String(64), nullable=True),
    Column("match_score", BigInteger, nullable=True),
    Column("score_breakdown_json", Text, nullable=True),
    Column("analysis_reason", Text, nullable=True),
    Column("evidence_summary_json", Text, nullable=True),
    Column("created_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint("candidate_fingerprint", "experience_hash", name="uniq_analysis_cache"),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)

daily_report_runs = Table(
    DAILY_REPORT_RUNS_TABLE_NAME,
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("window_start_at", DateTime, nullable=True),
    Column("window_end_at", DateTime, nullable=False),
    Column("records_loaded", BigInteger, nullable=False, server_default=text("0")),
    Column("candidate_outcomes", BigInteger, nullable=False, server_default=text("0")),
    Column("detail_pdf_path", Text, nullable=True),
    Column("detail_pdf_url", Text, nullable=True),
    Column("sent_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)

def build_mysql_connection_kwargs(settings: Settings) -> dict[str, object]:
    return {
        "host": settings.mysql_host,
        "port": settings.mysql_port,
        "user": settings.mysql_user,
        "password": settings.mysql_password,
        "database": settings.mysql_database,
        "charset": settings.mysql_charset,
    }


@lru_cache(maxsize=8)
def _build_engine(
    host: str,
    port: int,
    user: str,
    password: str,
    database: str,
    charset: str,
) -> Engine:
    url = URL.create(
        "mysql+pymysql",
        username=user,
        password=password,
        host=host,
        port=port,
        database=database,
        query={"charset": charset},
    )
    return create_engine(
        url,
        future=True,
        poolclass=QueuePool,
        pool_pre_ping=True,
        pool_recycle=1800,
    )


def get_engine(settings: Settings) -> Engine:
    kwargs = build_mysql_connection_kwargs(settings)
    missing = [key for key in ("host", "user", "password") if not kwargs.get(key)]
    if missing:
        raise RuntimeError(
            "MySQL connection is not configured. Missing env vars: "
            + ", ".join(f"MYSQL_{item.upper()}" for item in missing)
        )
    return _build_engine(
        str(kwargs["host"]),
        int(kwargs["port"]),
        str(kwargs["user"]),
        str(kwargs["password"]),
        str(kwargs["database"]),
        str(kwargs["charset"]),
    )


def connect_mysql(settings: Settings):
    return get_engine(settings).raw_connection()


def execute_sql_script(settings: Settings, sql: str) -> None:
    engine = get_engine(settings)
    statements = [item.strip() for item in sql.split(";") if item.strip()]
    with engine.begin() as connection:
        for statement in statements:
            connection.exec_driver_sql(statement)


def ensure_resume_experience_schema(settings: Settings) -> None:
    engine = get_engine(settings)
    metadata.create_all(engine, checkfirst=True)
    _ensure_resume_experience_columns(engine)
    _ensure_analysis_cache_columns(engine)
    _ensure_daily_report_runs_columns(engine)


def drop_legacy_resume_tables(settings: Settings) -> None:
    execute_sql_script(
        settings,
        f"""
        DROP TABLE IF EXISTS {LEGACY_WORK_EXPERIENCE_TABLE_NAME};
        DROP TABLE IF EXISTS {LEGACY_RESUME_COLLECTION_TABLE_NAME}
        """,
    )


def reset_resume_experience_tables(settings: Settings) -> None:
    execute_sql_script(
        settings,
        f"""
        DROP TABLE IF EXISTS {RESUME_ANALYSIS_CACHE_TABLE_NAME};
        DROP TABLE IF EXISTS {RESUME_SEARCH_HITS_TABLE_NAME};
        DROP TABLE IF EXISTS {RESUME_EXPERIENCE_TABLE_NAME}
        """,
    )
    ensure_resume_experience_schema(settings)


def build_candidate_fingerprint(
    *,
    candidate_name: str | None,
    structured_resume: Mapping[str, object] | None = None,
    experience: str | None = None,
) -> str:
    basic_info = structured_resume.get("basic_info") if isinstance(structured_resume, Mapping) else None
    work_experiences = (
        structured_resume.get("work_experiences") if isinstance(structured_resume, Mapping) else None
    )
    first_work = work_experiences[0] if isinstance(work_experiences, Sequence) and work_experiences else None

    stable_parts = [
        _normalize_fingerprint_part(candidate_name),
        _normalize_fingerprint_part(_nested_mapping_str(basic_info, "current_company")),
        _normalize_fingerprint_part(_nested_mapping_str(basic_info, "current_position")),
        _normalize_fingerprint_part(_nested_mapping_str(basic_info, "city")),
        _normalize_fingerprint_part(_mapping_number_as_str(basic_info, "age")),
        _normalize_fingerprint_part(_nested_mapping_str(basic_info, "education")),
        _normalize_fingerprint_part(_mapping_number_as_str(basic_info, "work_years")),
        _normalize_fingerprint_part(_nested_mapping_str(_mapping_value(first_work, "company"), "raw_name")),
        _normalize_fingerprint_part(_nested_mapping_str(_mapping_value(first_work, "position"), "raw_title")),
    ]
    compact_parts = [part for part in stable_parts if part]
    if len(compact_parts) < 3:
        compact_parts.append(_normalize_fingerprint_part((experience or "")[:160]))
    digest_source = "|".join(compact_parts) or "unknown_candidate"
    return xxhash.xxh3_128_hexdigest(digest_source.encode("utf-8"))


def build_experience_hash(experience: str | None) -> str:
    normalized = _normalize_experience_text(experience)
    return xxhash.xxh3_128_hexdigest(normalized.encode("utf-8"))


def upsert_contacted_candidate_record(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    candidate_name: str | None,
    search_keyword: str | None,
    experience: str | None,
    match_score: int | None = None,
    source_platform: str = "liepin",
    analysis_status: str = DEFAULT_ANALYSIS_STATUS,
) -> int:
    ensure_resume_experience_schema(settings)
    LOGGER.info(
        "Upserting contacted candidate record",
        extra={
            "event": "db_upsert_contact_start",
            "context": {
                "candidate_name": candidate_name,
                "candidate_fingerprint": candidate_fingerprint,
                "search_keyword": search_keyword,
                "analysis_status": analysis_status,
                "match_score": match_score,
                "experience_length": len(experience or ""),
            },
        },
    )
    stmt = mysql_insert(resume_experience_records).values(
        candidate_fingerprint=candidate_fingerprint,
        candidate_name=_string_or_none(candidate_name),
        source_platform=_string_or_none(source_platform) or "liepin",
        search_keyword=_string_or_none(search_keyword),
        experience=_string_or_none(experience),
        match_score=match_score,
        analysis_status=_string_or_none(analysis_status) or DEFAULT_ANALYSIS_STATUS,
    )
    stmt = stmt.on_duplicate_key_update(
        candidate_name=stmt.inserted.candidate_name,
        source_platform=stmt.inserted.source_platform,
        search_keyword=stmt.inserted.search_keyword,
        experience=stmt.inserted.experience,
        match_score=stmt.inserted.match_score,
        analysis_status=stmt.inserted.analysis_status,
        updated_at=func.current_timestamp(),
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        connection.execute(stmt)
        record_id = connection.execute(
            select(resume_experience_records.c.id).where(
                resume_experience_records.c.candidate_fingerprint == candidate_fingerprint
            )
        ).scalar_one()
    LOGGER.info(
        "Upserted contacted candidate record",
        extra={
            "event": "db_upsert_contact_complete",
            "context": {
                "record_id": int(record_id),
                "candidate_name": candidate_name,
                "candidate_fingerprint": candidate_fingerprint,
            },
        },
    )
    return int(record_id)


def get_candidate_record_by_fingerprint(
    settings: Settings,
    candidate_fingerprint: str,
) -> dict[str, object] | None:
    ensure_resume_experience_schema(settings)
    engine = get_engine(settings)
    with engine.begin() as connection:
        row = connection.execute(
            select(
                resume_experience_records.c.id,
                resume_experience_records.c.candidate_fingerprint,
                resume_experience_records.c.candidate_name,
                resume_experience_records.c.source_platform,
                resume_experience_records.c.search_keyword,
                resume_experience_records.c.experience,
                resume_experience_records.c.match_score,
                resume_experience_records.c.analysis_status,
                resume_experience_records.c.created_at,
                resume_experience_records.c.updated_at,
            ).where(resume_experience_records.c.candidate_fingerprint == candidate_fingerprint)
        ).mappings().first()
    return dict(row) if row else None


def list_contacted_candidate_records(
    settings: Settings,
    *,
    limit: int | None = None,
    updated_from: datetime | None = None,
    updated_before: datetime | None = None,
) -> list[dict[str, object]]:
    ensure_resume_experience_schema(settings)
    engine = get_engine(settings)
    stmt = (
        select(
            resume_experience_records.c.id,
            resume_experience_records.c.candidate_fingerprint,
            resume_experience_records.c.candidate_name,
            resume_experience_records.c.source_platform,
            resume_experience_records.c.search_keyword,
            resume_experience_records.c.experience,
            resume_experience_records.c.match_score,
            resume_experience_records.c.analysis_status,
            resume_experience_records.c.created_at,
            resume_experience_records.c.updated_at,
        )
        .order_by(resume_experience_records.c.updated_at.desc(), resume_experience_records.c.id.desc())
    )
    if updated_from is not None:
        stmt = stmt.where(resume_experience_records.c.updated_at >= updated_from)
    if updated_before is not None:
        stmt = stmt.where(resume_experience_records.c.updated_at < updated_before)
    if isinstance(limit, int) and limit > 0:
        stmt = stmt.limit(limit)
    with engine.begin() as connection:
        rows = connection.execute(stmt).mappings().all()
    return [dict(row) for row in rows]


def get_latest_daily_report_run(settings: Settings) -> dict[str, object] | None:
    ensure_resume_experience_schema(settings)
    engine = get_engine(settings)
    with engine.begin() as connection:
        row = connection.execute(
            select(
                daily_report_runs.c.id,
                daily_report_runs.c.window_start_at,
                daily_report_runs.c.window_end_at,
                daily_report_runs.c.records_loaded,
                daily_report_runs.c.candidate_outcomes,
                daily_report_runs.c.detail_pdf_path,
                daily_report_runs.c.detail_pdf_url,
                daily_report_runs.c.sent_at,
            )
            .order_by(daily_report_runs.c.sent_at.desc(), daily_report_runs.c.id.desc())
            .limit(1)
        ).mappings().first()
    return dict(row) if row else None


def record_daily_report_run(
    settings: Settings,
    *,
    window_start_at: datetime | None,
    window_end_at: datetime,
    records_loaded: int,
    candidate_outcomes: int,
    detail_pdf_path: str | None,
    detail_pdf_url: str | None,
) -> int:
    ensure_resume_experience_schema(settings)
    stmt = insert(daily_report_runs).values(
        window_start_at=window_start_at,
        window_end_at=window_end_at,
        records_loaded=records_loaded,
        candidate_outcomes=candidate_outcomes,
        detail_pdf_path=_string_or_none(detail_pdf_path),
        detail_pdf_url=_string_or_none(detail_pdf_url),
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        result = connection.execute(stmt)
        return int(result.inserted_primary_key[0])


def was_contacted_within_days(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    days: int,
) -> bool:
    record = get_candidate_record_by_fingerprint(settings, candidate_fingerprint)
    if record is None:
        return False
    updated_at = record.get("updated_at")
    if not isinstance(updated_at, datetime):
        return False
    now = datetime.now(updated_at.tzinfo) if updated_at.tzinfo else datetime.now()
    return updated_at >= now - timedelta(days=days)


def record_candidate_search_hit(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    candidate_name: str | None,
    search_keyword: str,
    hit_status: str,
) -> int:
    ensure_resume_experience_schema(settings)
    stmt = insert(resume_search_hits).values(
        candidate_fingerprint=candidate_fingerprint,
        candidate_name=_string_or_none(candidate_name),
        search_keyword=search_keyword,
        hit_status=hit_status,
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        result = connection.execute(stmt)
        hit_id = int(result.inserted_primary_key[0])
    return hit_id


def get_analysis_cache_record(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    experience_hash: str,
) -> dict[str, object] | None:
    ensure_resume_experience_schema(settings)
    engine = get_engine(settings)
    with engine.begin() as connection:
        row = connection.execute(
            select(
                resume_analysis_cache.c.id,
                resume_analysis_cache.c.candidate_fingerprint,
                resume_analysis_cache.c.experience_hash,
                resume_analysis_cache.c.analysis_status,
                resume_analysis_cache.c.match_result,
                resume_analysis_cache.c.match_score,
                resume_analysis_cache.c.score_breakdown_json,
                resume_analysis_cache.c.analysis_reason,
                resume_analysis_cache.c.evidence_summary_json,
                resume_analysis_cache.c.created_at,
                resume_analysis_cache.c.updated_at,
            ).where(
                (resume_analysis_cache.c.candidate_fingerprint == candidate_fingerprint)
                & (resume_analysis_cache.c.experience_hash == experience_hash)
            )
        ).mappings().first()
    return dict(row) if row else None


def upsert_analysis_cache_record(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    experience_hash: str,
    analysis_status: str,
    match_result: str | None,
    match_score: int | None = None,
    score_breakdown: Mapping[str, int] | None = None,
    analysis_reason: str | None = None,
    evidence_summary: Sequence[str] | None = None,
) -> int:
    ensure_resume_experience_schema(settings)
    stmt = mysql_insert(resume_analysis_cache).values(
        candidate_fingerprint=candidate_fingerprint,
        experience_hash=experience_hash,
        analysis_status=analysis_status,
        match_result=_string_or_none(match_result),
        match_score=match_score,
        score_breakdown_json=_json_string_or_none(score_breakdown),
        analysis_reason=_string_or_none(analysis_reason),
        evidence_summary_json=_json_string_or_none(evidence_summary),
    )
    stmt = stmt.on_duplicate_key_update(
        analysis_status=stmt.inserted.analysis_status,
        match_result=stmt.inserted.match_result,
        match_score=stmt.inserted.match_score,
        score_breakdown_json=stmt.inserted.score_breakdown_json,
        analysis_reason=stmt.inserted.analysis_reason,
        evidence_summary_json=stmt.inserted.evidence_summary_json,
        updated_at=func.current_timestamp(),
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        connection.execute(stmt)
        cache_id = connection.execute(
            select(resume_analysis_cache.c.id).where(
                (resume_analysis_cache.c.candidate_fingerprint == candidate_fingerprint)
                & (resume_analysis_cache.c.experience_hash == experience_hash)
            )
        ).scalar_one()
    return int(cache_id)


def build_candidate_experience_text(
    *,
    sections: Mapping[str, object] | None = None,
    structured_resume: Mapping[str, object] | None = None,
) -> str | None:
    blocks: list[str] = []

    profile_line = _build_profile_line_from_structured_resume(structured_resume)
    if profile_line:
        blocks.append(PROFILE_PREFIX + profile_line)

    work_lines = _build_work_lines_from_structured_resume(structured_resume)
    if not work_lines:
        work_lines = _extract_section_lines(sections, "工作经历")
    if work_lines:
        blocks.append(WORK_PREFIX + _join_experience_items(work_lines))

    project_lines = _build_project_lines_from_structured_resume(structured_resume)
    if not project_lines:
        project_lines = _extract_section_lines(sections, "项目经历")
    if project_lines:
        blocks.append(PROJECT_PREFIX + _join_experience_items(project_lines))

    if not blocks:
        return None
    return BLOCK_SEPARATOR.join(blocks)


def _build_profile_line_from_structured_resume(
    structured_resume: Mapping[str, object] | None,
) -> str | None:
    if not isinstance(structured_resume, Mapping):
        return None
    basic_info = structured_resume.get("basic_info")
    if not isinstance(basic_info, Mapping):
        return None
    age = _mapping_number_as_str(basic_info, "age")
    if not age:
        return None
    return f"年龄：{age}岁"


def _extract_section_lines(
    sections: Mapping[str, object] | None,
    section_name: str,
) -> list[str]:
    if not isinstance(sections, Mapping):
        return []
    raw_lines = sections.get(section_name)
    if not isinstance(raw_lines, Sequence) or isinstance(raw_lines, (str, bytes)):
        return []
    lines: list[str] = []
    for item in raw_lines:
        cleaned = _string_or_none(item)
        if cleaned:
            lines.append(cleaned)
    return lines


def _build_work_lines_from_structured_resume(
    structured_resume: Mapping[str, object] | None,
) -> list[str]:
    if not isinstance(structured_resume, Mapping):
        return []
    work_experiences = structured_resume.get("work_experiences")
    if not isinstance(work_experiences, Sequence) or isinstance(work_experiences, (str, bytes)):
        return []

    lines: list[str] = []
    for experience in work_experiences:
        if not isinstance(experience, Mapping):
            continue
        block_lines = _build_work_experience_lines(experience)
        if block_lines:
            lines.extend(block_lines)
    return lines


def _build_project_lines_from_structured_resume(
    structured_resume: Mapping[str, object] | None,
) -> list[str]:
    if not isinstance(structured_resume, Mapping):
        return []
    project_experiences = structured_resume.get("project_experiences")
    if not isinstance(project_experiences, Sequence) or isinstance(project_experiences, (str, bytes)):
        return []

    lines: list[str] = []
    for project in project_experiences:
        if not isinstance(project, Mapping):
            continue
        project_name = _string_or_none(project.get("project_name"))
        role_name = _string_or_none(project.get("role_name"))
        start_date = _string_or_none(project.get("start_date"))
        end_date = _string_or_none(project.get("end_date"))
        raw_description = _string_or_none(project.get("raw_description"))

        if project_name:
            lines.append(project_name)
        if role_name:
            lines.append(role_name)
        if start_date or end_date:
            lines.append(f"{start_date or ''}-{end_date or ''}".strip("-"))
        if raw_description:
            lines.append(raw_description)
    return lines


def _build_work_experience_lines(experience: Mapping[str, object]) -> list[str]:
    lines: list[str] = []

    company_name = _nested_mapping_str(experience.get("company"), "raw_name")
    position_name = _nested_mapping_str(experience.get("position"), "raw_title")
    period = experience.get("employment_period")
    raw_description = _string_or_none(experience.get("raw_description"))
    responsibilities = _extract_string_list(experience.get("responsibilities"))
    achievements = _extract_string_list(experience.get("achievements"))

    if company_name:
        lines.append(company_name)
    if position_name:
        lines.append(position_name)
    if isinstance(period, Mapping):
        period_text = _string_or_none(period.get("raw_text"))
        if period_text:
            lines.append(period_text)

    if raw_description:
        lines.append(raw_description)
    else:
        lines.extend(responsibilities)
        lines.extend(achievements)

    return lines


def _extract_string_list(value: object) -> list[str]:
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes)):
        return []
    result: list[str] = []
    for item in value:
        cleaned = _string_or_none(item)
        if cleaned:
            result.append(cleaned)
    return result


def _mapping_number_as_str(payload: object, key: str) -> str | None:
    if not isinstance(payload, Mapping):
        return None
    value = payload.get(key)
    if isinstance(value, (int, float)):
        return str(int(value) if isinstance(value, float) and value.is_integer() else value)
    return _string_or_none(value)


def _mapping_value(payload: object, key: str) -> object:
    if not isinstance(payload, Mapping):
        return None
    return payload.get(key)


def _nested_mapping_str(payload: object, key: str) -> str | None:
    if not isinstance(payload, Mapping):
        return None
    return _string_or_none(payload.get(key))


def _normalize_fingerprint_part(value: str | None) -> str:
    if not value:
        return ""
    return " ".join(value.strip().split())


def _normalize_experience_text(value: str | None) -> str:
    if not value:
        return ""
    compact = " ".join(value.strip().split())
    return compact[:4000]


def _string_or_none(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = value.strip()
    return cleaned or None


def _json_string_or_none(value: object) -> str | None:
    if value is None:
        return None
    if isinstance(value, Mapping):
        payload = {str(key): item for key, item in value.items()}
    elif isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        payload = list(value)
    else:
        return None
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def _join_experience_items(items: Sequence[str]) -> str:
    cleaned_items = [item.strip() for item in items if isinstance(item, str) and item.strip()]
    return ITEM_SEPARATOR.join(cleaned_items)


def _ensure_analysis_cache_columns(engine: Engine) -> None:
    inspector = inspect(engine)
    try:
        column_names = {
            str(column.get("name"))
            for column in inspector.get_columns(RESUME_ANALYSIS_CACHE_TABLE_NAME)
        }
    except Exception:
        return
    alter_statements: list[str] = []
    if "match_score" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_ANALYSIS_CACHE_TABLE_NAME} ADD COLUMN match_score BIGINT NULL"
        )
    if "score_breakdown_json" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_ANALYSIS_CACHE_TABLE_NAME} ADD COLUMN score_breakdown_json TEXT NULL"
        )
    if "analysis_reason" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_ANALYSIS_CACHE_TABLE_NAME} ADD COLUMN analysis_reason TEXT NULL"
        )
    if "evidence_summary_json" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_ANALYSIS_CACHE_TABLE_NAME} ADD COLUMN evidence_summary_json TEXT NULL"
        )
    if not alter_statements:
        return
    with engine.begin() as connection:
        for statement in alter_statements:
            connection.exec_driver_sql(statement)


def _ensure_resume_experience_columns(engine: Engine) -> None:
    inspector = inspect(engine)
    try:
        column_names = {
            str(column.get("name"))
            for column in inspector.get_columns(RESUME_EXPERIENCE_TABLE_NAME)
        }
    except Exception:
        return
    alter_statements: list[str] = []
    if "match_score" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_EXPERIENCE_TABLE_NAME} ADD COLUMN match_score BIGINT NULL"
        )
    if not alter_statements:
        return
    with engine.begin() as connection:
        for statement in alter_statements:
            connection.exec_driver_sql(statement)


def _ensure_daily_report_runs_columns(engine: Engine) -> None:
    inspector = inspect(engine)
    try:
        column_names = {
            str(column.get("name"))
            for column in inspector.get_columns(DAILY_REPORT_RUNS_TABLE_NAME)
        }
    except Exception:
        return
    required_columns = {
        "window_start_at": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN window_start_at DATETIME NULL",
        "window_end_at": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN window_end_at DATETIME NOT NULL",
        "records_loaded": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN records_loaded BIGINT NOT NULL DEFAULT 0",
        "candidate_outcomes": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN candidate_outcomes BIGINT NOT NULL DEFAULT 0",
        "detail_pdf_path": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN detail_pdf_path TEXT NULL",
        "detail_pdf_url": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN detail_pdf_url TEXT NULL",
        "sent_at": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP",
    }
    alter_statements = [
        statement for column_name, statement in required_columns.items() if column_name not in column_names
    ]
    if not alter_statements:
        return
    with engine.begin() as connection:
        for statement in alter_statements:
            connection.exec_driver_sql(statement)
