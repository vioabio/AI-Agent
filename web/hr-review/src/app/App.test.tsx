import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { focusManager } from "@tanstack/react-query";
import { afterEach, test, vi } from "vitest";

import { renderApp } from "../test/render";
import { App } from "./App";

afterEach(() => {
  focusManager.setFocused(undefined);
  vi.restoreAllMocks();
});

test("keeps the login form mounted while session is rechecked on window focus", async () => {
  let resolveRefetch: ((response: Response) => void) | undefined;
  const fetchMock = vi.spyOn(globalThis, "fetch")
    .mockResolvedValueOnce(unauthorizedResponse())
    .mockImplementationOnce(() => new Promise<Response>((resolve) => {
      resolveRefetch = resolve;
    }));

  renderApp(<App />);
  const username = await screen.findByLabelText("用户名");
  await userEvent.setup().type(username, "hr_demo");

  act(() => {
    focusManager.setFocused(false);
    focusManager.setFocused(true);
  });

  await waitFor(() => { expect(fetchMock).toHaveBeenCalledTimes(2); });
  expect(screen.getByLabelText("用户名")).toHaveValue("hr_demo");
  expect(screen.queryByText("正在验证登录状态")).not.toBeInTheDocument();

  resolveRefetch?.(unauthorizedResponse());
  await waitFor(() => { expect(fetchMock).toHaveBeenCalledTimes(2); });
});

function unauthorizedResponse(): Response {
  return new Response(
    JSON.stringify({ code: "authentication_required", message: "需要登录", details: [] }),
    { status: 401, headers: { "Content-Type": "application/json" } },
  );
}
