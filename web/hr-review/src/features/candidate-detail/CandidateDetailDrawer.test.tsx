import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";

import type { CandidateDetail } from "../../shared/api/types";
import { renderApp } from "../../test/render";
import { CandidateDetailDrawer } from "./CandidateDetailDrawer";

const detail = {
  summary: {
    candidate_fingerprint: "fp-1",
    candidate_name: "陈女士",
    job_profile_id: "finance_shared_management_v1",
    job_title: "财务共享与管理经理",
    age: 32,
    match_result: "relevant",
    analysis_status: "匹配",
    match_score: 94,
    current_company_title: "某保险公司 · 财务经理",
    communication_status: "not_sent",
    review_status: "pending_review",
    reviewer_id: null,
    reviewer_name: null,
    revision: 0,
    has_share_link: false,
    updated_at: "2026-08-18T10:00:00+08:00",
  },
  experience: "候选人信息：年龄：32岁 工作内容：工作10年以上·5段工作经历；某保险公司；2025.04-至今(1年4个月)；统筹总账核算与财务数字化升级： 1. 往来账款专项治理。 2. 应收保费精细化管控。 3. 月结全流程管控。 4. 标准化业财核对体系搭建。",
  experience_hash: "hash",
  analysis_version: "v2",
  score_breakdown: ["beauty_continuity: 14", "industry_depth: 23", "role_evidence: 59"],
  analysis_reason: "经验匹配",
  evidence_summary: ["具备复杂业务管理经验", "有团队协同和流程建设证据"],
  manual_review_feedback: null,
  sources: [],
  history: [],
} satisfies CandidateDetail;

afterEach(() => { vi.restoreAllMocks(); });

test("renders a structured resume and resizes without losing review input", async () => {
  const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(detail));
  renderApp(<CandidateDetailDrawer fingerprint="fp-1" onClose={vi.fn()} canSubmit />);

  expect(await screen.findByText("陈女士")).toBeVisible();
  expect(screen.getByText("候选人信息：")).toBeVisible();
  expect(screen.getByText("工作内容：")).toBeVisible();
  expect(screen.getByText("1.")).toBeVisible();
  expect(screen.getByText("行业深度")).toBeVisible();
  expect(screen.getByText("行业连续性")).toBeVisible();
  expect(screen.getByText("岗位证据")).toBeVisible();
  expect(screen.queryByText(/beauty_continuity|industry_depth|role_evidence/)).not.toBeInTheDocument();
  expect(screen.getByText("分析结论")).toBeVisible();
  expect(screen.getByText("关键证据")).toBeVisible();

  const feedback = screen.getByLabelText("反馈备注");
  await userEvent.setup().type(feedback, "保留这段审核意见");
  const separator = screen.getByRole("separator", { name: "调整候选人详情宽度" });
  expect(separator).toHaveAttribute("aria-valuenow", "760");

  fireEvent.keyDown(separator, { key: "ArrowLeft" });
  expect(separator).toHaveAttribute("aria-valuenow", "808");
  expect(feedback).toHaveValue("保留这段审核意见");
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
