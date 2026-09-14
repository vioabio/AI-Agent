from liepin_rpa.candidate_identity import (
    HistoricalCandidateEvidence,
    candidate_identity_evidence_matches,
    candidate_names_are_compatible,
    canonicalize_candidate_name,
    select_historical_candidate_identity,
)


def _resume(name: str, marker: str = "同一经历") -> str:
    return (f"候选人信息：{name} 集团财务经理 多主体核算 月结 流程优化 {marker} " * 12).strip()


def test_private_name_variants_share_canonical_name_but_full_names_do_not() -> None:
    assert canonicalize_candidate_name("毛先生") == "毛*"
    assert canonicalize_candidate_name("毛**") == "毛*"
    assert candidate_names_are_compatible("李昌浩", "李**") is True
    assert candidate_names_are_compatible("李昌浩", "李明远") is False
    assert canonicalize_candidate_name("欧阳先生") == "欧阳*"


def test_global_cooldown_identity_uses_four_strong_signals() -> None:
    assert candidate_identity_evidence_matches(
        first_name="李昌浩",
        first_age=35,
        first_card_text="李昌浩 35岁 本科 40-45K 集团财务经理 伟创力",
        first_experience=_resume("李昌浩", "流程优化项目"),
        second_name="李**",
        second_age=35,
        second_card_text="李** 35岁 本科 40-45K 集团财务经理 伟创力",
        second_experience=_resume("李**", "流程优化项目"),
    )
    assert not candidate_identity_evidence_matches(
        first_name="李昌浩",
        first_age=35,
        first_card_text="李昌浩 35岁 本科 40-45K 集团财务经理 伟创力",
        first_experience=_resume("李昌浩", "流程优化项目"),
        second_name="李**",
        second_age=36,
        second_card_text="李** 36岁 本科 40-45K 集团财务经理 伟创力",
        second_experience=_resume("李**", "流程优化项目"),
    )


def test_unique_strong_evidence_reuses_historical_identity() -> None:
    match = select_historical_candidate_identity(
        candidate_name="李昌浩",
        candidate_age=35,
        job_profile_id="finance_shared_management",
        candidate_card_text="李昌浩 上海 本科 35岁 13年 40-45K 集团财务经理 伟创力",
        experience=_resume("李昌浩"),
        candidates=[
            HistoricalCandidateEvidence(
                candidate_fingerprint="historical-fingerprint",
                candidate_name="李**",
                candidate_age=35,
                job_profile_id="finance_shared_management",
                candidate_card_text="李** 离职 本科 35岁 13年 40-45K 集团财务经理 伟创力",
                experience=_resume("李**"),
            )
        ],
    )

    assert match is not None
    assert match.candidate_fingerprint == "historical-fingerprint"
    assert match.resume_similarity > 0.99


def test_different_full_name_or_ambiguous_identity_is_not_merged() -> None:
    common = dict(
        candidate_age=35,
        job_profile_id="finance_shared_management",
        candidate_card_text="李** 35岁 本科 集团财务经理 上海",
        experience=_resume("李**"),
    )
    candidates = [
        HistoricalCandidateEvidence(
            candidate_fingerprint=fingerprint,
            candidate_name=name,
            **common,
        )
        for fingerprint, name in (("a", "李**"), ("b", "李先生"))
    ]
    assert select_historical_candidate_identity(
        candidate_name="李昌浩",
        candidates=candidates,
        **common,
    ) is None
    assert not candidate_names_are_compatible("李昌浩", "李明远")


def test_resume_disagreement_prevents_merge() -> None:
    match = select_historical_candidate_identity(
        candidate_name="李昌浩",
        candidate_age=35,
        job_profile_id="finance_shared_management",
        candidate_card_text="李昌浩 35岁 本科 集团财务经理 上海",
        experience=_resume("李昌浩", "集团核算"),
        candidates=[
            HistoricalCandidateEvidence(
                candidate_fingerprint="other",
                candidate_name="李**",
                candidate_age=35,
                job_profile_id="finance_shared_management",
                candidate_card_text="李** 35岁 本科 集团财务经理 上海",
                experience=("候选人信息：李** 品牌营销 电商直播 达人投放 " * 12),
            )
        ],
    )
    assert match is None
