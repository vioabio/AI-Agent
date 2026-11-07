import { afterEach, expect, it, vi } from "vitest";
import { api } from "./client";

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  for (const name of ["hr_review_csrf", "hr_demo_csrf", "custom_csrf"]) {
    document.cookie = `${name}=; Max-Age=0; path=/`;
  }
});

it.each([
  ["false", "", "real-token"],
  ["true", "", "demo-token"],
  ["false", "custom", "custom-token"],
])("uses only the configured CSRF cookie (demo=%s, prefix=%s)", async (demo, prefix, expected) => {
  vi.stubEnv("VITE_HR_DEMO", demo);
  vi.stubEnv("VITE_HR_COOKIE_PREFIX", prefix);
  document.cookie = "hr_review_csrf=real-token; path=/";
  document.cookie = "hr_demo_csrf=demo-token; path=/";
  document.cookie = "custom_csrf=custom-token; path=/";
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ message: "ok" }) });
  vi.stubGlobal("fetch", fetchMock);
  await api.logout();
  expect(fetchMock).toHaveBeenCalledWith("/api/v1/auth/logout", expect.objectContaining({
    credentials: "include",
    headers: expect.objectContaining({ "X-CSRF-Token": expected }),
  }));
});
