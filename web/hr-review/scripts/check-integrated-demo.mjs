import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { chromium } from "@playwright/test";

const flag = process.argv.indexOf("--base-url");
const base = new URL(flag >= 0 ? process.argv[flag + 1] : "http://127.0.0.1:15176");
assert(["127.0.0.1", "localhost", "[::1]"].includes(base.hostname));
const directory = path.resolve("../../artifacts/frontend", `integrated-${Date.now()}`);
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on("pageerror", () => errors.push("page_error"));
  await page.route("**/*", (route) => new URL(route.request().url()).origin === base.origin ? route.continue() : route.abort());
  await page.goto(new URL("/login", base).href);
  await page.getByLabel("用户名").fill("demo");
  await page.getByLabel("密码").fill("DemoReview2026!");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await page.getByRole("link", { name: "影子分析任务", exact: true }).click();
  const tasks = await (await page.request.get(new URL("/api/v1/analysis-tasks", base).href)).json();
  const task = tasks.items.find((item) => item.candidate_fingerprint === "remaining-browser-person");
  assert(task);
  const detail = await (await page.request.get(new URL(`/api/v1/analysis-tasks/${task.job_id}`, base).href)).json();
  assert.equal(detail.execution.tool_rounds.length, 2);
  assert(detail.execution.tool_rounds.every((round) => round.input_tokens === 100 && round.output_tokens === 20));
  await page.getByRole("button", { name: "查看任务：虚构工具回合演示", exact: true }).click();
  await page.getByText("第 1 回合 · 已收到响应", { exact: true }).waitFor();
  await page.getByText("第 2 回合 · 已收到响应", { exact: true }).waitFor();
  await page.getByLabel("工具请求回合", { exact: true }).screenshot({ path: path.join(directory, "durable-tool-rounds.png"), animations: "disabled" });
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.goto(new URL("/?scope=all&keyword=虚构工具回合演示", base).href);
  await page.getByRole("button", { name: "查看", exact: true }).click();
  const feedback = page.getByLabel("反馈备注");
  await feedback.fill("只验证已投影结果的原文，未提交审核。");
  await page.getByRole("button", { name: "查看证据1原文", exact: true }).click();
  assert.equal(await page.getByLabel("引用原文", { exact: true }).textContent(), detail.evidence_refs[0].quote);
  assert.equal(await feedback.inputValue(), "只验证已投影结果的原文，未提交审核。");
  assert.deepEqual(errors, []);
  const csrf = (await context.cookies()).find((cookie) => cookie.name === "hr_ai_demo_csrf");
  assert.equal((await page.request.post(new URL("/api/v1/auth/logout", base).href,
    { headers: { "X-CSRF-Token": csrf.value } })).status(), 200);
  const report = { mode: "integrated_synthetic_production_preview", tool_rounds_verified: 2,
    hr_projection_and_quote_verified: true, feedback_preserved: true, review_submitted: false,
    real_model_calls: 0, release_approved: false };
  await writeFile(path.join(directory, "report.json"), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ ...report, report: path.join(directory, "report.json") }));
} finally {
  await browser.close();
}
