// Exercise the real local synthetic API + UI; never submit a candidate review.
import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { chromium } from "@playwright/test";

const flag = process.argv.indexOf("--base-url");
const baseURL = new URL(flag >= 0 ? process.argv[flag + 1] : "http://127.0.0.1:15174");
assert(["127.0.0.1", "localhost", "[::1]"].includes(baseURL.hostname), "Only a local demo is supported");
const retrievalMode = process.argv.includes("--retrieval");
const output = path.resolve(`../../artifacts/screenshots/${retrievalMode ? "stage4-retrieval-highlight" : "stage3-evidence-highlight"}.png`);
const resultsOutput = path.resolve("../../artifacts/screenshots/stage4-retrieval-results.png");
const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: "zh-CN" });
  const page = await context.newPage();
  await page.route("**/*", (route) => {
    const url = new URL(route.request().url());
    return url.origin === baseURL.origin ? route.continue() : route.abort();
  });
  await page.goto(new URL("/login", baseURL).href);
  await page.getByText("演示环境 · 仅包含虚构数据", { exact: true }).waitFor();
  await page.getByLabel("用户名").fill("demo");
  await page.getByLabel("密码").fill("DemoReview2026!");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await page.waitForURL((url) => url.pathname === "/");
  const queueResponse = await page.request.get(new URL("/api/v1/review-queue?scope=all&page_size=100", baseURL).href);
  assert.equal(queueResponse.status(), 200);
  const queue = await queueResponse.json();
  const candidate = queue.items.find((row) => row.candidate_fingerprint.startsWith("demo-expanded-"));
  assert(candidate, "Expected a synthetic candidate with evidence");
  const detailResponse = await page.request.get(new URL(`/api/v1/candidates/${candidate.candidate_fingerprint}`, baseURL).href);
  assert.equal(detailResponse.status(), 200);
  const detail = await detailResponse.json();
  assert.equal(detail.evidence_status, "verified");
  assert.equal(detail.evidence_refs.length, 1);
  await page.goto(new URL(`/?scope=all&keyword=${encodeURIComponent(candidate.candidate_name)}`, baseURL).href);
  await page.getByRole("button", { name: "查看", exact: true }).first().click();
  const feedback = page.getByLabel("反馈备注");
  await feedback.fill("原文核验演示，保留未提交的审核意见。");
  await page.getByRole("button", { name: "查看证据1原文" }).click();
  const mark = page.getByLabel("引用原文", { exact: true });
  await mark.waitFor({ state: "visible" });
  assert.equal(await mark.textContent(), detail.evidence_refs[0].quote);
  assert.equal(await feedback.inputValue(), "原文核验演示，保留未提交的审核意见。");
  await mkdir(path.dirname(output), { recursive: true });
  if (retrievalMode) {
    const [response] = await Promise.all([
      page.waitForResponse((result) => result.url().includes("/evidence-search?dimension_id=role_evidence")),
      page.getByRole("button", { name: "检索原文片段" }).click(),
    ]);
    assert.equal(response.status(), 200);
    const search = await response.json();
    assert.equal(search.snapshot_id, detail.analysis_snapshot.snapshot_id);
    await page.getByRole("button", { name: "定位检索片段1" }).waitFor();
    await page.getByLabel("岗位证据检索", { exact: true }).screenshot({ path: resultsOutput, animations: "disabled" });
    await page.getByRole("button", { name: "定位检索片段1" }).click();
    await page.getByText("检索片段原文（非 AI 结论引用）", { exact: true }).waitFor();
    assert.equal(await mark.textContent(), search.hits[0].quote);
    assert.equal(await feedback.inputValue(), "原文核验演示，保留未提交的审核意见。");
  }
  await page.getByLabel("原文证据定位", { exact: true }).screenshot({ path: output, animations: "disabled" });
  const csrf = (await context.cookies()).find((cookie) => cookie.name === "hr_demo_csrf");
  assert(csrf);
  const logout = await page.request.post(new URL("/api/v1/auth/logout", baseURL).href, {
    headers: { "X-CSRF-Token": csrf.value },
  });
  assert.equal(logout.status(), 200);
  console.log(JSON.stringify({ screenshot: output, exact_quote_verified: true, review_feedback_preserved: true,
    review_submitted: false, retrieval_verified: retrievalMode, results_screenshot: retrievalMode ? resultsOutput : null,
    candidate_fingerprint: candidate.candidate_fingerprint }));
} finally {
  await browser.close();
}
