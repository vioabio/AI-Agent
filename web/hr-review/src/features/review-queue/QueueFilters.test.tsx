import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

import { renderApp } from "../../test/render";
import { QueueFilters } from "./QueueFilters";

test("emits score, reviewer and communication filters", async () => {
  const onChange = vi.fn();
  renderApp(<QueueFilters values={{}} allowedJobs={["daren_live_bd_v1"]} onChange={onChange} onReset={vi.fn()} />);

  await userEvent.type(screen.getByLabelText("AI 最低分"), "90");
  expect(onChange).toHaveBeenLastCalledWith("minScore", 90);

  await userEvent.type(screen.getByLabelText("审核人 ID"), "12");
  expect(onChange).toHaveBeenLastCalledWith("reviewerId", 12);

  await userEvent.click(screen.getByRole("combobox", { name: "沟通状态" }));
  await userEvent.click(screen.getByText("已发送"));
  expect(onChange).toHaveBeenLastCalledWith("communication", "sent");
});
