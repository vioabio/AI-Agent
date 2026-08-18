import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

import { LoginPage } from "./LoginPage";
import { renderApp } from "../../test/render";

test("logs in with validated credentials", async () => {
  const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ user: { id: 1, username: "hr", display_name: "审核员", role: "hr", allowed_job_profile_ids: ["daren_live_bd_v1"], can_view_sensitive: false }, expires_at: "2026-08-18T20:00:00+08:00" }), { status: 200, headers: { "Content-Type": "application/json" } }));
  renderApp(<LoginPage />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("用户名"), "hr");
  await user.type(screen.getByLabelText("密码"), "correct-password");
  await user.click(screen.getByRole("button", { name: "登录工作台" }));
  expect(fetchMock).toHaveBeenCalledWith("/api/v1/auth/login", expect.objectContaining({ method: "POST", credentials: "include" }));
  fetchMock.mockRestore();
});

test("shows server login failure", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(JSON.stringify({ code: "authentication_required", message: "用户名或密码错误", details: [] }), { status: 401, headers: { "Content-Type": "application/json" } }));
  renderApp(<LoginPage />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("用户名"), "hr");
  await user.type(screen.getByLabelText("密码"), "wrong-password");
  await user.click(screen.getByRole("button", { name: "登录工作台" }));
  expect(await screen.findByText("用户名或密码错误")).toBeVisible();
});
