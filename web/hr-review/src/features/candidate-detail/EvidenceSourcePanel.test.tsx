import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test } from "vitest";

import type { AnalysisSnapshot, CandidateDetail, EvidenceReference } from "../../shared/api/types";
import { renderApp } from "../../test/render";
import { CandidateDetailDrawer } from "./CandidateDetailDrawer";
import { EvidenceSourcePanel } from "./EvidenceSourcePanel";
import { SnapshotDocument } from "./SnapshotDocument";
import { verifiedReferences } from "./evidenceReferences";
import { vi } from "vitest";

const text = "  简历🙂\r\n重复句。重复句。\n项目内容：负责RAG检索上线。\n";
const quote = "负责RAG检索上线。";
const start = Array.from(text.slice(0, text.indexOf(quote))).length;
const snapshot: AnalysisSnapshot = {
  snapshot_id: "snapshot-one", content_hash: "hash", normalization_version: "exact-codepoints-v1",
  raw_text: text, is_current: true,
};
const reference: EvidenceReference = {
  evidence_id: "evidence-one", snapshot_id: "snapshot-one", chunk_id: "chunk-one",
  summary_index: 0, start, end: start + Array.from(quote).length, quote,
};

function detail(): CandidateDetail {
  return {
    summary: {
      candidate_fingerprint: "test-person", candidate_name: "虚构候选人", job_profile_id: "daren_live_bd_v1",
      job_title: "演示岗位", age: null, match_result: "relevant", analysis_status: "匹配", match_score: 90,
      current_company_title: null, communication_status: "not_sent", review_status: "pending_review",
      reviewer_id: null, reviewer_name: null, revision: 0, has_share_link: false, updated_at: null,
    },
    experience: text, experience_hash: "hash", analysis_version: "v1", analysis_run_id: "run-one",
    analysis_snapshot: snapshot, evidence_status: "verified", evidence_refs: [reference],
    score_breakdown: [], analysis_reason: "合成判断", evidence_summary: [quote, "概括性摘要，暂无原文"],
    manual_review_feedback: null, sources: [], history: [],
  };
}

test("highlights exact code points after emoji while preserving CRLF and whitespace", () => {
  render(<SnapshotDocument snapshot={snapshot} reference={reference} />);
  expect(screen.getByLabelText("引用原文")).toHaveTextContent(quote);
  expect(screen.getByLabelText("分析时简历原文").textContent).toBe(text);
  expect(screen.getByLabelText("引用原文")).toHaveFocus();
});

test("rejects references pointing to another snapshot, stale quote or invalid range", () => {
  for (const invalid of [
    { ...reference, snapshot_id: "other-person" },
    { ...reference, start: -1 }, { ...reference, end: 9999 },
    { ...reference, quote: "伪造引用" }, { ...reference, start: start + 1 },
  ]) {
    expect(verifiedReferences(snapshot, [invalid], [quote])).toEqual([]);
  }
});

test("opens only verified references and labels historical versions", async () => {
  const data = detail();
  data.analysis_snapshot = { ...snapshot, is_current: false };
  renderApp(<EvidenceSourcePanel detail={data} />);
  expect(screen.queryByRole("button", { name: "查看证据2原文" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "查看证据1原文" }));
  expect(screen.getByText("该引用来自历史简历版本")).toBeVisible();
  expect(screen.getByLabelText("引用原文")).toHaveTextContent(quote);
  await userEvent.click(screen.getByRole("button", { name: "收起原文定位" }));
  expect(screen.queryByLabelText("分析时简历原文")).not.toBeInTheDocument();
});

test("legacy and invalid responses never expose citation buttons", () => {
  const data = detail();
  const { rerender } = render(<EvidenceSourcePanel detail={{ ...data, evidence_status: "legacy" }} />);
  expect(screen.queryByRole("button", { name: "查看证据1原文" })).not.toBeInTheDocument();
  rerender(<EvidenceSourcePanel detail={{ ...data, evidence_status: "invalid" }} />);
  expect(screen.getByText("原文引用校验未通过")).toBeVisible();
  expect(screen.queryByRole("button", { name: "查看证据1原文" })).not.toBeInTheDocument();
});

test("source text renders as text and cannot inject HTML", () => {
  const unsafe = '<script>alert("x")</script>';
  const data = { ...snapshot, raw_text: unsafe };
  const ref = { ...reference, start: 0, end: Array.from(unsafe).length, quote: unsafe };
  const { container } = render(<SnapshotDocument snapshot={data} reference={ref} />);
  expect(screen.getByLabelText("引用原文").textContent).toBe(unsafe);
  expect(container.querySelector("script")).toBeNull();
});

test("opening an original quote does not remount or clear review feedback", async () => {
  const data = detail();
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(data), {
    status: 200, headers: { "Content-Type": "application/json" },
  }));
  try {
    renderApp(<CandidateDetailDrawer fingerprint="test-person" onClose={vi.fn()} canSubmit />);
    const feedback = await screen.findByLabelText("反馈备注");
    await userEvent.type(feedback, "保留正在填写的核验意见");
    await userEvent.click(screen.getByRole("button", { name: "查看证据1原文" }));
    expect(feedback).toHaveValue("保留正在填写的核验意见");
    expect(screen.getByLabelText("引用原文")).toHaveTextContent(quote);
    expect(fetch).toHaveBeenCalledTimes(1);
  } finally {
    fetch.mockRestore();
  }
});

function searchResponse(overrides: Record<string, unknown> = {}) {
  return {
    status: "available", analysis_run_id: "run-one", snapshot_id: snapshot.snapshot_id,
    dimension_id: "role_evidence", dimension_label: "工程落地", retrieval_version: "retrieval-test",
    hits: [{ chunk_id: reference.chunk_id, snapshot_id: snapshot.snapshot_id,
      start: reference.start, end: reference.end, quote, section: "项目内容", organization: null }],
    message: null, ...overrides,
  };
}

test("retrieval is manual, read-only, and shares a single viewer with AI citations", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify(searchResponse()), {
    status: 200, headers: { "Content-Type": "application/json" },
  }));
  try {
    renderApp(<EvidenceSourcePanel detail={detail()} />);
    expect(fetch).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "查看证据1原文" }));
    await userEvent.click(screen.getByRole("button", { name: "检索原文片段" }));
    await userEvent.click(await screen.findByRole("button", { name: "定位检索片段1" }));
    expect(screen.getAllByLabelText("引用原文")).toHaveLength(1);
    expect(screen.getByText("检索片段原文（非 AI 结论引用）")).toBeVisible();
    expect(screen.getByLabelText("引用原文")).toHaveTextContent(quote);
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/evidence-search?dimension_id=role_evidence"),
      expect.objectContaining({ credentials: "include" }));
    expect(fetch.mock.calls.every(([, options]) => !options?.method || options.method === "GET")).toBe(true);
  } finally { fetch.mockRestore(); }
});

test.each(["analysis_run_id", "snapshot_id"])("stale %s results cannot expose a source fragment", async (field) => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(searchResponse({ [field]: "foreign-version" })), {
    status: 200, headers: { "Content-Type": "application/json" },
  }));
  try {
    renderApp(<EvidenceSourcePanel detail={detail()} />);
    await userEvent.click(screen.getByRole("button", { name: "检索原文片段" }));
    expect(await screen.findByText("分析版本已更新，请重新加载候选人详情后检索。")).toBeVisible();
    expect(screen.queryByRole("button", { name: "定位检索片段1" })).not.toBeInTheDocument();
  } finally { fetch.mockRestore(); }
});

test("empty retrieval is not presented as a negative candidate decision", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(searchResponse({ hits: [] })), {
    status: 200, headers: { "Content-Type": "application/json" },
  }));
  try {
    renderApp(<EvidenceSourcePanel detail={detail()} />);
    await userEvent.click(screen.getByRole("button", { name: "检索原文片段" }));
    expect(await screen.findByText("没有检索到可定位片段；这不表示候选人没有相关经历。")).toBeVisible();
    expect(screen.getByText("合成判断")).toBeVisible();
  } finally { fetch.mockRestore(); }
});

test("failed retrieval exposes no provider body and can be retried", async () => {
  let calls = 0;
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
    calls += 1;
    return calls === 1 ? new Response(JSON.stringify({ code: "error", message: "private upstream detail", details: [] }), {
      status: 500, headers: { "Content-Type": "application/json" },
    }) : new Response(JSON.stringify(searchResponse()), { status: 200, headers: { "Content-Type": "application/json" } });
  });
  try {
    renderApp(<EvidenceSourcePanel detail={detail()} />);
    await userEvent.click(screen.getByRole("button", { name: "检索原文片段" }));
    expect(await screen.findByText("原文检索失败，请重试。")).toBeVisible();
    expect(screen.queryByText("private upstream detail")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "检索原文片段" }));
    expect(await screen.findByRole("button", { name: "定位检索片段1" })).toBeVisible();
    expect(calls).toBe(2);
  } finally { fetch.mockRestore(); }
});
