import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

import { renderApp } from "../../test/render";
import { QueueFilters } from "./QueueFilters";

test("emits score, reviewer and communication filters", async () => {
  const onChange = vi.fn();
  renderApp(<QueueFilters values={{}} batches={[{ value: "batch-1", label: "2026-08-18 17:00 完成" }]} allowedJobs={["daren_live_bd_v1"]} onChange={onChange} onReset={vi.fn()} />);

  await userEvent.click(screen.getByRole("combobox", { name: "搜索批次" }));
  await userEvent.click(screen.getByText("2026-08-18 17:00 完成"));
  expect(onChange).toHaveBeenLastCalledWith("batch", "batch-1");

  await userEvent.type(screen.getByLabelText("AI 最低分"), "90");
  expect(onChange).toHaveBeenLastCalledWith("minScore", 90);

  await userEvent.type(screen.getByLabelText("审核人 ID"), "12");
  expect(onChange).toHaveBeenLastCalledWith("reviewerId", 12);

  await userEvent.click(screen.getByRole("combobox", { name: "沟通状态" }));
  await userEvent.click(screen.getByText("已发送"));
  expect(onChange).toHaveBeenLastCalledWith("communication", "sent");
});

test("keeps an edited keyword draft until search is submitted", async () => {
  const onChange = vi.fn();
  renderApp(<QueueFilters values={{ keyword: "旧关键词" }} batches={[]} allowedJobs={[]} onChange={onChange} onReset={vi.fn()} />);
  const search = screen.getByPlaceholderText("姓名、公司、职位或关键词");
  const user = userEvent.setup();

  await user.clear(search);
  await user.type(search, "新关键词");
  expect(search).toHaveValue("新关键词");
  expect(onChange).not.toHaveBeenCalledWith("keyword", "新关键词");

  await user.keyboard("{Enter}");
  expect(onChange).toHaveBeenLastCalledWith("keyword", "新关键词");
});
