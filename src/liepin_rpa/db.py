from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from contextlib import closing
from datetime import datetime, timedelta
from functools import lru_cache

import xxhash
from sqlalchemy import (
    TIMESTAMP,
    BigInteger,
    Column,
    DateTime,
    MetaData,
    String,
    Table,
    Text,
    UniqueConstraint,
    create_engine,
    func,
    insert,
    inspect,
    select,
    text,
    update,
)
from sqlalchemy.dialects.mysql import insert as mysql_insert
from sqlalchemy.engine import URL, Engine
from sqlalchemy.pool import QueuePool

from liepin_rpa.candidate_identity import canonicalize_candidate_name
from liepin_rpa.config import Settings
from liepin_rpa.logging_utils import get_logger

RESUME_EXPERIENCE_TABLE_NAME = "candidate_resume_experience_records"
RESUME_SEARCH_HITS_TABLE_NAME = "candidate_resume_search_hits"
RESUME_ANALYSIS_CACHE_TABLE_NAME = "candidate_resume_analysis_cache"
RESUME_SOURCE_LOCATORS_TABLE_NAME = "candidate_resume_source_locators"
DAILY_REPORT_RUNS_TABLE_NAME = "candidate_resume_daily_report_runs"
LEGACY_RESUME_COLLECTION_TABLE_NAME = "candidate_resume_collection_records"
LEGACY_WORK_EXPERIENCE_TABLE_NAME = "candidate_resume_work_experiences"

WORK_PREFIX = "工作内容："
PROJECT_PREFIX = "项目内容："
PROFILE_PREFIX = "候选人信息："
ITEM_SEPARATOR = "；"
BLOCK_SEPARATOR = " "
DEFAULT_ANALYSIS_STATUS = "未执行"
EXPERIENCE_HASH_VERSION = 2
LEGACY_ANALYSIS_VERSION = "legacy"
LOGGER = get_logger(__name__)

metadata = MetaData()

resume_experience_records = Table(
    RESUME_EXPERIENCE_TABLE_NAME,
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("candidate_fingerprint", String(64), nullable=False, unique=True, index=True),
    # A normalized resume hash is stable when the platform changes the displayed name
    # between masked and full-name variants of the same candidate.
    Column("experience_hash", String(64), nullable=True, unique=True),
    Column(
        "experience_hash_version",
        BigInteger,
        nullable=False,
        server_default=text(str(EXPERIENCE_HASH_VERSION)),
    ),
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
    Column("manual_review_status", String(64), nullable=True, index=True),
    Column("manual_review_feedback", Text, nullable=True),
    Column("manual_suppress_communication", BigInteger, nullable=False, server_default=text("0")),
    Column("manual_suppress_report", BigInteger, nullable=False, server_default=text("0")),
    Column("manual_reviewed_by", BigInteger, nullable=True, index=True),
    Column("manual_review_assignee_id", BigInteger, nullable=True, index=True),
    Column("manual_review_revision", BigInteger, nullable=False, server_default=text("0")),
    Column("manual_reviewed_at", DateTime, nullable=True),
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
    Column("search_batch_id", String(64), nullable=True, index=True),
    Column("hit_status", String(64), nullable=False, server_default=text("'hit'")),
    Column("created_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
)

resume_source_locators = Table(
    RESUME_SOURCE_LOCATORS_TABLE_NAME,
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("candidate_fingerprint", String(64), nullable=False, index=True),
    Column("experience_hash", String(64), nullable=True, index=True),
    Column("candidate_name", String(255), nullable=True, index=True),
    Column("source_platform", String(64), nullable=False, server_default=text("'liepin'")),
    Column("source_account_alias", String(128), nullable=True, index=True),
    Column("source_device_id", String(128), nullable=True),
    Column("job_profile_id", String(128), nullable=True, index=True),
    Column("search_keyword", String(255), nullable=False, index=True),
    Column("search_pack_id", String(128), nullable=True),
    Column("search_batch_id", String(64), nullable=True, index=True),
    Column("result_iteration", BigInteger, nullable=True),
    Column("result_rank", BigInteger, nullable=True),
    Column("candidate_card_fingerprint", String(255), nullable=True, index=True),
    Column("candidate_card_text", Text, nullable=True),
    Column("platform_candidate_id", String(255), nullable=True, index=True),
    Column("platform_resume_id", String(255), nullable=True, index=True),
    Column("detail_deep_link", Text, nullable=True),
    Column("detail_activity", String(255), nullable=True),
    Column("detail_hierarchy_hash", String(64), nullable=True, index=True),
    Column("locator_quality", String(32), nullable=False, server_default=text("'snapshot'")),
    Column("detail_screenshot_path", Text, nullable=True),
    Column("platform_interaction_note", Text, nullable=True),
    Column("created_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
    mysql_engine="InnoDB",
)

resume_analysis_cache = Table(
    RESUME_ANALYSIS_CACHE_TABLE_NAME,
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("candidate_fingerprint", String(64), nullable=False, index=True),
    Column("experience_hash", String(64), nullable=False),
    Column("experience", Text, nullable=True),
    Column(
        "analysis_version",
        String(64),
        nullable=False,
        server_default=text(f"'{LEGACY_ANALYSIS_VERSION}'"),
    ),
    Column("analysis_status", String(64), nullable=False),
    Column("match_result", String(64), nullable=True),
    Column("match_score", BigInteger, nullable=True),
    Column("score_breakdown_json", Text, nullable=True),
    Column("analysis_reason", Text, nullable=True),
    Column("evidence_summary_json", Text, nullable=True),
    Column("created_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", TIMESTAMP, nullable=False, server_default=func.current_timestamp()),
    UniqueConstraint(
        "candidate_fingerprint",
        "experience_hash",
        "analysis_version",
        name="uniq_analysis_cache_version",
    ),
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
    Column("report_key", String(64), nullable=True, unique=True),
    Column("search_batch_id", String(64), nullable=True, unique=True),
    Column("delivery_status", String(32), nullable=False, server_default=text("'sent'")),
    Column("detail_pdf_path", Text, nullable=True),
    Column("detail_pdf_url", Text, nullable=True),
    Column("uploaded_at", DateTime, nullable=True),
    Column("feishu_attempted_at", DateTime, nullable=True),
    Column("sent_at", DateTime, nullable=False, server_default=func.current_timestamp()),
    Column("updated_at", DateTime, nullable=False, server_default=func.current_timestamp()),
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
    _ensure_search_hit_columns(engine)
    _ensure_source_locator_columns(engine)
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
        DROP TABLE IF EXISTS {RESUME_SOURCE_LOCATORS_TABLE_NAME};
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
    basic_info = (
        structured_resume.get("basic_info") if isinstance(structured_resume, Mapping) else None
    )
    work_experiences = (
        structured_resume.get("work_experiences")
        if isinstance(structured_resume, Mapping)
        else None
    )
    first_work = (
        work_experiences[0] if isinstance(work_experiences, Sequence) and work_experiences else None
    )

    stable_parts = [
        _normalize_fingerprint_part(canonicalize_candidate_name(candidate_name)),
        _normalize_fingerprint_part(_nested_mapping_str(basic_info, "current_company")),
        _normalize_fingerprint_part(_nested_mapping_str(basic_info, "current_position")),
        _normalize_fingerprint_part(_nested_mapping_str(basic_info, "city")),
        _normalize_fingerprint_part(_mapping_number_as_str(basic_info, "age")),
        _normalize_fingerprint_part(_nested_mapping_str(basic_info, "education")),
        _normalize_fingerprint_part(_mapping_number_as_str(basic_info, "work_years")),
        _normalize_fingerprint_part(
            _nested_mapping_str(_mapping_value(first_work, "company"), "raw_name")
        ),
        _normalize_fingerprint_part(
            _nested_mapping_str(_mapping_value(first_work, "position"), "raw_title")
        ),
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
    experience_hash: str | None = None,
    match_score: int | None = None,
    source_platform: str = "liepin",
    analysis_status: str = DEFAULT_ANALYSIS_STATUS,
) -> int:
    ensure_resume_experience_schema(settings)
    normalized_experience_hash = experience_hash or _experience_hash_or_none(experience)
    if normalized_experience_hash:
        canonical_record = get_candidate_record_by_experience_hash(
            settings,
            normalized_experience_hash,
        )
        if canonical_record is not None:
            candidate_fingerprint = str(
                canonical_record.get("candidate_fingerprint") or candidate_fingerprint
            )
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
        experience_hash=normalized_experience_hash,
        experience_hash_version=EXPERIENCE_HASH_VERSION,
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
        experience_hash=stmt.inserted.experience_hash,
        experience_hash_version=stmt.inserted.experience_hash_version,
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


def update_candidate_manual_review(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    review_status: str,
    feedback: str,
    suppress_communication: bool = True,
    suppress_report: bool = True,
) -> int:
    """Persist an HR disposition separately from the original AI analysis."""

    ensure_resume_experience_schema(settings)
    normalized_status = _string_or_none(review_status)
    normalized_feedback = _string_or_none(feedback)
    if not normalized_status or not normalized_feedback:
        raise ValueError("review_status and feedback must not be empty")
    stmt = (
        update(resume_experience_records)
        .where(resume_experience_records.c.candidate_fingerprint == candidate_fingerprint)
        .values(
            manual_review_status=normalized_status,
            manual_review_feedback=normalized_feedback,
            manual_suppress_communication=1 if suppress_communication else 0,
            manual_suppress_report=1 if suppress_report else 0,
            manual_reviewed_at=func.current_timestamp(),
            updated_at=func.current_timestamp(),
        )
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        result = connection.execute(stmt)
    return int(result.rowcount or 0)


def get_candidate_record_by_fingerprint(
    settings: Settings,
    candidate_fingerprint: str,
) -> dict[str, object] | None:
    ensure_resume_experience_schema(settings)
    engine = get_engine(settings)
    with engine.begin() as connection:
        row = (
            connection.execute(
                _candidate_record_select().where(
                    resume_experience_records.c.candidate_fingerprint == candidate_fingerprint
                )
            )
            .mappings()
            .first()
        )
    return dict(row) if row else None


def get_candidate_record_by_experience_hash(
    settings: Settings,
    experience_hash: str,
) -> dict[str, object] | None:
    """Return the canonical contacted record for an identical normalized resume."""
    if not experience_hash:
        return None
    ensure_resume_experience_schema(settings)
    engine = get_engine(settings)
    with engine.begin() as connection:
        row = (
            connection.execute(
                _candidate_record_select().where(
                    resume_experience_records.c.experience_hash == experience_hash
                )
            )
            .mappings()
            .first()
        )
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
    stmt = select(
        resume_experience_records.c.id,
        resume_experience_records.c.candidate_fingerprint,
        resume_experience_records.c.experience_hash,
        resume_experience_records.c.experience_hash_version,
        resume_experience_records.c.candidate_name,
        resume_experience_records.c.source_platform,
        resume_experience_records.c.search_keyword,
        resume_experience_records.c.experience,
        resume_experience_records.c.match_score,
        resume_experience_records.c.analysis_status,
        resume_experience_records.c.manual_review_status,
        resume_experience_records.c.manual_review_feedback,
        resume_experience_records.c.manual_suppress_communication,
        resume_experience_records.c.manual_suppress_report,
        resume_experience_records.c.manual_reviewed_at,
        resume_experience_records.c.created_at,
        resume_experience_records.c.updated_at,
    ).order_by(resume_experience_records.c.updated_at.desc(), resume_experience_records.c.id.desc())
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
        row = (
            connection.execute(
                select(
                    daily_report_runs.c.id,
                    daily_report_runs.c.window_start_at,
                    daily_report_runs.c.window_end_at,
                    daily_report_runs.c.records_loaded,
                    daily_report_runs.c.candidate_outcomes,
                    daily_report_runs.c.report_key,
                    daily_report_runs.c.search_batch_id,
                    daily_report_runs.c.delivery_status,
                    daily_report_runs.c.detail_pdf_path,
                    daily_report_runs.c.detail_pdf_url,
                    daily_report_runs.c.sent_at,
                    daily_report_runs.c.updated_at,
                )
                .where(daily_report_runs.c.delivery_status == "sent")
                .order_by(daily_report_runs.c.sent_at.desc(), daily_report_runs.c.id.desc())
                .limit(1)
            )
            .mappings()
            .first()
        )
    return dict(row) if row else None


def start_daily_report_delivery(
    settings: Settings,
    *,
    report_key: str,
    window_start_at: datetime | None,
    window_end_at: datetime,
    records_loaded: int,
    candidate_outcomes: int,
    search_batch_id: str | None = None,
) -> dict[str, object]:
    ensure_resume_experience_schema(settings)
    stmt = mysql_insert(daily_report_runs).values(
        report_key=report_key,
        search_batch_id=_string_or_none(search_batch_id),
        window_start_at=window_start_at,
        window_end_at=window_end_at,
        records_loaded=records_loaded,
        candidate_outcomes=candidate_outcomes,
        delivery_status="pending",
    )
    stmt = stmt.on_duplicate_key_update(
        search_batch_id=stmt.inserted.search_batch_id,
        window_end_at=stmt.inserted.window_end_at,
        records_loaded=stmt.inserted.records_loaded,
        candidate_outcomes=stmt.inserted.candidate_outcomes,
        updated_at=func.current_timestamp(),
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        connection.execute(stmt)
        row = (
            connection.execute(
                select(daily_report_runs).where(daily_report_runs.c.report_key == report_key)
            )
            .mappings()
            .one()
        )
    return dict(row)


def update_daily_report_delivery(
    settings: Settings,
    *,
    report_key: str,
    delivery_status: str,
    detail_pdf_path: str | None = None,
    detail_pdf_url: str | None = None,
    uploaded: bool = False,
    feishu_attempted: bool = False,
) -> None:
    ensure_resume_experience_schema(settings)
    values: dict[str, object] = {
        "delivery_status": delivery_status,
        "updated_at": func.current_timestamp(),
    }
    if detail_pdf_path is not None:
        values["detail_pdf_path"] = _string_or_none(detail_pdf_path)
    if detail_pdf_url is not None:
        values["detail_pdf_url"] = _string_or_none(detail_pdf_url)
    if uploaded:
        values["uploaded_at"] = func.current_timestamp()
    if feishu_attempted:
        values["feishu_attempted_at"] = func.current_timestamp()
    engine = get_engine(settings)
    with engine.begin() as connection:
        connection.execute(
            daily_report_runs.update()
            .where(daily_report_runs.c.report_key == report_key)
            .values(**values)
        )


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
    search_batch_id: str | None = None,
) -> int:
    ensure_resume_experience_schema(settings)
    stmt = insert(resume_search_hits).values(
        candidate_fingerprint=candidate_fingerprint,
        candidate_name=_string_or_none(candidate_name),
        search_keyword=search_keyword,
        search_batch_id=_string_or_none(search_batch_id),
        hit_status=hit_status,
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        result = connection.execute(stmt)
        hit_id = int(result.inserted_primary_key[0])
    return hit_id


def record_candidate_source_locator(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    experience_hash: str | None,
    candidate_name: str | None,
    search_keyword: str,
    source_account_alias: str | None = None,
    source_device_id: str | None = None,
    job_profile_id: str | None = None,
    search_pack_id: str | None = None,
    search_batch_id: str | None = None,
    result_iteration: int | None = None,
    result_rank: int | None = None,
    candidate_card_fingerprint: str | None = None,
    candidate_card_text: str | None = None,
    platform_candidate_id: str | None = None,
    platform_resume_id: str | None = None,
    detail_deep_link: str | None = None,
    detail_activity: str | None = None,
    detail_hierarchy_hash: str | None = None,
    locator_quality: str = "snapshot",
    detail_screenshot_path: str | None = None,
    platform_interaction_note: str | None = None,
) -> int:
    """Persist one account-specific observation used to locate a resume again."""

    ensure_resume_experience_schema(settings)
    stmt = insert(resume_source_locators).values(
        candidate_fingerprint=candidate_fingerprint,
        experience_hash=_string_or_none(experience_hash),
        candidate_name=_string_or_none(candidate_name),
        source_platform="liepin",
        source_account_alias=_string_or_none(source_account_alias),
        source_device_id=_string_or_none(source_device_id),
        job_profile_id=_string_or_none(job_profile_id),
        search_keyword=search_keyword,
        search_pack_id=_string_or_none(search_pack_id),
        search_batch_id=_string_or_none(search_batch_id),
        result_iteration=result_iteration,
        result_rank=result_rank,
        candidate_card_fingerprint=_string_or_none(candidate_card_fingerprint),
        candidate_card_text=_string_or_none(candidate_card_text),
        platform_candidate_id=_string_or_none(platform_candidate_id),
        platform_resume_id=_string_or_none(platform_resume_id),
        detail_deep_link=_string_or_none(detail_deep_link),
        detail_activity=_string_or_none(detail_activity),
        detail_hierarchy_hash=_string_or_none(detail_hierarchy_hash),
        locator_quality=_string_or_none(locator_quality) or "snapshot",
        detail_screenshot_path=_string_or_none(detail_screenshot_path),
        platform_interaction_note=_string_or_none(platform_interaction_note),
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        result = connection.execute(stmt)
        return int(result.inserted_primary_key[0])


def update_candidate_source_locator_share_link(
    settings: Settings,
    *,
    locator_id: int,
    share_link: str,
) -> None:
    """Promote an official Liepin share URL to the primary saved locator."""

    ensure_resume_experience_schema(settings)
    normalized_link = _string_or_none(share_link)
    if not normalized_link:
        raise ValueError("share_link must not be empty")
    stmt = (
        update(resume_source_locators)
        .where(resume_source_locators.c.id == int(locator_id))
        .values(detail_deep_link=normalized_link, locator_quality="share_link")
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        connection.execute(stmt)


def update_candidate_source_locator_favorite(
    settings: Settings,
    *,
    locator_id: int,
) -> None:
    """Record that the candidate was preserved through Liepin Favorites."""

    ensure_resume_experience_schema(settings)
    stmt = (
        update(resume_source_locators)
        .where(resume_source_locators.c.id == int(locator_id))
        .values(locator_quality="favorite")
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        connection.execute(stmt)


def update_candidate_source_locator_identity(
    settings: Settings,
    *,
    locator_id: int,
    candidate_fingerprint: str,
    experience_hash: str | None,
    candidate_name: str | None,
) -> None:
    """Attach the final resume identity to a locator captured on detail entry."""

    ensure_resume_experience_schema(settings)
    stmt = (
        update(resume_source_locators)
        .where(resume_source_locators.c.id == int(locator_id))
        .values(
            candidate_fingerprint=candidate_fingerprint,
            experience_hash=_string_or_none(experience_hash),
            candidate_name=_string_or_none(candidate_name),
        )
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        connection.execute(stmt)


def list_candidate_source_locators(
    settings: Settings,
    *,
    candidate_fingerprint: str | None = None,
    experience_hash: str | None = None,
    platform_candidate_id: str | None = None,
    platform_resume_id: str | None = None,
    limit: int = 50,
) -> list[dict[str, object]]:
    """Find historical account observations for a known candidate or resume."""

    ensure_resume_experience_schema(settings)
    filters = []
    if candidate_fingerprint:
        filters.append(resume_source_locators.c.candidate_fingerprint == candidate_fingerprint)
    if experience_hash:
        filters.append(resume_source_locators.c.experience_hash == experience_hash)
    if platform_candidate_id:
        filters.append(resume_source_locators.c.platform_candidate_id == platform_candidate_id)
    if platform_resume_id:
        filters.append(resume_source_locators.c.platform_resume_id == platform_resume_id)
    if not filters:
        raise ValueError("At least one locator identity must be provided")
    stmt = (
        select(resume_source_locators)
        .where(*filters)
        .order_by(resume_source_locators.c.created_at.desc(), resume_source_locators.c.id.desc())
        .limit(max(1, int(limit)))
    )
    engine = get_engine(settings)
    with engine.begin() as connection:
        rows = connection.execute(stmt).mappings().all()
    return [dict(row) for row in rows]


def get_analysis_cache_record(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    experience_hash: str,
    analysis_version: str,
) -> dict[str, object] | None:
    ensure_resume_experience_schema(settings)
    engine = get_engine(settings)
    with engine.begin() as connection:
        row = (
            connection.execute(
                select(
                    resume_analysis_cache.c.id,
                    resume_analysis_cache.c.candidate_fingerprint,
                    resume_analysis_cache.c.experience_hash,
                    resume_analysis_cache.c.experience,
                    resume_analysis_cache.c.analysis_version,
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
                    & (resume_analysis_cache.c.analysis_version == analysis_version)
                )
            )
            .mappings()
            .first()
        )
    return dict(row) if row else None


def upsert_analysis_cache_record(
    settings: Settings,
    *,
    candidate_fingerprint: str,
    experience_hash: str,
    experience: str | None = None,
    analysis_version: str,
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
        experience=_string_or_none(experience),
        analysis_version=analysis_version,
        analysis_status=analysis_status,
        match_result=_string_or_none(match_result),
        match_score=match_score,
        score_breakdown_json=_json_string_or_none(score_breakdown),
        analysis_reason=_string_or_none(analysis_reason),
        evidence_summary_json=_json_string_or_none(evidence_summary),
    )
    stmt = stmt.on_duplicate_key_update(
        analysis_status=stmt.inserted.analysis_status,
        experience=stmt.inserted.experience,
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
                & (resume_analysis_cache.c.analysis_version == analysis_version)
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
    if not isinstance(project_experiences, Sequence) or isinstance(
        project_experiences, (str, bytes)
    ):
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
    return compact


def _experience_hash_or_none(experience: str | None) -> str | None:
    return build_experience_hash(experience) if _normalize_experience_text(experience) else None


def _candidate_record_select():
    return select(
        resume_experience_records.c.id,
        resume_experience_records.c.candidate_fingerprint,
        resume_experience_records.c.experience_hash,
        resume_experience_records.c.experience_hash_version,
        resume_experience_records.c.candidate_name,
        resume_experience_records.c.source_platform,
        resume_experience_records.c.search_keyword,
        resume_experience_records.c.experience,
        resume_experience_records.c.match_score,
        resume_experience_records.c.analysis_status,
        resume_experience_records.c.manual_review_status,
        resume_experience_records.c.manual_review_feedback,
        resume_experience_records.c.manual_suppress_communication,
        resume_experience_records.c.manual_suppress_report,
        resume_experience_records.c.manual_reviewed_at,
        resume_experience_records.c.created_at,
        resume_experience_records.c.updated_at,
    )


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
    if "experience" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_ANALYSIS_CACHE_TABLE_NAME} ADD COLUMN experience LONGTEXT NULL"
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
    if "analysis_version" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_ANALYSIS_CACHE_TABLE_NAME} "
            f"ADD COLUMN analysis_version VARCHAR(64) NOT NULL DEFAULT '{LEGACY_ANALYSIS_VERSION}'"
        )
    if alter_statements:
        with engine.begin() as connection:
            for statement in alter_statements:
                connection.exec_driver_sql(statement)
    _ensure_analysis_cache_unique_index(engine)


def _ensure_search_hit_columns(engine: Engine) -> None:
    inspector = inspect(engine)
    try:
        column_names = {
            str(column.get("name"))
            for column in inspector.get_columns(RESUME_SEARCH_HITS_TABLE_NAME)
        }
    except Exception:
        return
    if "search_batch_id" not in column_names:
        with engine.begin() as connection:
            connection.exec_driver_sql(
                f"ALTER TABLE {RESUME_SEARCH_HITS_TABLE_NAME} "
                "ADD COLUMN search_batch_id VARCHAR(64) NULL"
            )
    if not _has_index_for_column(engine, RESUME_SEARCH_HITS_TABLE_NAME, "search_batch_id"):
        with engine.begin() as connection:
            connection.exec_driver_sql(
                f"CREATE INDEX ix_candidate_search_hits_batch "
                f"ON {RESUME_SEARCH_HITS_TABLE_NAME} (search_batch_id)"
            )


def _ensure_source_locator_columns(engine: Engine) -> None:
    inspector = inspect(engine)
    try:
        column_names = {
            str(column.get("name"))
            for column in inspector.get_columns(RESUME_SOURCE_LOCATORS_TABLE_NAME)
        }
    except Exception:
        return
    alter_statements: list[str] = []
    if "platform_interaction_note" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_SOURCE_LOCATORS_TABLE_NAME} "
            "ADD COLUMN platform_interaction_note TEXT NULL"
        )
    if "search_batch_id" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_SOURCE_LOCATORS_TABLE_NAME} "
            "ADD COLUMN search_batch_id VARCHAR(64) NULL"
        )
    if alter_statements:
        with engine.begin() as connection:
            for statement in alter_statements:
                connection.exec_driver_sql(statement)
    if not _has_index_for_column(engine, RESUME_SOURCE_LOCATORS_TABLE_NAME, "search_batch_id"):
        with engine.begin() as connection:
            connection.exec_driver_sql(
                f"CREATE INDEX ix_candidate_source_locators_batch "
                f"ON {RESUME_SOURCE_LOCATORS_TABLE_NAME} (search_batch_id)"
            )


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
    if "experience_hash" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_EXPERIENCE_TABLE_NAME} ADD COLUMN experience_hash VARCHAR(64) NULL"
        )
    if "experience_hash_version" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_EXPERIENCE_TABLE_NAME} "
            f"ADD COLUMN experience_hash_version BIGINT NOT NULL DEFAULT {EXPERIENCE_HASH_VERSION}"
        )
    if "manual_review_status" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_EXPERIENCE_TABLE_NAME} ADD COLUMN manual_review_status VARCHAR(64) NULL"
        )
    if "manual_review_feedback" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_EXPERIENCE_TABLE_NAME} ADD COLUMN manual_review_feedback TEXT NULL"
        )
    if "manual_suppress_communication" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_EXPERIENCE_TABLE_NAME} "
            "ADD COLUMN manual_suppress_communication BIGINT NOT NULL DEFAULT 0"
        )
    if "manual_suppress_report" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_EXPERIENCE_TABLE_NAME} "
            "ADD COLUMN manual_suppress_report BIGINT NOT NULL DEFAULT 0"
        )
    if "manual_reviewed_at" not in column_names:
        alter_statements.append(
            f"ALTER TABLE {RESUME_EXPERIENCE_TABLE_NAME} ADD COLUMN manual_reviewed_at DATETIME NULL"
        )
    if alter_statements:
        with engine.begin() as connection:
            for statement in alter_statements:
                connection.exec_driver_sql(statement)

    upgraded_hashes, merged_count = _backfill_resume_experience_hashes(engine)
    if not _has_unique_index_for_column(engine, RESUME_EXPERIENCE_TABLE_NAME, "experience_hash"):
        merged_count += _merge_duplicate_resume_records(engine)
        _create_unique_experience_hash_index(engine)
    if upgraded_hashes or merged_count:
        LOGGER.info(
            "Completed candidate resume identity migration",
            extra={
                "event": "resume_identity_migration_complete",
                "context": {
                    "upgraded_hashes": upgraded_hashes,
                    "merged_duplicate_records": merged_count,
                    "experience_hash_version": EXPERIENCE_HASH_VERSION,
                },
            },
        )


def _has_unique_index_for_column(engine: Engine, table_name: str, column_name: str) -> bool:
    return _has_unique_index_for_columns(engine, table_name, (column_name,))


def _has_index_for_column(engine: Engine, table_name: str, column_name: str) -> bool:
    inspector = inspect(engine)
    try:
        return any(
            tuple(index.get("column_names") or []) == (column_name,)
            for index in inspector.get_indexes(table_name)
        )
    except Exception:
        return False


def _has_unique_index_for_columns(
    engine: Engine,
    table_name: str,
    column_names: tuple[str, ...],
) -> bool:
    inspector = inspect(engine)
    try:
        for constraint in inspector.get_unique_constraints(table_name):
            columns = constraint.get("column_names") or []
            if tuple(columns) == column_names:
                return True
        for index in inspector.get_indexes(table_name):
            columns = index.get("column_names") or []
            if index.get("unique") and tuple(columns) == column_names:
                return True
    except Exception:
        return False
    return False


def _ensure_analysis_cache_unique_index(engine: Engine) -> None:
    expected_columns = ("candidate_fingerprint", "experience_hash", "analysis_version")
    if _has_unique_index_for_columns(engine, RESUME_ANALYSIS_CACHE_TABLE_NAME, expected_columns):
        return
    inspector = inspect(engine)
    with engine.begin() as connection:
        dropped_index_names: set[str] = set()
        for constraint in inspector.get_unique_constraints(RESUME_ANALYSIS_CACHE_TABLE_NAME):
            columns = tuple(constraint.get("column_names") or [])
            name = str(constraint.get("name") or "")
            if columns == ("candidate_fingerprint", "experience_hash") and name:
                connection.exec_driver_sql(
                    f"ALTER TABLE {RESUME_ANALYSIS_CACHE_TABLE_NAME} DROP INDEX {name}"
                )
                dropped_index_names.add(name)
        for index in inspector.get_indexes(RESUME_ANALYSIS_CACHE_TABLE_NAME):
            columns = tuple(index.get("column_names") or [])
            name = str(index.get("name") or "")
            if (
                index.get("unique")
                and columns == ("candidate_fingerprint", "experience_hash")
                and name
                and name not in dropped_index_names
            ):
                connection.exec_driver_sql(
                    f"ALTER TABLE {RESUME_ANALYSIS_CACHE_TABLE_NAME} DROP INDEX {name}"
                )
        connection.exec_driver_sql(
            f"CREATE UNIQUE INDEX uniq_analysis_cache_version "
            f"ON {RESUME_ANALYSIS_CACHE_TABLE_NAME} "
            f"(candidate_fingerprint, experience_hash, analysis_version)"
        )


def _backfill_resume_experience_hashes(engine: Engine) -> tuple[int, int]:
    with engine.begin() as connection:
        rows = (
            connection.execute(
                _candidate_record_select().where(
                    (resume_experience_records.c.experience_hash.is_(None))
                    | (
                        resume_experience_records.c.experience_hash_version
                        != EXPERIENCE_HASH_VERSION
                    )
                )
            )
            .mappings()
            .all()
        )

        target_hash_by_id: dict[int, str | None] = {}
        grouped_records: dict[str, dict[int, dict[str, object]]] = {}
        for row in rows:
            record = dict(row)
            record_id = int(record["id"])
            experience_hash = _experience_hash_or_none(_string_or_none(record.get("experience")))
            target_hash_by_id[record_id] = experience_hash
            if experience_hash:
                grouped_records.setdefault(experience_hash, {})[record_id] = record

        if grouped_records:
            existing_rows = (
                connection.execute(
                    _candidate_record_select().where(
                        resume_experience_records.c.experience_hash.in_(tuple(grouped_records))
                    )
                )
                .mappings()
                .all()
            )
            for row in existing_rows:
                record = dict(row)
                experience_hash = _string_or_none(record.get("experience_hash"))
                if experience_hash in grouped_records:
                    grouped_records[experience_hash][int(record["id"])] = record

        merged_count = 0
        for records_by_id in grouped_records.values():
            if len(records_by_id) > 1:
                merged_count += _merge_resume_record_group(
                    connection,
                    list(records_by_id.values()),
                )

        updated_count = 0
        for row in rows:
            result = connection.execute(
                resume_experience_records.update()
                .where(resume_experience_records.c.id == row["id"])
                .values(
                    experience_hash=target_hash_by_id[int(row["id"])],
                    experience_hash_version=EXPERIENCE_HASH_VERSION,
                )
            )
            updated_count += int(result.rowcount or 0)
    return updated_count, merged_count


def _merge_duplicate_resume_records(engine: Engine) -> int:
    """Merge historical same-resume records while retaining hit and cache history."""
    with engine.begin() as connection:
        rows = (
            connection.execute(
                _candidate_record_select()
                .where(resume_experience_records.c.experience_hash.is_not(None))
                .order_by(
                    resume_experience_records.c.experience_hash.asc(),
                    resume_experience_records.c.updated_at.desc(),
                    resume_experience_records.c.id.desc(),
                )
            )
            .mappings()
            .all()
        )

        grouped: dict[str, list[dict[str, object]]] = {}
        for row in rows:
            record = dict(row)
            experience_hash = _string_or_none(record.get("experience_hash"))
            if experience_hash:
                grouped.setdefault(experience_hash, []).append(record)

        merged_count = 0
        for records in grouped.values():
            if len(records) < 2:
                continue
            merged_count += _merge_resume_record_group(connection, records)
    return merged_count


def _merge_resume_record_group(
    connection,
    records: list[dict[str, object]],
) -> int:
    records = sorted(
        records,
        key=lambda item: (
            item.get("updated_at")
            if isinstance(item.get("updated_at"), datetime)
            else datetime.min,
            int(item.get("id") or 0),
        ),
        reverse=True,
    )
    canonical = records[0]
    canonical_fingerprint = str(canonical["candidate_fingerprint"])
    canonical_name = _string_or_none(canonical.get("candidate_name"))
    merged_count = 0
    for duplicate in records[1:]:
        duplicate_fingerprint = str(duplicate["candidate_fingerprint"])
        duplicate_name = _string_or_none(duplicate.get("candidate_name"))
        if _is_preferred_candidate_name(duplicate_name, canonical_name):
            canonical_name = duplicate_name

        _merge_analysis_cache_rows(
            connection,
            canonical_fingerprint=canonical_fingerprint,
            duplicate_fingerprint=duplicate_fingerprint,
        )
        connection.execute(
            resume_search_hits.update()
            .where(resume_search_hits.c.candidate_fingerprint == duplicate_fingerprint)
            .values(candidate_fingerprint=canonical_fingerprint)
        )
        connection.execute(
            resume_experience_records.delete().where(
                resume_experience_records.c.id == duplicate["id"]
            )
        )
        merged_count += 1

    if canonical_name != _string_or_none(canonical.get("candidate_name")):
        connection.execute(
            resume_experience_records.update()
            .where(resume_experience_records.c.id == canonical["id"])
            .values(candidate_name=canonical_name)
        )
    return merged_count


def _merge_analysis_cache_rows(
    connection,
    *,
    canonical_fingerprint: str,
    duplicate_fingerprint: str,
) -> None:
    duplicate_rows = (
        connection.execute(
            select(resume_analysis_cache)
            .where(resume_analysis_cache.c.candidate_fingerprint == duplicate_fingerprint)
            .order_by(resume_analysis_cache.c.updated_at.desc(), resume_analysis_cache.c.id.desc())
        )
        .mappings()
        .all()
    )
    for duplicate in duplicate_rows:
        experience_hash = str(duplicate["experience_hash"])
        analysis_version = str(duplicate.get("analysis_version") or LEGACY_ANALYSIS_VERSION)
        canonical = (
            connection.execute(
                select(resume_analysis_cache).where(
                    (resume_analysis_cache.c.candidate_fingerprint == canonical_fingerprint)
                    & (resume_analysis_cache.c.experience_hash == experience_hash)
                    & (resume_analysis_cache.c.analysis_version == analysis_version)
                )
            )
            .mappings()
            .first()
        )
        if canonical is None:
            connection.execute(
                resume_analysis_cache.update()
                .where(resume_analysis_cache.c.id == duplicate["id"])
                .values(candidate_fingerprint=canonical_fingerprint)
            )
            continue
        if _cache_row_is_newer(duplicate, canonical):
            connection.execute(
                resume_analysis_cache.update()
                .where(resume_analysis_cache.c.id == canonical["id"])
                .values(
                    analysis_status=duplicate["analysis_status"],
                    match_result=duplicate["match_result"],
                    match_score=duplicate["match_score"],
                    score_breakdown_json=duplicate["score_breakdown_json"],
                    analysis_reason=duplicate["analysis_reason"],
                    evidence_summary_json=duplicate["evidence_summary_json"],
                    updated_at=duplicate["updated_at"],
                )
            )
        connection.execute(
            resume_analysis_cache.delete().where(resume_analysis_cache.c.id == duplicate["id"])
        )


def _cache_row_is_newer(candidate: Mapping[str, object], existing: Mapping[str, object]) -> bool:
    candidate_updated = candidate.get("updated_at")
    existing_updated = existing.get("updated_at")
    if isinstance(candidate_updated, datetime) and isinstance(existing_updated, datetime):
        if candidate_updated != existing_updated:
            return candidate_updated > existing_updated
    candidate_score = candidate.get("match_score")
    existing_score = existing.get("match_score")
    return isinstance(candidate_score, int) and (
        not isinstance(existing_score, int) or candidate_score > existing_score
    )


def _is_preferred_candidate_name(candidate: str | None, existing: str | None) -> bool:
    if not candidate:
        return False
    if not existing:
        return True
    candidate_mask_count = candidate.count("*")
    existing_mask_count = existing.count("*")
    if candidate_mask_count != existing_mask_count:
        return candidate_mask_count < existing_mask_count
    return len(candidate) > len(existing)


def _create_unique_experience_hash_index(engine: Engine) -> None:
    if _has_unique_index_for_column(engine, RESUME_EXPERIENCE_TABLE_NAME, "experience_hash"):
        return
    with engine.begin() as connection:
        connection.exec_driver_sql(
            f"CREATE UNIQUE INDEX uniq_candidate_resume_experience_hash "
            f"ON {RESUME_EXPERIENCE_TABLE_NAME} (experience_hash)"
        )


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
        "report_key": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN report_key VARCHAR(64) NULL",
        "search_batch_id": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN search_batch_id VARCHAR(64) NULL",
        "delivery_status": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN delivery_status VARCHAR(32) NOT NULL DEFAULT 'sent'",
        "detail_pdf_path": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN detail_pdf_path TEXT NULL",
        "detail_pdf_url": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN detail_pdf_url TEXT NULL",
        "uploaded_at": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN uploaded_at DATETIME NULL",
        "feishu_attempted_at": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN feishu_attempted_at DATETIME NULL",
        "sent_at": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP",
        "updated_at": f"ALTER TABLE {DAILY_REPORT_RUNS_TABLE_NAME} ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP",
    }
    alter_statements = [
        statement
        for column_name, statement in required_columns.items()
        if column_name not in column_names
    ]
    if alter_statements:
        with engine.begin() as connection:
            for statement in alter_statements:
                connection.exec_driver_sql(statement)
    if not _has_unique_index_for_column(engine, DAILY_REPORT_RUNS_TABLE_NAME, "report_key"):
        with engine.begin() as connection:
            connection.exec_driver_sql(
                f"CREATE UNIQUE INDEX uniq_daily_report_run_key "
                f"ON {DAILY_REPORT_RUNS_TABLE_NAME} (report_key)"
            )
    if not _has_unique_index_for_column(engine, DAILY_REPORT_RUNS_TABLE_NAME, "search_batch_id"):
        with engine.begin() as connection:
            connection.exec_driver_sql(
                f"CREATE UNIQUE INDEX uniq_daily_report_search_batch "
                f"ON {DAILY_REPORT_RUNS_TABLE_NAME} (search_batch_id)"
            )
