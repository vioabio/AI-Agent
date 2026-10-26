import { expect, test } from "vitest";

import { parseResumeText } from "./resumeParser";

test("segments a long single-line resume without treating dates as list items", () => {
  const source = "候选人信息：年龄：32岁 工作内容：工作10年以上·5段工作经历；众安在线财产保险股份有限公司；2025.04-至今(1年4个月)；统筹总账核算与财务数字化升级： 1. 全品类往来账款专项治理，完成95%以上逾期款项清理。 2. 应收保费精细化账龄管控。 3. 保险新旧准则月结全流程管控。 4. 标准化业财核对体系搭建。";
  const blocks = parseResumeText(source);

  expect(blocks).toContainEqual({ kind: "section", label: "候选人信息：", text: "年龄：32岁" });
  expect(blocks).toContainEqual({ kind: "section", label: "工作内容：", text: "工作10年以上·5段工作经历；" });
  expect(blocks.filter((block) => block.kind === "numbered").map((block) => block.marker)).toEqual(["1.", "2.", "3.", "4."]);
  expect(blocks.some((block) => block.kind === "numbered" && block.marker === "2025.")).toBe(false);
  expect(blocks.some((block) => block.kind === "paragraph" && block.text.includes("2025.04-至今(1年4个月)"))).toBe(true);
});

test("respects an existing multiline layout", () => {
  expect(parseResumeText("第一行\n第二行；仍在同一行")).toEqual([
    { kind: "paragraph", text: "第一行" },
    { kind: "paragraph", text: "第二行；仍在同一行" },
  ]);
});
