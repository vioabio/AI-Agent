import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

import { CandidateTable } from "./CandidateTable";
import { renderApp } from "../../test/render";

const candidate = {
  candidate_fingerprint: "fp-1", candidate_name: "陈女士", job_profile_id: "daren_live_bd_v1",
  job_title: "达人营销与达播负责人", age: 32, match_result: "relevant", analysis_status: "匹配",
  match_score: 92, current_company_title: null, communication_status: "sent", review_status: "pending_review",
  reviewer_id: null, reviewer_name: null, revision: 0, has_share_link: true, updated_at: "2026-08-18T10:00:00+08:00",
};

test("renders candidate and opens detail", async () => {
  const onOpen = vi.fn();
  renderApp(<CandidateTable items={[candidate]} loading={false} page={1} pageSize={20} total={1} sortBy="updated_at" sortOrder="desc" onChange={vi.fn()} onOpen={onOpen} onRetry={vi.fn()} />);
  expect(screen.getByText("陈女士")).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: "查看" }));
  expect(onOpen).toHaveBeenCalledWith("fp-1");
});

test("renders a missing experience-record update time as a dash", async () => {
  const withoutTimestamp = {
    ...candidate,
    candidate_fingerprint: "fp-without-master",
    candidate_name: "仅分析候选人",
    current_company_title: "某公司 · 某职位",
    updated_at: null,
  };
  renderApp(<CandidateTable items={[withoutTimestamp]} loading={false} page={1} pageSize={20} total={1} sortBy="updated_at" sortOrder="desc" onChange={vi.fn()} onOpen={vi.fn()} onRetry={vi.fn()} />);
  const row = screen.getByText("仅分析候选人").closest("tr");
  if (!row) throw new Error("未找到仅分析候选人所在行");
  await waitFor(() => expect(within(row).getByText("-")).toBeVisible());
});

test("renders empty and error states", () => {
  const { rerender } = renderApp(<CandidateTable items={[]} loading={false} page={1} pageSize={20} total={0} sortBy="updated_at" sortOrder="desc" onChange={vi.fn()} onOpen={vi.fn()} onRetry={vi.fn()} />);
  expect(screen.getByText("当前筛选条件下暂无候选人")).toBeVisible();
  rerender(<CandidateTable items={[]} loading={false} error="网络错误" page={1} pageSize={20} total={0} sortBy="updated_at" sortOrder="desc" onChange={vi.fn()} onOpen={vi.fn()} onRetry={vi.fn()} />);
  expect(screen.getByText("候选人列表加载失败")).toBeVisible();
});

test("reports server sort changes", async () => {
  const onChange = vi.fn();
  renderApp(<CandidateTable items={[candidate]} loading={false} page={2} pageSize={20} total={42} sortBy="updated_at" sortOrder="desc" onChange={onChange} onOpen={vi.fn()} onRetry={vi.fn()} />);
  await userEvent.click(screen.getByRole("columnheader", { name: /AI 分数/ }));
  expect(onChange).toHaveBeenCalledWith({ page: 2, pageSize: 20, sortBy: "match_score", sortOrder: "asc" });
});
