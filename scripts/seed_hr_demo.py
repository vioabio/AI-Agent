"""Idempotent synthetic fixtures for the local SQLite demo only."""
from __future__ import annotations

import json
from datetime import datetime, timedelta

from sqlalchemy import func, insert, select

from apps.hr_review_api.persistence.tables import hr_users, manual_review_events
from liepin_rpa.db import (
    resume_analysis_cache, resume_experience_records, resume_search_hits,
    resume_source_locators,
)
from liepin_rpa.analysis_evidence_store import persist_analysis_evidence, result_document
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.resume_evidence import resume_text_hash


def seed_expanded_demo(engine, *, scenario="legacy") -> list[dict[str, str]]:
    if scenario not in {"legacy", "ai"}:
        raise ValueError("Unknown synthetic demo scenario")
    if engine.dialect.name != "sqlite":
        raise ValueError("Synthetic demo fixtures require local SQLite")
    batches = []
    anchor = datetime(2026, 10, 26, 18)
    with engine.begin() as conn:
        reviewer = conn.execute(select(hr_users.c.id, hr_users.c.display_name)
                                .where(hr_users.c.username == "demo")).one()
        counters = {
            table.name: int(conn.execute(select(func.max(table.c.id))).scalar() or 0)
            for table in [resume_experience_records, resume_analysis_cache,
                          resume_search_hits, resume_source_locators]
        }

        def add(table, **values):
            counters[table.name] += 1
            conn.execute(insert(table).values(id=counters[table.name], **values))

        states = [None, None, None, None, "recommended_contact", "contacted",
                  "interviewing", "on_hold", "resume_rejected", "interview_rejected", "hired", None]
        feedbacks = {
            "recommended_contact": "项目经历符合要求，建议安排初步沟通。",
            "contacted": "已完成电话沟通，候选人对岗位有兴趣。",
            "interviewing": "已安排业务面试，重点核验团队管理与项目成果。",
            "on_hold": "到岗时间暂不匹配，保留观察。",
            "resume_rejected": "当前经历与岗位核心要求存在差距。",
            "interview_rejected": "面试中未提供足够的项目落地证据。",
            "hired": "已完成面试与录用确认。",
        }
        if scenario == "ai":
            feedbacks["interviewing"] = "已安排技术面试，重点核验个人职责、部署交付与失败案例。"
        for batch_index in range(4):
            end = anchor - timedelta(days=batch_index * 3)
            batch_id = f"synthetic-{'ai' if scenario == 'ai' else 'expanded'}-{batch_index + 1}"
            batches.append({"batch_id": batch_id,
                            "started_at": (end - timedelta(hours=8)).isoformat() + "+08:00",
                            "ended_at": end.isoformat() + "+08:00"})
            for offset in range(24):
                number = batch_index * 24 + offset + 1
                fingerprint = f"demo-{'ai' if scenario == 'ai' else 'expanded'}-{number:03d}"
                if conn.execute(select(resume_experience_records.c.id).where(
                    resume_experience_records.c.candidate_fingerprint == fingerprint
                )).first():
                    continue
                finance = offset % 2 == 1
                job = "finance_shared_management_v1" if finance else "daren_live_bd_v1"
                title = "财务共享经理" if finance else "达人营销负责人"
                company = f"虚构{'财务' if finance else '消费'}集团{number:03d}"
                name = f"演示{'陈李王张赵周吴郑'[number % 8]}{'先生' if number % 2 else '女士'}{number:03d}"
                age = 27 + number % 9
                state = states[(offset // 2 + batch_index) % len(states)]
                result = ["relevant", "relevant", "needs_review", "not_relevant"][number % 4]
                if scenario == "ai":
                    job = "fde_ai_deployment_engineer_v1" if finance else "ai_development_engineer_v1"
                    title = "AI 前沿部署工程师（FDE）" if finance else "AI 开发工程师"
                    company = f"虚构{'部署交付' if finance else 'AI应用'}团队{number:03d}"
                    result = ["relevant", "needs_review", "not_relevant"][(number - 1) // 2 % 3]
                score = (85 + number % 15) if result == "relevant" else (
                    80 + number % 20 if result == "needs_review" else 40 + number % 25)
                status = {"relevant": "匹配", "needs_review": "待复核", "not_relevant": "不匹配"}[result]
                when = end - timedelta(hours=6, minutes=offset * 3)
                achievement = (f"主导{5 + number % 12}家主体核算整合，设计共享中心费用审核流程，维护月度结账报表。"
                               if finance else f"管理{4 + number % 10}人营销团队，负责达人筛选、商务谈判与直播复盘，策划{number + 10}场活动。")
                if scenario == "ai":
                    achievement = ({
                        "relevant": "负责客户需求澄清、Docker 部署与联调验收，将知识库问答服务交付上线。",
                        "needs_review": "参与客户需求梳理与服务验收，未说明是否独立负责部署、故障处理及交付。",
                        "not_relevant": "主要承担售前产品演示，未描述模型服务部署或客户交付经历。",
                    } if finance else {
                        "relevant": "独立开发 Python 后端与 RAG 知识库服务，实现检索、引用校验及失败回退并部署上线。",
                        "needs_review": "参与 RAG 项目开发，罗列向量检索和工具调用，但个人职责与上线范围待核验。",
                        "not_relevant": "仅完成 Python 与提示词课程练习，未描述后端项目或 AI 应用上线经历。",
                    })[result]
                experience = (
                    f"【虚构演示数据】{name}，编号 DEMO-{number:03d}，请勿用于真实招聘判断。\n"
                    f"候选人信息：年龄：{age}岁；城市：上海；统招本科；工作年限：{age - 22}年。\n"
                    f"求职意向：上海，{title}，期望薪资{20 + number % 15}K，预计一个月到岗。\n"
                    f"工作内容：{company}；{title}；2023.01-至今；{achievement}\n"
                    f"项目内容：项目 DEMO-{number:03d}；负责需求梳理、跨部门协作和指标复盘。\n"
                    "教育经历：虚构示范大学，2014.09-2018.06，统招本科。\n"
                    "自我评价：重视业务结果与流程规范，具备团队协作经验。"
                )
                common = dict(candidate_fingerprint=fingerprint, candidate_name=name)
                experience_hash = resume_text_hash(experience) if scenario == "ai" else f"demo-expanded-hash-{number}"
                add(resume_experience_records, **common, experience_hash=experience_hash,
                    experience=experience, analysis_status=status, match_score=score,
                    manual_review_status=state, manual_review_feedback=feedbacks.get(state),
                    manual_review_revision=1 if state else 0,
                    manual_reviewed_by=reviewer.id if state else None,
                    manual_review_assignee_id=reviewer.id if state else None,
                    manual_reviewed_at=when + timedelta(hours=1) if state else None,
                    manual_suppress_communication=int(bool(state)), manual_suppress_report=int(bool(state)),
                    created_at=when, updated_at=when)
                breakdown = {"行业经验": score // 3, "管理经验": score // 3, "岗位证据": score - 2 * (score // 3)}
                if scenario == "ai":
                    industry = min(35 if finance else 30, score)
                    continuity = min(25 if finance else 20, score - industry)
                    breakdown = {"industry_depth": industry, "beauty_continuity": continuity,
                                 "role_evidence": score - industry - continuity}
                add(resume_analysis_cache, candidate_fingerprint=fingerprint,
                    experience_hash=experience_hash, experience=experience,
                    analysis_version=f"synthetic-ai-v1:{job}" if scenario == "ai" else "synthetic-expanded-v1", analysis_status=status,
                    match_result=result, match_score=score, execution_status="succeeded",
                    analysis_reason=f"虚构分析：{'经历匹配，建议进一步核验' if result == 'relevant' else ('需核验个人职责与上线证据' if scenario == 'ai' else '需核验管理范围及成果证据')}。未调用外部模型。",
                    score_breakdown_json=json.dumps(breakdown, ensure_ascii=False),
                    evidence_summary_json=json.dumps([achievement, "上海求职意向，统招本科（均为虚构）"], ensure_ascii=False),
                    created_at=when, updated_at=when)
                _attach_synthetic_evidence(conn, fingerprint=fingerprint, job_profile_id=job,
                                           cache_id=counters[resume_analysis_cache.name], experience=experience,
                                           fixture_version="demo-v6-ai" if scenario == "ai" else "demo-v4")
                add(resume_source_locators, **common, job_profile_id=job,
                    search_keyword=title, search_batch_id=batch_id, source_account_alias="synthetic-demo",
                    locator_quality="snapshot", candidate_card_text=f"{name} {age}岁 {company} · {title}",
                    created_at=when)
                hit_status = ["hit", "message_sent", "previously_contacted", "send_failed:demo"][number % 4]
                add(resume_search_hits, **common, search_keyword=title, search_batch_id=batch_id,
                    hit_status=hit_status, created_at=when)
                if state:
                    conn.execute(insert(manual_review_events).values(
                        candidate_fingerprint=fingerprint, previous_status=None, review_status=state,
                        feedback=feedbacks[state], reviewer_id=reviewer.id,
                        reviewer_name_snapshot=reviewer.display_name, revision=1,
                        suppress_communication=True, suppress_report=True,
                        client_ip="127.0.0.1", user_agent="synthetic-demo-seed",
                        created_at=when + timedelta(hours=1)))
    return batches


def _attach_synthetic_evidence(conn, *, fingerprint, job_profile_id, cache_id, experience, fixture_version="demo-v4"):
    row = conn.execute(select(resume_analysis_cache).where(
        resume_analysis_cache.c.id == cache_id
    )).mappings().one()
    run_id = content_hash({"fixture_version": fixture_version, "candidate": fingerprint, "job": job_profile_id})
    persist_analysis_evidence(
        conn, analysis_run_id=run_id, candidate_fingerprint=fingerprint,
        job_profile_id=job_profile_id, analysis_version=row["analysis_version"], raw_text=experience,
        analyzed_text_hash=resume_text_hash(experience), binding_source="synthetic_fixture",
        result=result_document(
            match_result=row["match_result"], match_score=row["match_score"],
            score_breakdown=json.loads(row["score_breakdown_json"]), analysis_reason=row["analysis_reason"],
            evidence_summary=json.loads(row["evidence_summary_json"]), execution_status="succeeded",
        ),
    )
    conn.execute(resume_analysis_cache.update().where(resume_analysis_cache.c.id == cache_id).values(
        analysis_run_id=run_id, analyzed_text_hash=resume_text_hash(experience)
    ))
