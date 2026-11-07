from datetime import datetime, timedelta, timezone
import socket

import pytest
from sqlalchemy import column, create_engine, select, text

from liepin_rpa.analysis_contract import (
    build_needs_review_result,
    is_transient_analysis_review,
    transient_analysis_cache_retry_due,
)
from liepin_rpa.analysis_execution import (
    AnalysisExecutionStatus,
    has_analysis_execution_failure,
    analysis_success_condition,
    legacy_analysis_success_condition,
    legacy_analysis_success_sql,
)
from liepin_rpa.reporting_rules import is_actionable_report_result


@pytest.mark.parametrize("provider", ["Coze", "模型", "OpenAI兼容", "其他供应商"])
def test_provider_failure_never_enters_actionable_population(provider):
    result = build_needs_review_result(
        raw_data="", reason="timeout", provider_label=provider, source="api_error"
    )
    assert result.execution_status == AnalysisExecutionStatus.TRANSIENT_ERROR
    assert result.error_code == "provider_error"
    assert result.retryable
    assert is_transient_analysis_review(result)
    # Even a stale scored legacy record must not become actionable.
    assert not is_actionable_report_result(
        "needs_review", 99, analysis_reason=result.analysis_reason
    )
    assert not is_actionable_report_result(
        "relevant", 99, execution_status=result.execution_status
    )


def test_execution_metadata_takes_precedence_over_display_text():
    result = build_needs_review_result(
        raw_data="bad", reason="invalid", provider_label="模型"
    )
    result.analysis_reason = "展示文案已修改"
    assert not is_transient_analysis_review(result)
    assert has_analysis_execution_failure(
        result.analysis_reason, execution_status=result.execution_status
    )
    assert not result.retryable
    assert has_analysis_execution_failure(execution_status="misspelled_status")
    assert not has_analysis_execution_failure(
        "证据中引用了 Coze API", execution_status="succeeded"
    )


def test_transient_retry_window_uses_existing_cache_timestamp():
    result = build_needs_review_result(
        raw_data="", reason="timeout", provider_label="模型", source="api_error"
    )
    result.analysis_reason = "不影响重试的展示文案"
    cached_at = datetime(2026, 11, 5, 12)
    record = {"updated_at": cached_at}
    assert not transient_analysis_cache_retry_due(
        result, record, now=cached_at + timedelta(minutes=9, seconds=59)
    )
    assert transient_analysis_cache_retry_due(
        result, record, now=cached_at + timedelta(minutes=10)
    )
    assert record["updated_at"] == cached_at


def test_absolute_retry_deadline_ignores_display_timestamp_changes_and_timezones():
    result = build_needs_review_result(
        raw_data="", reason="timeout", provider_label="模型", source="api_error"
    )
    record = {
        "retry_after": "2026-11-05T04:10:00Z",
        "updated_at": datetime(2026, 11, 5, 12, 9, 59),
    }
    china = timezone(timedelta(hours=8))
    assert not transient_analysis_cache_retry_due(
        result, record, now=datetime(2026, 11, 5, 12, 9, 59, tzinfo=china)
    )
    record["updated_at"] = datetime(2026, 11, 5, 12, 10)
    assert transient_analysis_cache_retry_due(
        result, record, now=datetime(2026, 11, 5, 12, 10, tzinfo=china)
    )


@pytest.mark.parametrize("reason", [
    None, "证据充分", "Coze API error", "模型 API 临时异常",
    "其他供应商 API 连续返回异常", "模型输出结构校验失败",
])
def test_python_and_both_sql_legacy_adapters_select_the_same_population(reason):
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE sample (reason TEXT)"))
        conn.execute(text("INSERT INTO sample VALUES (:reason)"), {"reason": reason})
        raw_count = conn.scalar(text(
            f"SELECT COUNT(*) FROM sample s WHERE {legacy_analysis_success_sql('s.reason')}"
        ))
        expression = legacy_analysis_success_condition(column("reason"))
        # NULL is a legitimate successful legacy reason, so count rows separately.
        selected_rows = conn.execute(select(column("reason")).select_from(text("sample")).where(expression)).all()
    expected = not has_analysis_execution_failure(reason)
    assert bool(raw_count) == expected
    assert bool(selected_rows) == expected
    engine.dispose()


def test_legacy_sql_rejects_untrusted_identifiers():
    with pytest.raises(ValueError):
        legacy_analysis_success_sql("reason); DROP TABLE sample; --")


@pytest.mark.parametrize("status,reason,expected", [
    ("succeeded", "证据引用 Coze API", True),
    ("transient_error", "展示文案已更改", False),
    ("invalid_output", "无供应商名称", False),
    ("legacy_unknown", "待核验", False),
    ("typo", "正常说明", False),
    (None, "模型 API 临时异常", False),
    (None, "证据充分", True),
])
def test_execution_state_overrides_display_text_in_sql(status, reason, expected):
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE sample (execution_status TEXT, reason TEXT)"))
        conn.execute(text("INSERT INTO sample VALUES (:status, :reason)"), {
            "status": status, "reason": reason,
        })
        raw_rows = conn.execute(text(
            "SELECT * FROM sample s WHERE " +
            legacy_analysis_success_sql("s.reason", "s.execution_status")
        )).all()
        expression_rows = conn.execute(select(column("reason")).select_from(text("sample")).where(
            analysis_success_condition(column("reason"), column("execution_status"))
        )).all()
    assert bool(raw_rows) == expected
    assert bool(expression_rows) == expected
    engine.dispose()


def test_offline_suite_rejects_external_dns():
    with pytest.raises(RuntimeError, match="External DNS"):
        socket.getaddrinfo("example.invalid", 443)


def test_offline_suite_rejects_mysql_even_on_loopback():
    engine = create_engine("mysql+pymysql://test:test@127.0.0.1/offline")
    with pytest.raises(RuntimeError, match="only temporary SQLite"):
        engine.connect()
    engine.dispose()
