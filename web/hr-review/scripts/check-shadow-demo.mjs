// Real local synthetic API/UI check, including one permitted synthetic reschedule.
import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { chromium } from "@playwright/test";

const flag = process.argv.indexOf("--base-url");
const baseURL = new URL(flag >= 0 ? process.argv[flag + 1] : "http://127.0.0.1:15174");
assert(["127.0.0.1", "localhost", "[::1]"].includes(baseURL.hostname));
const outputDir = path.resolve("../../artifacts/screenshots");
const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: "zh-CN" });
  const page = await context.newPage();
  await page.route("**/*", (route) => new URL(route.request().url()).origin === baseURL.origin ? route.continue() : route.abort());
  await page.goto(new URL("/login", baseURL).href);
  await page.getByText("演示环境 · 仅包含虚构数据", { exact: true }).waitFor();
  await page.getByLabel("用户名").fill("demo");
  await page.getByLabel("密码").fill("DemoReview2026!");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await page.waitForURL((url) => url.pathname === "/");
  const original = await (await page.request.get(new URL("/api/v1/candidates/demo-expanded-005", baseURL).href)).json();
  await page.getByRole("link", { name: "影子分析任务", exact: true }).click();
  await page.getByText("合成数据 · 预计算结果 · 未调用真实模型", { exact: true }).waitFor();
  const listResponse = await page.request.get(new URL("/api/v1/analysis-tasks", baseURL).href);
  assert.equal(listResponse.status(), 200);
  const list = await listResponse.json();
  assert.equal(list.enabled, true);
  assert(list.items.every((row) => row.source_type === "synthetic"));
  const success = list.items.find((row) => row.candidate_fingerprint === "shadow-ai");
  const retry = list.items.find((row) => row.candidate_fingerprint === "shadow-timeout");
  assert(success && retry?.can_retry);
  const detail = await (await page.request.get(new URL(`/api/v1/analysis-tasks/${success.job_id}`, baseURL).href)).json();
  assert.equal(detail.result_status, "verified");
  assert.equal(detail.execution.mode, "synthetic_only");
  assert.equal(detail.execution.attempts.length, success.attempts);
  assert.equal(detail.execution.attempts[0].request, null);
  await page.getByRole("button", { name: `查看任务：${success.candidate_name}`, exact: true }).click();
  await page.getByLabel("任务运行记录", { exact: true }).waitFor();
  await page.getByText("未记录请求回执", { exact: true }).waitFor();
  await mkdir(outputDir, { recursive: true });
  await page.getByLabel("任务运行记录", { exact: true }).screenshot({ path: path.join(outputDir, "stage5-execution-history.png"), animations: "disabled" });
  await page.getByRole("button", { name: "查看影子证据1原文" }).click();
  const mark = page.getByLabel("引用原文", { exact: true });
  assert.equal(await mark.textContent(), detail.evidence_refs[0].quote);
  await mkdir(outputDir, { recursive: true });
  await page.getByRole("dialog").screenshot({ path: path.join(outputDir, "stage5-shadow-evidence.png"), animations: "disabled" });
  await page.getByRole("button", { name: "Close", exact: true }).click();
  const [retried] = await Promise.all([
    page.waitForResponse((response) => response.request().method() === "POST" && response.url().endsWith(`/${retry.job_id}/retry`)),
    page.getByRole("button", { name: `安排重试：${retry.candidate_name}`, exact: true }).click(),
  ]);
  assert.equal(retried.status(), 200);
  const updated = await retried.json();
  assert.equal(updated.task.attempts, retry.attempts);
  assert.equal(updated.task.lease_token, retry.lease_token);
  assert.equal(updated.task.status, "retry_wait");
  await page.getByText("已更新重试调度时间，尝试次数和预算保持不变。", { exact: true }).waitFor();
  await page.screenshot({ path: path.join(outputDir, "stage5-shadow-tasks.png"), fullPage: true, animations: "disabled" });
  const after = await (await page.request.get(new URL("/api/v1/candidates/demo-expanded-005", baseURL).href)).json();
  assert.deepEqual(after, original);
  const csrf = (await context.cookies()).find((cookie) => cookie.name === "hr_demo_csrf");
  assert(csrf);
  assert.equal((await page.request.post(new URL("/api/v1/auth/logout", baseURL).href, { headers: { "X-CSRF-Token": csrf.value } })).status(), 200);
  console.log(JSON.stringify({ exact_quote_verified: true, synthetic_retry_verified: true, legacy_detail_unchanged: true,
    execution_history_verified: true,
    review_submitted: false, model_called: false, screenshots: ["stage5-shadow-tasks.png", "stage5-shadow-evidence.png", "stage5-execution-history.png"].map((name) => path.join(outputDir, name)) }));
} finally {
  await browser.close();
}
