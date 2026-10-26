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


def seed_expanded_demo(engine) -> list[dict[str, str]]:
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
        for batch_index in range(4):
            end = anchor - timedelta(days=batch_index * 3)
            batch_id = f"synthetic-expanded-{batch_index + 1}"
            batches.append({"batch_id": batch_id,
                            "started_at": (end - timedelta(hours=8)).isoformat() + "+08:00",
                            "ended_at": end.isoformat() + "+08:00"})
            for offset in range(24):
                number = batch_index * 24 + offset + 1
                fingerprint = f"demo-expanded-{number:03d}"
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
                score = (85 + number % 15) if result == "relevant" else (
                    80 + number % 20 if result == "needs_review" else 40 + number % 25)
                status = {"relevant": "匹配", "needs_review": "待复核", "not_relevant": "不匹配"}[result]
                when = end - timedelta(hours=6, minutes=offset * 3)
                achievement = (f"主导{5 + number % 12}家主体核算整合，设计共享中心费用审核流程，维护月度结账报表。"
                               if finance else f"管理{4 + number % 10}人营销团队，负责达人筛选、商务谈判与直播复盘，策划{number + 10}场活动。")
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
                add(resume_experience_records, **common, experience_hash=f"demo-expanded-hash-{number}",
                    experience=experience, analysis_status=status, match_score=score,
                    manual_review_status=state, manual_review_feedback=feedbacks.get(state),
                    manual_review_revision=1 if state else 0,
                    manual_reviewed_by=reviewer.id if state else None,
                    manual_review_assignee_id=reviewer.id if state else None,
                    manual_reviewed_at=when + timedelta(hours=1) if state else None,
                    manual_suppress_communication=int(bool(state)), manual_suppress_report=int(bool(state)),
                    created_at=when, updated_at=when)
                add(resume_analysis_cache, candidate_fingerprint=fingerprint,
                    experience_hash=f"demo-expanded-hash-{number}", experience=experience,
                    analysis_version="synthetic-expanded-v1", analysis_status=status,
                    match_result=result, match_score=score,
                    analysis_reason=f"虚构分析：{'经历匹配，建议进一步核验' if result == 'relevant' else '需核验管理范围及成果证据'}。未调用外部模型。",
                    score_breakdown_json=json.dumps({"行业经验": score // 3, "管理经验": score // 3,
                                                     "岗位证据": score - 2 * (score // 3)}, ensure_ascii=False),
                    evidence_summary_json=json.dumps([achievement, "上海求职意向，统招本科（均为虚构）"], ensure_ascii=False),
                    created_at=when, updated_at=when)
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
