from __future__ import annotations

import json
import sys
from pathlib import Path

from liepin_rpa.analysis_gateway import analyze_resume_record
from liepin_rpa.config import get_settings

PROMPT_PATH = Path("config/prompts/finance_shared_management_v3.txt")

SAMPLES = (
    {
        "id": 0,
        "candidate_name": "测试候选人A",
        "source_platform": "synthetic_test",
        "search_keyword": "集团核算经理 民营集团 多主体",
        "age": 33,
        "education": "本科，会计学",
        "experience": (
            "2015.07-2020.05 某成长型企业 总账主管：负责5个法人主体总账、月结、"
            "往来清理和会计政策统一。2020.06-至今 某民营集团 集团核算经理："
            "统筹12个主体月结关账，管理8人团队，主导历史账务整改、费用审批流程"
            "重构和ERP切换，将月结周期从8天缩短到5天。"
        ),
        "analysis_status": "未执行",
    },
    {
        "id": 0,
        "candidate_name": "测试候选人B",
        "source_platform": "synthetic_test",
        "search_keyword": "财务共享经理",
        "age": 32,
        "education": "大专，会计",
        "experience": (
            "2018.01-至今 某企业 应付会计：负责发票审核、付款申请和共享工单处理，"
            "未披露多主体核算、月结、流程建设或团队管理职责。"
        ),
        "analysis_status": "未执行",
    },
)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    settings = get_settings()
    for sample in SAMPLES:
        result = analyze_resume_record(
            settings,
            resume_record=sample,
            prompt_path=PROMPT_PATH,
        )
        print(
            json.dumps(
                {
                    "sample": sample["candidate_name"],
                    "model": settings.llm_model,
                    "match_result": result.match_result,
                    "match_score": result.match_score,
                    "score_breakdown": result.score_breakdown,
                    "analysis_reason": result.analysis_reason,
                    "evidence_summary": result.evidence_summary,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
