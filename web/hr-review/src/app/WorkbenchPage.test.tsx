import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, test, vi } from "vitest";

import { renderApp } from "../test/render";
import type { User } from "../shared/api/types";
import { WorkbenchPage } from "./WorkbenchPage";

const candidate = {
  candidate_fingerprint: "fp-1",
  candidate_name: "陈女士",
  job_profile_id: "daren_live_bd_v1",
  job_title: "达人营销与达播负责人",
  age: 32,
  match_result: "relevant",
  analysis_status: "匹配",
  match_score: 92,
  current_company_title: null,
  communication_status: "not_sent",
  review_status: "pending_review",
  reviewer_id: null,
  reviewer_name: null,
  revision: 0,
  has_share_link: false,
  updated_at: "2026-08-18T10:00:00+08:00",
};

const user = {
  id: 1,
  username: "hr",
  display_name: "审核员",
  role: "recruiting_lead",
  allowed_job_profile_ids: [],
  can_view_sensitive: false,
} satisfies User;

afterEach(() => {
  vi.restoreAllMocks();
});

test("collapses filters and keeps the populated queue stable across tab changes", async () => {
  let queueCalls = 0;
  const queueUrls: string[] = [];
  const reportUrls: string[] = [];
  let resolveQueueRefetch: ((response: Response) => void) | undefined;
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
    const path = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    if (path.startsWith("/api/v1/review-queue")) {
      queueCalls += 1;
      queueUrls.push(path);
      if (queueCalls === 2) {
        return new Promise<Response>((resolve) => { resolveQueueRefetch = resolve; });
      }
      return Promise.resolve(jsonResponse({ items: [candidate], page: 1, page_size: 20, total: 1 }));
    }
    if (path.startsWith("/api/v1/dashboard/summary")) {
      return Promise.resolve(jsonResponse({ actionable: 1, pending_review: 1, recommended_contact: 0, contacted: 0, interviewing: 0, rejected: 0, hired: 0 }));
    }
    if (path.startsWith("/api/v1/reports/live-preview")) {
      reportUrls.push(path);
      return Promise.resolve(jsonResponse({
        ai_relevant: 3,
        ai_needs_review: 8,
        ai_not_relevant: 10,
        actionable_pending: 2,
        hr_status_counts: {},
        feedback_candidates_in_window: 1,
        generated_at: "2026-08-19T10:43:54+08:00",
      }));
    }
    if (path === "/api/v1/batches") return Promise.resolve(jsonResponse({ items: [{ batch_id: "batch-1", started_at: "2026-08-18T08:00:00+08:00", ended_at: "2026-08-18T17:00:00+08:00", label: "2026-08-18 17:00 完成" }] }));
    throw new Error(`Unexpected request: ${path}`);
  });

  const { container } = renderApp(<WorkbenchPage user={user} />, {
    initialEntries: ["/?job=daren_live_bd_v1"],
  });
  expect(await screen.findByText("陈女士")).toBeVisible();
  expect(screen.getByRole("tab", { name: "待审核" })).toBeVisible();
  expect(screen.queryByRole("tab", { name: "待我审核" })).not.toBeInTheDocument();
  expect(screen.queryByRole("tab", { name: "全部待审核" })).not.toBeInTheDocument();
  expect(screen.getByRole("columnheader", { name: "审核人" })).toBeVisible();
  expect(queueUrls[0]).toContain("scope=pending");
  expect(queueUrls[0]).not.toContain("batch_id");
  expect(queueUrls[0]).toContain("job_profile_id=daren_live_bd_v1");

  fireEvent.click(screen.getByRole("button", { name: /日报实时预览/ }));
  expect(await screen.findByText("未选择批次，已自动展示最新完成批次")).toBeVisible();
  await waitFor(() => { expect(reportUrls[0]).toContain("batch_id=batch-1"); });
  expect(reportUrls[0]).not.toContain("job_profile_id");
  expect(screen.getByText(/全部岗位/)).toBeVisible();
  expect(await screen.findByText("AI 原始三态")).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "Close" }));

  fireEvent.click(screen.getByRole("button", { name: "收起筛选栏" }));
  expect(container.querySelector(".sidebar")).toHaveClass("ant-layout-sider-collapsed");
  expect(screen.getByRole("button", { name: "展开筛选栏" })).toHaveAttribute("aria-expanded", "false");

  fireEvent.click(screen.getByRole("tab", { name: "全部候选人" }));
  await waitFor(() => { expect(queueCalls).toBe(2); });
  expect(screen.getByText("陈女士")).toBeVisible();
  expect(container.querySelector(".ant-table .ant-spin-spinning")).not.toBeInTheDocument();

  act(() => {
    resolveQueueRefetch?.(jsonResponse({ items: [candidate], page: 1, page_size: 20, total: 1 }));
  });
  fireEvent.click(screen.getByRole("button", { name: "展开筛选栏" }));
  fireEvent.mouseDown(screen.getByRole("combobox", { name: "搜索批次" }));
  fireEvent.click(await screen.findByText("2026-08-18 17:00 完成"));
  await waitFor(() => { expect(queueUrls.some((url) => url.includes("batch_id=batch-1"))).toBe(true); });

}, 20_000);

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
