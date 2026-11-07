import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

import { ReviewForm } from "./ReviewForm";
import { renderApp } from "../../test/render";

const detail = {
  summary: { candidate_fingerprint: "fp-1", candidate_name: "陈女士", job_profile_id: "daren_live_bd_v1", job_title: "达人营销与达播负责人", age: 32, match_result: "relevant", analysis_status: "匹配", match_score: 92, current_company_title: null, communication_status: "sent", review_status: "pending_review", reviewer_id: null, reviewer_name: null, revision: 0, has_share_link: false, updated_at: "2026-08-18T10:00:00+08:00" },
  experience: "完整简历", experience_hash: "hash", analysis_version: "v1", evidence_status: "legacy" as const, score_breakdown: [], analysis_reason: "匹配", evidence_summary: [], manual_review_feedback: null, sources: [], history: [],
};

test("requires feedback and prevents read-only submission", async () => {
  const rendered = renderApp(<ReviewForm detail={detail} canSubmit />);
  await userEvent.click(screen.getByRole("button", { name: "提交审核" }));
  expect(await screen.findByText("请填写至少 2 个字的反馈")).toBeVisible();
  rendered.unmount();
  renderApp(<ReviewForm detail={detail} canSubmit={false} />);
  expect(screen.getByText("当前账号为只读权限，不能修改 HR 状态。")).toBeVisible();
});

test("shows 409 conflict and reload action", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(JSON.stringify({ code: "review_conflict", message: "数据已被其他人修改，请重新加载", details: [] }), { status: 409, headers: { "Content-Type": "application/json" } }));
  renderApp(<ReviewForm detail={detail} canSubmit />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("反馈备注"), "建议进入沟通阶段");
  await user.click(screen.getByRole("button", { name: "提交审核" }));
  expect(await screen.findByText("数据已被其他人修改")).toBeVisible();
  expect(screen.getByRole("button", { name: "重新加载最新数据" })).toBeVisible();
});
