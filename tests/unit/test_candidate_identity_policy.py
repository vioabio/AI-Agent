from datetime import datetime

from apps.hr_review_api.domain.candidate_identity_policy import (
    deduplicate_candidate_summaries,
)
from apps.hr_review_api.domain.models import CandidateSummary
from liepin_rpa.db import build_candidate_fingerprint


def test_masked_and_honorific_names_build_the_same_future_fingerprint() -> None:
    structured_resume = {
        "basic_info": {
            "current_company": "某消费品公司",
            "current_position": "达人营销负责人",
            "city": "上海",
            "age": 43,
            "education": "本科",
            "work_years": 16,
        },
        "work_experiences": [
            {
                "company": {"raw_name": "某消费品公司"},
                "position": {"raw_title": "达人营销负责人"},
            }
        ],
    }

    masked = build_candidate_fingerprint(
        candidate_name="毛**",
        structured_resume=structured_resume,
    )
    honorific = build_candidate_fingerprint(
        candidate_name="毛先生",
        structured_resume=structured_resume,
    )

    assert masked == honorific


def test_historical_double_fingerprint_is_deduplicated_with_two_strong_signals() -> None:
    common_resume = "工作经历：某消费品公司；达人营销负责人；负责达人投放。" * 40
    older = _summary(
        fingerprint="older",
        name="毛先生",
        score=90,
        updated_at=datetime(2026, 8, 17, 11, 0),
        communication_status="sent",
        experience=common_resume,
    )
    newer = _summary(
        fingerprint="newer",
        name="毛**",
        score=92,
        updated_at=datetime(2026, 8, 19, 9, 0),
        communication_status="not_sent",
        experience=f"{common_resume}补充项目经历：年度达人矩阵建设。",
    )

    deduplicated = deduplicate_candidate_summaries((newer, older))

    assert len(deduplicated) == 1
    assert deduplicated[0].candidate_fingerprint == "newer"
    assert deduplicated[0].match_score == 92
    assert deduplicated[0].communication_status == "sent"


def test_same_surname_and_card_are_not_enough_when_resumes_disagree() -> None:
    first = _summary(
        fingerprint="first",
        name="毛先生",
        score=90,
        updated_at=datetime(2026, 8, 17, 11, 0),
        communication_status="sent",
        experience="品牌市场与达人投放经历。" * 40,
    )
    second = _summary(
        fingerprint="second",
        name="毛**",
        score=92,
        updated_at=datetime(2026, 8, 19, 9, 0),
        communication_status="not_sent",
        experience="财务共享、总账核算与资金管理经历。" * 40,
    )

    assert len(deduplicate_candidate_summaries((first, second))) == 2


def test_historical_identity_merge_accepts_missing_experience_timestamp() -> None:
    common_resume = "工作经历：某消费品公司；达人营销负责人；负责达人投放。" * 40
    without_master = _summary(
        fingerprint="analysis-only",
        name="毛先生",
        score=92,
        updated_at=None,
        communication_status="not_sent",
        experience=common_resume,
    )
    with_master = _summary(
        fingerprint="with-master",
        name="毛**",
        score=90,
        updated_at=datetime(2026, 8, 19, 9, 0),
        communication_status="sent",
        experience=f"{common_resume}补充项目经历：年度达人矩阵建设。",
    )

    deduplicated = deduplicate_candidate_summaries((without_master, with_master))

    assert len(deduplicated) == 1
    assert deduplicated[0].updated_at == datetime(2026, 8, 19, 9, 0)


def _summary(
    *,
    fingerprint: str,
    name: str,
    score: int,
    updated_at: datetime | None,
    communication_status: str,
    experience: str,
) -> CandidateSummary:
    return CandidateSummary(
        candidate_fingerprint=fingerprint,
        candidate_name=name,
        job_profile_id="daren_live_bd_v1",
        job_title="达人营销与达播负责人",
        age=43,
        match_result="relevant",
        analysis_status="匹配",
        match_score=score,
        current_company_title="60-80K · 16薪",
        communication_status=communication_status,
        review_status="interviewing",
        reviewer_id=1,
        reviewer_name="审核人",
        revision=1,
        has_share_link=True,
        updated_at=updated_at,
        identity_card_text=f"{name} 43岁 60-80K · 16薪 达人营销负责人",
        identity_experience=experience,
        identity_source_account_alias="liepin_account_a",
    )
