"""Local synthetic-data demo; never connects to the recruitment database."""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

import uvicorn
from argon2 import PasswordHasher
from sqlalchemy import create_engine, insert, select

from apps.hr_review_api.config import HRReviewSettings
from apps.hr_review_api.main import create_app
from apps.hr_review_api.persistence.tables import hr_users
from scripts.seed_hr_demo import seed_expanded_demo
from liepin_rpa.db import (
    metadata,
    resume_analysis_cache,
    resume_experience_records,
    resume_search_hits,
    resume_source_locators,
)

ROOT = Path(__file__).resolve().parents[1]
DEMO_DIR = ROOT / "artifacts" / "hr_demo"


def build_demo_app():
    DEMO_DIR.mkdir(parents=True, exist_ok=True)
    engine = create_engine(
        f"sqlite+pysqlite:///{(DEMO_DIR / 'demo-v2.sqlite3').as_posix()}",
        connect_args={"check_same_thread": False},
    )
    metadata.create_all(engine)
    with engine.begin() as conn:
        if conn.execute(select(hr_users.c.id).limit(1)).first() is None:
            conn.execute(insert(hr_users).values(
                username="demo", display_name="演示审核员", role="recruiting_lead",
                password_hash=PasswordHasher().hash("DemoReview2026!"),
                is_active=True, can_view_sensitive=False,
            ))
            for index, (result, score) in enumerate([
                ("relevant", 95), ("needs_review", 92), ("not_relevant", 45)
            ], 1):
                fingerprint = f"demo-candidate-{index}"
                name = f"虚构候选人{index}"
                status = {"relevant": "匹配", "needs_review": "待复核", "not_relevant": "不匹配"}[result]
                conn.execute(insert(resume_experience_records).values(
                    id=index,
                    candidate_fingerprint=fingerprint, experience_hash=f"demo-hash-{index}",
                    candidate_name=name, experience="演示虚构简历：上海，统招本科，达人营销团队管理经验。",
                    analysis_status=status, match_score=score, updated_at=datetime.now(),
                ))
                conn.execute(insert(resume_analysis_cache).values(
                    id=index,
                    candidate_fingerprint=fingerprint, experience_hash=f"demo-hash-{index}",
                    analysis_version="synthetic-demo-v1", analysis_status=status,
                    match_result=result, match_score=score, analysis_reason="虚构演示评分，未调用 AI",
                ))
                conn.execute(insert(resume_source_locators).values(
                    id=index,
                    candidate_fingerprint=fingerprint, candidate_name=name,
                    job_profile_id="daren_live_bd_v1", search_keyword="演示关键词",
                    search_batch_id="synthetic-demo-batch", locator_quality="snapshot",
                ))
                conn.execute(insert(resume_search_hits).values(
                    id=index,
                    candidate_fingerprint=fingerprint, candidate_name=name,
                    search_keyword="演示关键词", search_batch_id="synthetic-demo-batch", hit_status="hit",
                ))
    expanded_batches = seed_expanded_demo(engine)
    batch_path = DEMO_DIR / "batches.json"
    batch_path.write_text(json.dumps({"completed_batches": [{
        "batch_id": "synthetic-demo-batch", "started_at": "2020-01-01T00:00:00+08:00",
        "ended_at": "2030-01-01T00:00:00+08:00",
    }, *expanded_batches]}), encoding="utf-8")
    settings = HRReviewSettings(
        _env_file=None, APP_ENV="development", HR_REVIEW_DATABASE_URL=str(engine.url),
        HR_BATCH_STATE_PATH=batch_path, HR_ALLOWED_ORIGINS="http://localhost:5174",
        HR_COOKIE_SECURE=False, HR_ENABLE_OPENAPI=True,
    )
    return create_app(settings=settings, engine=engine)


if __name__ == "__main__":
    uvicorn.run(build_demo_app(), host="127.0.0.1", port=8001)
