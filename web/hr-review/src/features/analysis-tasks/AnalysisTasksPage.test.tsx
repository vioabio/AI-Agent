import { afterEach, expect, test, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { renderApp } from "../../test/render";
import type { AnalysisTask, AnalysisTaskDetail } from "../../shared/api/types";
import { AnalysisTasksPage } from "./AnalysisTasksPage";

const quote = "独立开发 RAG 服务。";
const text = "【虚构】🙂\r\n" + quote + '\n<script>alert("x")</script>';
const start = Array.from(text.slice(0, text.indexOf(quote))).length;
const task: AnalysisTask = {
  job_id: "job-one", candidate_fingerprint: "fake-one", candidate_name: "虚构林工", job_profile_id: "ai_development_engineer_v1",
  batch_id: "fake-batch", collection_completed: true, status: "succeeded", attempts: 1, max_attempts: 3,
  lease_token: 1, last_error_code: null, source_type: "synthetic", can_retry: false,
  available_at: "2026-11-05T01:00:00Z", deadline_at: "2026-11-06T01:00:00Z",
};
const detail: AnalysisTaskDetail = { task, result_status: "verified",
  snapshot: { snapshot_id: "snap-one", content_hash: "hash", normalization_version: "exact-codepoints-v1", raw_text: text, is_current: true },
  result: { beauty_relevant: true, match_result: "relevant", match_score: 90,
    score_breakdown: { industry_depth: 30, beauty_continuity: 20, role_evidence: 40 }, analysis_reason: "预计算合成判断", evidence_summary: [quote] },
  evidence_refs: [{ evidence_id: "ref-one", snapshot_id: "snap-one", chunk_id: "chunk-one", summary_index: 0,
    start, end: start + Array.from(quote).length, quote }],
};
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "Content-Type": "application/json" },
});

const requestUrl = (input: RequestInfo | URL) => typeof input === "string" ? input : input instanceof URL ? input.href : input.url;

afterEach(() => { vi.restoreAllMocks(); document.cookie = "hr_review_csrf=; Max-Age=0"; });

test("collection completion stays separate from pending analysis, without automatic detail fetches", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(response({ enabled: true, items: [{ ...task, status: "pending" }], total: 1, page: 1, page_size: 20 }));
  renderApp(<AnalysisTasksPage />);
  expect(await screen.findByText("等待分析")).toBeVisible();
  expect(screen.getByText("采集已结束")).toBeVisible();
  expect(screen.queryByText("90 分")).not.toBeInTheDocument();
  expect(fetch).toHaveBeenCalledTimes(1);
});

test("verified shadow evidence opens exact Unicode source and never injects HTML", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => Promise.resolve(requestUrl(input).includes("?")
    ? response({ enabled: true, items: [task], total: 1, page: 1, page_size: 20 }) : response(detail)));
  const { container } = renderApp(<AnalysisTasksPage />);
  await userEvent.click(await screen.findByRole("button", { name: "查看任务：虚构林工" }));
  await userEvent.click(await screen.findByRole("button", { name: "查看影子证据1原文" }));
  expect(screen.getAllByLabelText("引用原文")).toHaveLength(1);
  expect(screen.getByLabelText("引用原文").textContent).toBe(quote);
  expect(screen.getByLabelText("分析时简历原文").textContent).toBe(text);
  expect(container.querySelector("script")).toBeNull();
});

test("foreign snapshot references cannot expose a citation action", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => Promise.resolve(requestUrl(input).includes("?")
    ? response({ enabled: true, items: [task], total: 1, page: 1, page_size: 20 }) :
    response({ ...detail, evidence_refs: [{ ...detail.evidence_refs[0], snapshot_id: "foreign" }] })));
  renderApp(<AnalysisTasksPage />);
  await userEvent.click(await screen.findByRole("button", { name: "查看任务：虚构林工" }));
  expect(await screen.findByText("预计算影子结果")).toBeVisible();
  expect(screen.queryByRole("button", { name: "查看影子证据1原文" })).not.toBeInTheDocument();
});

test("runtime shows failed attempts and partial receipt usage without presenting unknown as zero", async () => {
  const failedTask: AnalysisTask = { ...task, status: "failed", last_error_code: "invalid_output" };
  const runtime: NonNullable<AnalysisTaskDetail["execution"]> = {
    mode: "synthetic_only", currency: "USD", price_version: "synthetic-fixed-v1",
    charged_tokens: 4000, max_tokens: 12000, charged_cost_micros: 1000, max_cost_micros: 3000,
    history_truncated: false, attempts: [{ sequence: 1, status: "failed", error_code: "PRIVATE-UPSTREAM-BODY",
      reserved_tokens: 4000, reserved_cost_micros: 1000, reported_tokens: 0, reported_cost_micros: null,
      started_at: "2026-11-05T01:00:00Z", finished_at: "2026-11-05T01:00:01Z",
      request: { state: "received", started_at: "2026-11-05T01:00:00Z", completed_at: "2026-11-05T01:00:01Z",
        http_status: 200, error_code: null, latency_ms: 0, input_tokens: 0, output_tokens: null, reported_cost_micros: null },
    }],
  };
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => Promise.resolve(requestUrl(input).includes("?")
    ? response({ enabled: true, items: [failedTask], total: 1, page: 1, page_size: 20 })
    : response({ ...detail, task: failedTask, result_status: "pending", result: null, execution: runtime })));
  renderApp(<AnalysisTasksPage />);
  await userEvent.click(await screen.findByRole("button", { name: "查看任务：虚构林工" }));
  expect(await screen.findByLabelText("任务运行记录")).toBeVisible();
  expect(screen.getByText("第 1 次尝试 · 失败")).toBeVisible();
  expect(screen.getByText("4,000 / 12,000")).toBeVisible();
  expect(screen.getAllByText("0 / 未知").length).toBeGreaterThan(0);
  await userEvent.click(screen.getByText("请求回执 · 已收到响应"));
  expect(screen.getByText("HTTP 状态")).toBeVisible();
  expect(screen.queryByText("PRIVATE-UPSTREAM-BODY")).not.toBeInTheDocument();
  expect(screen.queryByText("预计算影子结果")).not.toBeInTheDocument();
});

test("pending runtime has no invented attempts or receipts", async () => {
  const pendingTask: AnalysisTask = { ...task, status: "pending", attempts: 0, lease_token: 0 };
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => Promise.resolve(requestUrl(input).includes("?")
    ? response({ enabled: true, items: [pendingTask], total: 1, page: 1, page_size: 20 })
    : response({ ...detail, task: pendingTask, result_status: "pending", result: null,
      execution: { mode: "synthetic_only", currency: "USD", price_version: "synthetic-fixed-v1",
      charged_tokens: 0, max_tokens: 12000, charged_cost_micros: 0, max_cost_micros: 3000,
      attempts: [], history_truncated: false } })));
  renderApp(<AnalysisTasksPage />);
  await userEvent.click(await screen.findByRole("button", { name: "查看任务：虚构林工" }));
  expect(await screen.findByText("尚无领取尝试记录")).toBeVisible();
  expect(screen.queryByText("未记录请求回执")).not.toBeInTheDocument();
});

test("durable tool rounds show their attempt binding and preserve unknown usage", async () => {
  const execution: NonNullable<AnalysisTaskDetail["execution"]> = {
    mode: "synthetic_only", currency: "USD", price_version: "synthetic-fixed-v1", charged_tokens: 8000,
    max_tokens: 12000, charged_cost_micros: 2000, max_cost_micros: 3000, history_truncated: false,
    attempts: [], tool_rounds: [{ sequence: 1, attempt_sequence: 1, state: "received", reserved_tokens: 4000,
      reserved_cost_micros: 1000, input_tokens: 0, output_tokens: null, reported_cost_micros: null,
      started_at: "2026-11-05T01:00:00Z", finished_at: "2026-11-05T01:00:01Z" }],
  };
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => Promise.resolve(requestUrl(input).includes("?")
    ? response({ enabled: true, items: [task], total: 1, page: 1, page_size: 20 })
    : response({ ...detail, execution })));
  renderApp(<AnalysisTasksPage />);
  await userEvent.click(await screen.findByRole("button", { name: "查看任务：虚构林工" }));
  expect(await screen.findByLabelText("工具请求回合")).toBeVisible();
  expect(screen.getByText("第 1 回合 · 已收到响应")).toBeVisible();
  expect(screen.getByText("所属领取尝试：1")).toBeVisible();
  expect(screen.getByText("0 / 未知")).toBeVisible();
});

test("retry sends only the displayed lease version with CSRF and refreshes state", async () => {
  const row = { ...task, status: "retry_wait", can_retry: true, last_error_code: "timeout" };
  document.cookie = "hr_review_csrf=test-csrf";
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation((_input, options) => Promise.resolve(options?.method === "POST"
    ? response({ ...detail, task: row }) : response({ enabled: true, items: [row], total: 1, page: 1, page_size: 20 })));
  renderApp(<AnalysisTasksPage />);
  await userEvent.click(await screen.findByRole("button", { name: "安排重试：虚构林工" }));
  expect(await screen.findByText("已更新重试调度时间，尝试次数和预算保持不变。")).toBeVisible();
  const post = fetch.mock.calls.find(([, options]) => options?.method === "POST");
  expect(post?.[0]).toBe("/api/v1/analysis-tasks/job-one/retry");
  expect(post?.[1]).toMatchObject({ credentials: "include", body: JSON.stringify({ lease_token: 1 }),
    headers: { "X-CSRF-Token": "test-csrf" } });
});

test("conflicting retry shows a safe message and does not repeat the mutation", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation((_input, options) => Promise.resolve(options?.method === "POST"
    ? response({ code: "analysis_task_conflict", message: "PRIVATE UPSTREAM BODY", details: [] }, 409) :
    response({ enabled: true, items: [{ ...task, status: "retry_wait", can_retry: true }], total: 1, page: 1, page_size: 20 })));
  renderApp(<AnalysisTasksPage />);
  await userEvent.click(await screen.findByRole("button", { name: "安排重试：虚构林工" }));
  expect(await screen.findByText("任务状态已变化，请刷新后重试。")).toBeVisible();
  expect(screen.queryByText("PRIVATE UPSTREAM BODY")).not.toBeInTheDocument();
  expect(fetch.mock.calls.filter(([, options]) => options?.method === "POST")).toHaveLength(1);
});

test("invalid results and non-retryable tasks expose neither scores nor retry actions", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => Promise.resolve(requestUrl(input).includes("?")
    ? response({ enabled: true, items: [task], total: 1, page: 1, page_size: 20 }) :
    response({ ...detail, result_status: "invalid" })));
  renderApp(<AnalysisTasksPage />);
  await userEvent.click(await screen.findByRole("button", { name: "查看任务：虚构林工" }));
  expect(await screen.findByText("当前结果校验未通过，请核验数据。")).toBeVisible();
  expect(screen.queryByText("匹配 · 90 分")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "安排重试：虚构林工" })).not.toBeInTheDocument();
});

test("disabled environments explain availability without presenting empty model conclusions", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(response({ enabled: false, items: [], total: 0, page: 1, page_size: 20 }));
  renderApp(<AnalysisTasksPage />);
  expect(await screen.findByText("此环境尚未启用影子分析任务。")).toBeVisible();
  expect(screen.queryByRole("table")).not.toBeInTheDocument();
});
