"""Precomputed synthetic intake -> queue -> HR shadow projection examples."""
from sqlalchemy import func, select

from liepin_rpa.analysis_intake import SyntheticCollectionIntake
from liepin_rpa.analysis_job_schema import collection_receipts
from liepin_rpa.analysis_jobs import AnalysisJobQueue
from liepin_rpa.analysis_versioning import content_hash


def seed_shadow_demo(engine):
    if engine.dialect.name != "sqlite":
        raise ValueError("Shadow fixtures require SQLite")
    queue = AnalysisJobQueue(engine)
    queue.configure()
    with engine.connect() as connection:
        if connection.scalar(select(func.count()).select_from(collection_receipts)):
            return  # Preserve operator actions and accumulated budgets across restarts.
    intake = SyntheticCollectionIntake(queue)
    batch, active = "synthetic-shadow-complete-v1", "synthetic-shadow-active-v1"
    queue.create_batch(batch)
    queue.create_batch(active)
    text = ("【纯虚构影子流程简历】\n教育经历：统招本科，计算机。\n求职意向：上海。\n"
            "工作经历：2021.01-至今。\n项目经历：独立开发 RAG 服务并部署上线。\n")
    record = {"experience": text, "education_experiences": [{"degree": "本科", "admission_type": "统招"}],
              "sections": {"求职意向": ["上海"]}}
    payload = {"beauty_relevant": True, "match_result": "relevant", "match_score": 90,
               "score_breakdown": {"industry_depth": 30, "beauty_continuity": 20, "role_evidence": 40},
               "analysis_reason": "预计算合成结果，仅用于影子流程演示，未调用真实模型。",
               "evidence_summary": ["独立开发 RAG 服务并部署上线。"]}
    for person, name, profile, outcome in [
        ("shadow-ai", "虚构林工 · AI", "ai_development_engineer_v1", "succeeded"),
        ("shadow-fde", "虚构陈工 · FDE", "fde_ai_deployment_engineer_v1", "succeeded"),
        ("demo-expanded-005", "原有候选人的独立影子任务", "daren_live_bd_v1", "succeeded"),
        ("shadow-invalid", "虚构赵工 · 待核验输出", "ai_development_engineer_v1", "invalid_output"),
        ("shadow-timeout", "虚构周工 · 可安排重试", "fde_ai_deployment_engineer_v1", "timeout"),
        ("shadow-rate", "虚构李工 · 限流等待", "fde_ai_deployment_engineer_v1", "rate_limited"),
        ("shadow-pending", "虚构王工 · 等待分析", "ai_development_engineer_v1", "pending"),
    ]:
        decision = intake.submit(batch_id=active if outcome == "pending" else batch, candidate_fingerprint=person,
            candidate_name=name, job_profile_id=profile, resume_record=record,
            pipeline_fingerprint=content_hash({"mode": "shadow-demo-v1", "job": profile}), deadline_seconds=86400)
        if decision.state != "queued":
            raise ValueError("Synthetic fixture failed deterministic intake")
        if outcome == "pending":
            continue
        # Existing retries are in the future, so the newly submitted task is the only ready task.
        lease = queue.claim("synthetic-seed-worker")
        if lease is None or lease.job_id != decision.job_id:
            raise ValueError("Synthetic fixture scheduling mismatch")
        if outcome == "succeeded":
            output = {**payload, "score_breakdown": {"industry_depth": 20, "beauty_continuity": 15, "role_evidence": 55}} if profile == "daren_live_bd_v1" else payload
            queue.complete(lease, payload=output, analyzed_text_hash=queue.read_input(lease).input_hash)
        else:
            queue.fail(lease, outcome, retry_after_seconds=120 if outcome == "rate_limited" else 30)
    queue.close_collection(batch)
