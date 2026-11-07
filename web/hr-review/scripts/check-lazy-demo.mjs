// Run against Vite preview of a demo build and an isolated synthetic API.
import assert from "node:assert/strict";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { chromium } from "@playwright/test";

const flag = process.argv.indexOf("--base-url");
const base = new URL(flag >= 0 ? process.argv[flag + 1] : "http://127.0.0.1:15175");
assert(["127.0.0.1", "localhost", "[::1]"].includes(base.hostname));
const manifest = JSON.parse(await readFile("dist/.vite/manifest.json", "utf8"));
const fileFor = (name) => {
  const item = Object.values(manifest).find((entry) => entry.name === name && entry.isDynamicEntry);
  assert(item);
  return `/${item.file}`;
};
const features = ["WorkbenchPage", "AnalysisTasksPage", "CandidateDetailDrawer", "ReportPreviewDrawer"];
const directory = path.resolve("../../artifacts/frontend", `browser-${Date.now()}`);
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  const context = await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(10_000);
  const requested = new Set();
  const errors = [];
  page.on("request", (request) => requested.add(new URL(request.url()).pathname));
  page.on("pageerror", () => errors.push("page_error"));
  await page.route("**/*", (route) => new URL(route.request().url()).origin === base.origin ? route.continue() : route.abort());
  await page.goto(new URL("/login", base).href);
  await page.getByLabel("用户名").waitFor();
  assert(requested.has(fileFor("LoginPage")));
  for (const name of features) assert(!requested.has(fileFor(name)), `${name} loaded on login`);
  await page.getByLabel("用户名").fill("demo");
  await page.getByLabel("密码").fill("DemoReview2026!");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await page.getByRole("link", { name: "影子分析任务", exact: true }).waitFor();
  assert(requested.has(fileFor("WorkbenchPage")));
  for (const name of features.slice(1)) assert(!requested.has(fileFor(name)), `${name} loaded before use`);
  const detailURL = new URL("/api/v1/candidates/demo-expanded-005", base).href;
  const originalResponse = await page.request.get(detailURL);
  assert.equal(originalResponse.status(), 200);
  const original = await originalResponse.json();
  assert.equal(original.evidence_status, "verified");
  await page.goto(new URL(`/?scope=all&keyword=${encodeURIComponent(original.summary.candidate_name)}`, base).href);
  await page.getByRole("button", { name: "日报实时预览" }).click();
  await page.getByRole("dialog").waitFor();
  assert(requested.has(fileFor("ReportPreviewDrawer")));
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByRole("button", { name: "查看", exact: true }).first().click();
  const feedback = page.getByLabel("反馈备注");
  await feedback.fill("按需加载后保留未提交意见");
  assert(requested.has(fileFor("CandidateDetailDrawer")));
  await page.getByRole("button", { name: "查看证据1原文" }).click();
  await page.getByLabel("引用原文", { exact: true }).waitFor();
  assert.equal(await page.getByLabel("引用原文", { exact: true }).textContent(), original.evidence_refs[0].quote);
  assert.equal(await feedback.inputValue(), "按需加载后保留未提交意见");
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByRole("link", { name: "影子分析任务", exact: true }).click();
  await page.getByText("合成数据 · 预计算结果 · 未调用真实模型", { exact: true }).waitFor();
  assert(requested.has(fileFor("AnalysisTasksPage")));
  await page.reload();
  await page.getByText("合成数据 · 预计算结果 · 未调用真实模型", { exact: true }).waitFor();
  await page.locator(".ant-spin-spinning").waitFor({ state: "hidden" });
  assert.deepEqual(errors, []);
  assert.deepEqual(await (await page.request.get(detailURL)).json(), original);
  await page.screenshot({ path: path.join(directory, "stage7-lazy-tasks.png"), fullPage: true, animations: "disabled" });
  const csrf = (await context.cookies()).find((cookie) => cookie.name === "hr_demo_csrf");
  assert(csrf);
  assert.equal((await page.request.post(new URL("/api/v1/auth/logout", base).href,
    { headers: { "X-CSRF-Token": csrf.value } })).status(), 200);
  // A missing deployed chunk must yield an actionable message rather than a blank page.
  const broken = await browser.newPage();
  broken.setDefaultTimeout(10_000);
  await broken.route("**/*", (route) => {
    const url = new URL(route.request().url());
    return url.origin !== base.origin || url.pathname === fileFor("LoginPage") ? route.abort() : route.continue();
  });
  await broken.goto(new URL("/login", base).href);
  await broken.getByRole("alert").filter({ hasText: "登录页面加载失败" }).waitFor();
  await broken.getByRole("button", { name: "刷新页面", exact: true }).waitFor();
  await broken.screenshot({ path: path.join(directory, "stage7-chunk-failure.png"), fullPage: true });
  const result = { mode: "local_production_demo_browser", lazy_requests_verified: true,
    direct_route_reload_verified: true, chunk_failure_message_verified: true, feedback_preserved: true,
    review_submitted: false, real_model_calls: 0, release_approved: false };
  await writeFile(path.join(directory, "report.json"), JSON.stringify(result, null, 2));
  console.log(JSON.stringify({ ...result, report: path.join(directory, "report.json") }));
} finally {
  await browser.close();
}
