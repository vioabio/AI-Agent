// Production preview smoke check for the isolated v6 AI/FDE demo; no review submission.
import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { chromium } from "@playwright/test";

const flag = process.argv.indexOf("--base-url");
const base = new URL(flag >= 0 ? process.argv[flag + 1] : "http://127.0.0.1:15176");
assert(["127.0.0.1", "localhost", "[::1]"].includes(base.hostname));
const directory = path.resolve("../../artifacts/frontend", `ai-demo-${Date.now()}`);
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
  await page.getByText("演示环境 · 仅包含虚构数据", { exact: true }).waitFor();
  await page.getByLabel("用户名").fill("demo");
  await page.getByLabel("密码").fill("DemoReview2026!");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await page.getByRole("link", { name: "影子分析任务", exact: true }).waitFor();
  const all = await (await page.request.get(new URL("/api/v1/review-queue?scope=all&page_size=100", base).href)).json();
  assert(all.total > 0);
  assert.deepEqual(new Set(all.items.map((row) => row.job_profile_id)), new Set(["ai_development_engineer_v1", "fde_ai_deployment_engineer_v1"]));
  const reports = [];
  for (const [job, label] of [["ai_development_engineer_v1", "AI 开发工程师"],
    ["fde_ai_deployment_engineer_v1", "AI 前沿部署工程师（FDE）"]]) {
    await page.goto(new URL("/?scope=all", base).href);
    await page.getByRole("combobox", { name: "岗位", exact: true }).click();
    const [response, summaryResponse] = await Promise.all([
      page.waitForResponse((response) => {
        const url = new URL(response.url());
        return url.pathname === "/api/v1/review-queue" && url.searchParams.get("job_profile_id") === job;
      }),
      page.waitForResponse((response) => {
        const url = new URL(response.url());
        return url.pathname === "/api/v1/dashboard/summary" && url.searchParams.get("job_profile_id") === job;
      }),
      page.locator(".ant-select-dropdown:visible").getByText(label, { exact: true }).click(),
    ]);
    assert.equal(response.status(), 200);
    const rows = await response.json();
    assert(rows.items.length > 0 && rows.items.every((row) => row.job_profile_id === job));
    assert.equal(summaryResponse.status(), 200);
    const summary = await summaryResponse.json();
    await page.locator(".ant-spin-spinning").waitFor({ state: "hidden" });
    await page.locator(".metric-grid .ant-skeleton").first().waitFor({ state: "hidden" });
    assert.equal(await page.locator(".metric-card .ant-statistic").count(), 7);
    assert.equal(await page.locator(".metric-card .ant-statistic-content-value").first().textContent(), String(summary.actionable));
    await page.screenshot({ path: path.join(directory, `${job}-workbench.png`), fullPage: true, animations: "disabled" });
    const person = rows.items[0];
    const detailURL = new URL(`/api/v1/candidates/${person.candidate_fingerprint}`, base).href;
    const before = await (await page.request.get(detailURL)).json();
    assert.equal(before.evidence_status, "verified");
    await page.getByRole("button", { name: "查看", exact: true }).first().click();
    const feedback = page.getByLabel("反馈备注");
    await feedback.fill("虚构 AI/FDE 演示，原文核验后仍未提交。");
    await page.getByRole("button", { name: "查看证据1原文", exact: true }).click();
    assert.equal(await page.getByLabel("引用原文", { exact: true }).textContent(), before.evidence_refs[0].quote);
    assert.equal(await feedback.inputValue(), "虚构 AI/FDE 演示，原文核验后仍未提交。");
    assert.deepEqual(await (await page.request.get(detailURL)).json(), before);
    reports.push({ job_profile_id: job, visible_actionable_count: rows.total, dashboard: summary, exact_quote_verified: true });
  }
  assert.deepEqual(errors, []);
  const cookie = (await context.cookies()).find((item) => item.name === "hr_ai_demo_csrf");
  assert(cookie);
  assert.equal((await page.request.post(new URL("/api/v1/auth/logout", base).href,
    { headers: { "X-CSRF-Token": cookie.value } })).status(), 200);
  const report = { mode: "synthetic_ai_fde_production_preview", jobs: reports, actionable_total: all.total,
    feedback_preserved: true, review_submitted: false, real_model_calls: 0, release_approved: false };
  await writeFile(path.join(directory, "report.json"), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ ...report, report: path.join(directory, "report.json") }));
} finally {
  await browser.close();
}
