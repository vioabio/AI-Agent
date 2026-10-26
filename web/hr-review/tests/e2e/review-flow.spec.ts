import { expect, test } from "@playwright/test";

test("login, review candidate, refresh metrics, history and report preview", async ({ page }) => {
  let loggedIn = false;
  let reviewed = false;
  let candidateDetailReads = 0;
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/me") return route.fulfill({ status: loggedIn ? 200 : 401, json: loggedIn ? user : apiError("authentication_required", "请登录") });
    if (path === "/api/v1/auth/login") { loggedIn = true; return route.fulfill({ status: 200, headers: { "Set-Cookie": "hr_review_csrf=test-csrf; Path=/; SameSite=Strict" }, json: { user, expires_at: "2026-08-18T22:00:00+08:00" } }); }
    if (path === "/api/v1/batches") return route.fulfill({ json: { items: [{ batch_id: "batch-1", started_at: "2026-08-18T08:00:00+08:00", ended_at: "2026-08-18T17:00:00+08:00", label: "2026-08-18 17:00 完成" }] } });
    if (path === "/api/v1/review-queue") return route.fulfill({ json: { items: reviewed ? [] : [summary], page: 1, page_size: 20, total: reviewed ? 0 : 1 } });
    if (path === "/api/v1/dashboard/summary") return route.fulfill({ json: { actionable: reviewed ? 0 : 1, pending_review: reviewed ? 0 : 1, recommended_contact: reviewed ? 1 : 0, contacted: 0, interviewing: 0, rejected: 0, hired: 0 } });
    if (path === "/api/v1/reports/live-preview") return route.fulfill({ json: { ai_relevant: 1, ai_needs_review: 0, ai_not_relevant: 0, actionable_pending: reviewed ? 0 : 1, hr_status_counts: { pending_review: reviewed ? 0 : 1, recommended_contact: reviewed ? 1 : 0 }, feedback_candidates_in_window: reviewed ? 1 : 0, generated_at: "2026-08-18T13:00:00+08:00" } });
    if (path.endsWith("/review") && route.request().method() === "PATCH") { reviewed = true; return route.fulfill({ json: candidateDetail(true) }); }
    if (path === "/api/v1/candidates/fp-1") { candidateDetailReads += 1; return route.fulfill({ json: candidateDetail(reviewed) }); }
    return route.fulfill({ status: 404, json: apiError("not_found", "not found") });
  });

  await page.goto("/");
  await page.getByLabel("用户名").fill("reviewer");
  await page.getByLabel("密码").fill("correct-password");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page.getByText("陈女士")).toBeVisible();

  await expect(page.getByText("AI 分数范围")).toHaveCount(0);
  await page.getByLabel("审核人姓名").fill("招聘负责人");
  await page.getByLabel("审核人姓名").press("Enter");
  await page.getByRole("combobox", { name: "沟通状态" }).click();
  await page.getByText("已发送", { exact: true }).last().click();
  await expect.poll(() => new URL(page.url()).searchParams.get("reviewer_name")).toBe("招聘负责人");
  await expect.poll(() => new URL(page.url()).searchParams.get("communication")).toBe("sent");
  await page.getByRole("button", { name: "重置筛选" }).click();

  await page.getByRole("columnheader", { name: /AI 分数/ }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("sort")).toBe("match_score");
  await expect.poll(() => new URL(page.url()).searchParams.get("order")).toBe("asc");
  await page.mouse.move(0, 0);
  await page.waitForTimeout(250);
  await page.screenshot({ path: "test-results/hr-review-workbench-visual.png", fullPage: true });

  await page.getByRole("button", { name: "查看" }).click();
  await expect(page.getByText("候选人信息：")).toBeVisible();
  await expect(page.getByText("工作内容：")).toBeVisible();
  await expect(page.getByText("行业深度")).toBeVisible();
  await expect(page.getByText("行业连续性")).toBeVisible();
  await expect(page.getByText("岗位证据")).toBeVisible();
  await expect(page.getByText("分析结论")).toBeVisible();
  await expect(page.getByText("关键证据")).toBeVisible();
  const drawer = page.locator(".ant-drawer-content-wrapper");
  const resizeHandle = page.getByRole("separator", { name: "调整候选人详情宽度" });
  const beforeResize = await drawer.boundingBox();
  const handleBox = await resizeHandle.boundingBox();
  expect(beforeResize).not.toBeNull();
  expect(handleBox).not.toBeNull();
  if (!beforeResize || !handleBox) throw new Error("Drawer resize geometry is unavailable");
  await page.getByLabel("反馈备注").fill("拖动时保留的反馈");
  await page.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + 220);
  await page.mouse.down();
  await page.mouse.move(handleBox.x - 160, handleBox.y + 220, { steps: 6 });
  await page.mouse.up();
  await expect.poll(async () => (await drawer.boundingBox())?.width ?? 0).toBeGreaterThan(beforeResize.width + 120);
  await expect(page.getByLabel("反馈备注")).toHaveValue("拖动时保留的反馈");
  expect(candidateDetailReads).toBe(1);
  await page.locator(".ant-drawer-body").evaluate((element) => { element.scrollTop = 0; });
  await page.waitForTimeout(500);
  await page.screenshot({ path: "test-results/hr-review-detail-visual.png", fullPage: true });
  await page.locator(".ai-analysis-document").scrollIntoViewIfNeeded();
  await page.waitForTimeout(250);
  await page.screenshot({ path: "test-results/hr-review-ai-analysis-visual.png", fullPage: true });
  await page.getByLabel("新状态").focus();
  await page.getByLabel("新状态").press("ArrowDown");
  await page.getByText("建议沟通", { exact: true }).last().click();
  await page.getByLabel("反馈备注").fill("资历和业务经验匹配，建议沟通。");
  await page.getByRole("button", { name: "提交审核" }).click();
  await expect(page.getByText(/^版本 1 ·/)).toBeVisible();
  await expect(page.locator(".ant-timeline").getByText("资历和业务经验匹配，建议沟通。")).toBeVisible();
  const recommendedMetric = page.locator(".metric-card").filter({ hasText: "建议沟通" });
  await expect(recommendedMetric.getByText("1", { exact: true })).toBeVisible();
  await page.locator(".ant-drawer-close").click();
  await page.getByRole("button", { name: "日报实时预览" }).click();
  await expect(page.getByText("未选择批次，已自动展示最新完成批次")).toBeVisible();
  await expect(page.getByText(/数据只读，不会发送日报或修改已发送的飞书卡片/)).toBeVisible();
  await expect(page.getByText("当前批次已反馈候选人：1", { exact: false })).toBeVisible();
  await expect(page.getByText("当前待审核").locator("..")).toContainText("0");
});

const user = { id: 1, username: "reviewer", display_name: "审核员", role: "hr", allowed_job_profile_ids: ["daren_live_bd_v1"], can_view_sensitive: true };
const summary = { candidate_fingerprint: "fp-1", candidate_name: "陈女士", job_profile_id: "daren_live_bd_v1", job_title: "达人营销与达播负责人", age: 32, match_result: "relevant", analysis_status: "匹配", match_score: 95, current_company_title: null, communication_status: "sent", review_status: "pending_review", reviewer_id: null, reviewer_name: null, revision: 0, has_share_link: true, updated_at: "2026-08-18T10:00:00+08:00" };
function candidateDetail(done: boolean) { return { summary: { ...summary, review_status: done ? "recommended_contact" : "pending_review", reviewer_id: done ? 1 : null, reviewer_name: done ? "审核员" : null, revision: done ? 1 : 0 }, experience: "候选人信息：年龄：32岁 工作内容：工作10年以上·5段工作经历；某消费品牌；2025.04-至今(1年4个月)；负责达人营销策略与团队管理： 1. 搭建达人分层运营体系。 2. 优化直播间转化链路。 3. 建立月度复盘机制。 4. 推进跨部门资源协同。", experience_hash: "hash", analysis_version: "v1", score_breakdown: ["beauty_continuity: 14", "industry_depth: 23", "role_evidence: 59"], analysis_reason: "候选人的目标行业经历和岗位职责证据较完整，具备团队管理与跨部门协同经验。", evidence_summary: ["负责达人分层运营体系建设", "具备直播转化链路优化经验", "有跨部门资源协同和月度复盘机制建设经验"], manual_review_feedback: done ? "资历和业务经验匹配，建议沟通。" : null, sources: [{ job_profile_id: "daren_live_bd_v1", search_keyword: "达人营销负责人", search_pack_id: "daren_kol", source_account_alias: "recruiter_a", locator_quality: "share_link", share_link: "https://example.invalid/resume", platform_interaction_note: null, created_at: "2026-08-18T09:00:00+08:00" }], history: done ? [{ id: 1, previous_status: null, review_status: "recommended_contact", feedback: "资历和业务经验匹配，建议沟通。", reviewer_id: 1, reviewer_name: "审核员", revision: 1, created_at: "2026-08-18T13:00:00+08:00" }] : [] }; }
function apiError(code: string, message: string) { return { code, message, details: [] }; }
