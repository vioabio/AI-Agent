from __future__ import annotations

import json
import sys

from liepin_rpa.config import get_settings
from liepin_rpa.coze_workflow import analyze_resume_record_with_coze


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

    settings = get_settings()
    sample_resume_record = {
        "id": 0,
        "candidate_name": "鉴权测试",
        "source_platform": "liepin",
        "search_keyword": "珀莱雅，达播总监",
        "experience": "工作内容：珀莱雅；抖音达播负责人；达播总监；美容仪；脱毛仪；直播分销。",
        "analysis_status": "未执行",
    }

    result = analyze_resume_record_with_coze(
        settings,
        resume_record=sample_resume_record,
    )
    print(
        json.dumps(
            {
                "coze_api_base_url": settings.coze_api_base_url,
                "workflow_id": settings.coze_workflow_id,
                "matched": result.matched,
                "match_result": result.match_result,
                "raw_data": result.raw_data,
                "parsed_payload": result.parsed_payload,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
