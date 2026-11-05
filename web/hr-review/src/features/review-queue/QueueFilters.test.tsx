import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

import { renderApp } from "../../test/render";
import { QueueFilters } from "./QueueFilters";

test("offers the authorized AI and FDE job labels and submits the exact profile id", async () => {
  const onChange = vi.fn();
  renderApp(<QueueFilters values={{}} batches={[]} allowedJobs={["ai_development_engineer_v1", "fde_ai_deployment_engineer_v1"]}
    onChange={onChange} onReset={vi.fn()} />);
  await userEvent.click(screen.getByRole("combobox", { name: "岗位" }));
  await waitFor(() => { expect(screen.getByText("AI 开发工程师")).toBeVisible(); });
  expect(screen.queryByText("达人营销与达播负责人")).not.toBeInTheDocument();
  await userEvent.click(screen.getByText("AI 前沿部署工程师（FDE）"));
  expect(onChange).toHaveBeenLastCalledWith("job", "fde_ai_deployment_engineer_v1");
});

test("emits reviewer and communication filters without score controls", async () => {
  const onChange = vi.fn();
  renderApp(<QueueFilters values={{}} batches={[{ value: "batch-1", label: "2026-08-18 17:00 完成" }]} allowedJobs={["daren_live_bd_v1"]} onChange={onChange} onReset={vi.fn()} />);

  await userEvent.click(screen.getByRole("combobox", { name: "搜索批次" }));
  await userEvent.click(screen.getByText("2026-08-18 17:00 完成"));
  expect(onChange).toHaveBeenLastCalledWith("batch", "batch-1");

  await userEvent.click(screen.getByRole("combobox", { name: "AI 结论" }));
  expect(screen.getByText("匹配")).toBeInTheDocument();
  expect(screen.getByText("待复核")).toBeInTheDocument();
  expect(screen.queryByText("不匹配")).not.toBeInTheDocument();

  expect(screen.queryByText("AI 分数范围")).not.toBeInTheDocument();
  expect(screen.queryByLabelText("AI 最低分")).not.toBeInTheDocument();
  expect(screen.queryByLabelText("AI 最高分")).not.toBeInTheDocument();

  await userEvent.type(screen.getByLabelText("审核人姓名"), "招聘负责人{Enter}");
  expect(onChange).toHaveBeenLastCalledWith("reviewerName", "招聘负责人");

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
