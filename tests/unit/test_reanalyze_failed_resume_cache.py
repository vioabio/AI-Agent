import json
from datetime import datetime

from scripts.reanalyze_failed_resume_cache import (
    build_resume_record,
    is_technical_failure_reason,
    load_run_window,
)


def test_technical_failure_reason_only_accepts_model_failures() -> None:
    assert is_technical_failure_reason("需要人工复核：模型输出结构校验失败，分项超限")
    assert is_technical_failure_reason("需要人工复核：模型 API 临时异常，连接失败")
    assert not is_technical_failure_reason("最终结论为needs_review，证据不足")


def test_load_run_window_requires_finished_run(tmp_path) -> None:
    state_path = tmp_path / "run_state.json"
    state_path.write_text(
        json.dumps({"started_at": 1_787_098_135, "finished_at": 1_787_122_250}),
        encoding="utf-8",
    )

    assert load_run_window(state_path) == (
        datetime.fromtimestamp(1_787_098_135),
        datetime.fromtimestamp(1_787_122_250),
    )


def test_build_resume_record_reuses_saved_resume_without_side_effects() -> None:
    record = build_resume_record(
        {
            "candidate_name": "测试候选人",
            "search_keyword": "达人营销负责人",
            "experience": "负责达人矩阵",
        }
    )

    assert record["candidate_name"] == "测试候选人"
    assert record["search_keyword"] == "达人营销负责人"
    assert record["experience"] == "负责达人矩阵"
    assert record["analysis_status"] == "未执行"
