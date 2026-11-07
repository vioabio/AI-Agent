from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Mapping

from sqlalchemy import and_, exists, or_, select

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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="使用新分析版本补算指定运行窗口内的技术失败缓存。"
    )
    parser.add_argument(
        "--run-state-file",
        type=Path,
        default=Path("artifacts/search_workflow_run_state.json"),
    )
    parser.add_argument(
        "--job-profile",
        type=Path,
        default=Path("config/job_profiles/daren_live_bd.yaml"),
    )
    parser.add_argument(
        "--source-version",
        default="2026-08-18-daren-live-bd-llm-v1",
    )
    parser.add_argument("--target-version")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="实际调用模型并写入新版本缓存；默认仅统计。",
    )
    return parser.parse_args()


def is_technical_failure_reason(reason: object) -> bool:
    return isinstance(reason, str) and reason.startswith(TECHNICAL_FAILURE_PREFIXES)


def load_run_window(path: Path) -> tuple[datetime, datetime]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    started_at = payload.get("started_at")
    finished_at = payload.get("finished_at")
    if not isinstance(started_at, int) or not isinstance(finished_at, int):
        raise ValueError("run_state must contain integer started_at and finished_at")
    return datetime.fromtimestamp(started_at), datetime.fromtimestamp(finished_at)


def load_failed_candidates(
    settings: Settings,
    *,
    job_profile_id: str,
    source_version: str,
    target_version: str,
    started_at: datetime,
    finished_at: datetime,
    limit: int = 0,
) -> list[dict[str, object]]:
    source = resume_analysis_cache.alias("source_analysis")
    target = resume_analysis_cache.alias("target_analysis")
    locator = resume_source_locators.alias("batch_locator")
    locator_filters = (
        locator.c.candidate_fingerprint == source.c.candidate_fingerprint,
        locator.c.job_profile_id == job_profile_id,
        locator.c.created_at >= started_at,
        locator.c.created_at <= finished_at,
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
            source.c.candidate_fingerprint,
            source.c.experience_hash,
            source.c.experience,
            source.c.analysis_reason,
            latest_name.label("candidate_name"),
            latest_keyword.label("search_keyword"),
        )
        .where(
            source.c.analysis_version == source_version,
            source.c.match_result == "needs_review",
            source.c.created_at >= started_at,
            source.c.created_at <= finished_at,
            or_(
                source.c.analysis_reason.like(f"{TECHNICAL_FAILURE_PREFIXES[0]}%"),
                source.c.analysis_reason.like(f"{TECHNICAL_FAILURE_PREFIXES[1]}%"),
            ),
            exists(select(locator.c.id).where(*locator_filters)),
            ~exists(
                select(target.c.id).where(
                    and_(
                        target.c.candidate_fingerprint
                        == source.c.candidate_fingerprint,
                        target.c.experience_hash == source.c.experience_hash,
                        target.c.analysis_version == target_version,
                    )
                )
            ),
        )
        .order_by(source.c.created_at, source.c.id)
    )
    if limit > 0:
        statement = statement.limit(limit)
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
        raise RuntimeError("定向补算仅允许 RESUME_ANALYSIS_PROVIDER=openai_compatible")
    profile = load_job_profile(args.job_profile)
    job_profile_id = str(profile.get("id") or "").strip()
    prompt_path = Path(str(profile.get("analysis_prompt_path") or "").strip())
    if not job_profile_id or not prompt_path.is_file():
        raise RuntimeError("岗位配置缺少 id 或有效 analysis_prompt_path")
    target_version = str(args.target_version or settings.llm_jd_analysis_version).strip()
    if not target_version or target_version == args.source_version:
        raise RuntimeError("target analysis version must differ from source version")
    started_at, finished_at = load_run_window(args.run_state_file)
    candidates = load_failed_candidates(
        settings,
        job_profile_id=job_profile_id,
        source_version=args.source_version,
        target_version=target_version,
        started_at=started_at,
        finished_at=finished_at,
        limit=args.limit,
    )
    print(
        json.dumps(
            {
                "mode": "apply" if args.apply else "dry_run",
                "job_profile_id": job_profile_id,
                "source_version": args.source_version,
                "target_version": target_version,
                "window_start": started_at.isoformat(sep=" "),
                "window_end": finished_at.isoformat(sep=" "),
                "candidate_count": len(candidates),
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    if not args.apply:
        return 0

    contract = analysis_contract_from_job_profile(profile)
    counts: Counter[str] = Counter()
    for index, row in enumerate(candidates, start=1):
        result = analyze_resume_record(
            settings,
            resume_record=build_resume_record(row),
            prompt_path=prompt_path,
            analysis_contract=contract,
        )
        analysis_status = analysis_status_for_match_result(result.match_result)
        upsert_analysis_cache_record(
            settings,
            candidate_fingerprint=str(row["candidate_fingerprint"]),
            experience_hash=str(row["experience_hash"]),
            experience=str(row.get("experience") or ""),
            analysis_version=target_version,
            analysis_status=analysis_status,
            match_result=result.match_result,
            match_score=result.match_score,
            score_breakdown=result.score_breakdown,
            analysis_reason=result.analysis_reason,
            evidence_summary=result.evidence_summary,
            execution_status=getattr(result, "execution_status", None),
            error_code=getattr(result, "error_code", None),
            retryable=getattr(result, "retryable", None),
            retry_after=getattr(result, "retry_after", None),
            analysis_run_id=getattr(result, "analysis_run_id", None),
            analyzed_text_hash=getattr(result, "analyzed_text_hash", None),
            job_profile_id=str(profile.get("id") or ""),
        )
        counts[str(result.match_result or "unknown")] += 1
        print(
            json.dumps(
                {
                    "progress": f"{index}/{len(candidates)}",
                    "candidate_fingerprint": str(row["candidate_fingerprint"])[:12],
                    "match_result": result.match_result,
                    "match_score": result.match_score,
                },
                ensure_ascii=False,
            ),
            flush=True,
        )
    print(json.dumps({"completed": len(candidates), "results": counts}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
