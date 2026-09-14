from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Mapping

from sqlalchemy import exists, or_, select

from liepin_rpa.analysis_contract import analysis_contract_from_job_profile
from liepin_rpa.analysis_gateway import (
    PROVIDER_OPENAI_COMPATIBLE,
    analyze_resume_record,
)
from liepin_rpa.candidate_decision_rules import analysis_status_for_match_result
from liepin_rpa.config import Settings, get_settings
from liepin_rpa.db import (
    get_engine,
    resume_analysis_cache,
    resume_source_locators,
    upsert_analysis_cache_record,
)
from liepin_rpa.task_generation.config_loader import load_job_profile

TECHNICAL_FAILURE_PREFIXES = (
    "需要人工复核：模型输出结构校验失败",
    "需要人工复核：模型 API 临时异常",
)

JOB_PROFILES = {
    "daren": Path("config/job_profiles/daren_live_bd.yaml"),
    "finance": Path("config/job_profiles/finance_shared_management.yaml"),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="重试当前有效分析版本中仍处于技术失败状态的简历。"
    )
    parser.add_argument(
        "--job",
        choices=("all", *JOB_PROFILES),
        default="all",
        help="限定岗位；默认同时检查达人和财务岗位。",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="实际调用模型并原位更新当前版本缓存；默认仅统计。",
    )
    return parser.parse_args()


def active_analysis_version(settings: Settings, job: str) -> str:
    if job == "daren":
        return settings.llm_jd_analysis_version
    if job == "finance":
        return settings.llm_finance_analysis_version
    raise ValueError(f"unsupported job: {job}")


def load_active_failures(
    settings: Settings,
    *,
    job_profile_id: str,
    analysis_version: str,
) -> list[dict[str, object]]:
    analysis = resume_analysis_cache.alias("active_failure")
    locator = resume_source_locators.alias("job_locator")
    locator_filters = (
        locator.c.candidate_fingerprint == analysis.c.candidate_fingerprint,
        locator.c.job_profile_id == job_profile_id,
    )
    latest_name = (
        select(locator.c.candidate_name)
        .where(*locator_filters)
        .order_by(locator.c.created_at.desc(), locator.c.id.desc())
        .limit(1)
        .scalar_subquery()
    )
    latest_keyword = (
        select(locator.c.search_keyword)
        .where(*locator_filters)
        .order_by(locator.c.created_at.desc(), locator.c.id.desc())
        .limit(1)
        .scalar_subquery()
    )
    statement = (
        select(
            analysis.c.candidate_fingerprint,
            analysis.c.experience_hash,
            analysis.c.experience,
            analysis.c.analysis_reason,
            latest_name.label("candidate_name"),
            latest_keyword.label("search_keyword"),
        )
        .where(
            analysis.c.analysis_version == analysis_version,
            analysis.c.match_result == "needs_review",
            or_(
                analysis.c.analysis_reason.like(f"{TECHNICAL_FAILURE_PREFIXES[0]}%"),
                analysis.c.analysis_reason.like(f"{TECHNICAL_FAILURE_PREFIXES[1]}%"),
            ),
            exists(select(locator.c.id).where(*locator_filters)),
        )
        .order_by(analysis.c.updated_at, analysis.c.id)
    )
    with get_engine(settings).begin() as connection:
        rows = connection.execute(statement).mappings().all()
    return [dict(row) for row in rows]


def build_resume_record(row: Mapping[str, object]) -> dict[str, object]:
    return {
        "id": 0,
        "candidate_name": row.get("candidate_name"),
        "source_platform": "liepin",
        "search_keyword": row.get("search_keyword") or "",
        "experience": row.get("experience") or "",
        "age": None,
        "education": "",
        "analysis_status": "未执行",
    }


def main() -> int:
    args = parse_args()
    settings = get_settings()
    if settings.resume_analysis_provider.strip().lower() != PROVIDER_OPENAI_COMPATIBLE:
        raise RuntimeError("技术失败重试仅允许 RESUME_ANALYSIS_PROVIDER=openai_compatible")

    jobs = tuple(JOB_PROFILES) if args.job == "all" else (args.job,)
    work_items: list[tuple[str, Path, Mapping[str, object], str, dict[str, object]]] = []
    preview: dict[str, object] = {}
    for job in jobs:
        profile_path = JOB_PROFILES[job]
        profile = load_job_profile(profile_path)
        job_profile_id = str(profile.get("id") or "").strip()
        prompt_path = Path(str(profile.get("analysis_prompt_path") or "").strip())
        if not job_profile_id or not prompt_path.is_file():
            raise RuntimeError(f"{profile_path} 缺少 id 或有效 analysis_prompt_path")
        version = active_analysis_version(settings, job)
        failures = load_active_failures(
            settings,
            job_profile_id=job_profile_id,
            analysis_version=version,
        )
        preview[job] = {
            "job_profile_id": job_profile_id,
            "analysis_version": version,
            "candidate_count": len(failures),
        }
        work_items.extend(
            (job, prompt_path, profile, version, row) for row in failures
        )

    print(
        json.dumps(
            {
                "mode": "apply" if args.apply else "dry_run",
                "jobs": preview,
                "candidate_count": len(work_items),
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    if not args.apply:
        return 0

    counts: Counter[str] = Counter()
    for index, (job, prompt_path, profile, version, row) in enumerate(work_items, start=1):
        result = analyze_resume_record(
            settings,
            resume_record=build_resume_record(row),
            prompt_path=prompt_path,
            analysis_contract=analysis_contract_from_job_profile(profile),
        )
        upsert_analysis_cache_record(
            settings,
            candidate_fingerprint=str(row["candidate_fingerprint"]),
            experience_hash=str(row["experience_hash"]),
            experience=str(row.get("experience") or ""),
            analysis_version=version,
            analysis_status=analysis_status_for_match_result(result.match_result),
            match_result=result.match_result,
            match_score=result.match_score,
            score_breakdown=result.score_breakdown,
            analysis_reason=result.analysis_reason,
            evidence_summary=result.evidence_summary,
        )
        counts[f"{job}:{result.match_result or 'unknown'}"] += 1
        print(
            json.dumps(
                {
                    "progress": f"{index}/{len(work_items)}",
                    "job": job,
                    "candidate_fingerprint": str(row["candidate_fingerprint"])[:12],
                    "match_result": result.match_result,
                    "match_score": result.match_score,
                },
                ensure_ascii=False,
            ),
            flush=True,
        )
    print(
        json.dumps(
            {"completed": len(work_items), "results": dict(counts)},
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
