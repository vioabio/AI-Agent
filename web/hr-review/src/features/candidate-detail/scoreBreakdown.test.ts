import { expect, test } from "vitest";

import { parseScoreBreakdown } from "./scoreBreakdown";

test("maps the three stored score keys to report-style Chinese labels and order", () => {
  expect(parseScoreBreakdown([
    "beauty_continuity: 14",
    "role_evidence: 59",
    "industry_depth: 23",
  ])).toEqual([
    { id: "industry_depth-2", label: "行业深度", value: "23", order: 0 },
    { id: "beauty_continuity-0", label: "行业连续性", value: "14", order: 1 },
    { id: "role_evidence-1", label: "岗位证据", value: "59", order: 2 },
  ]);
});

test("does not expose an unknown English storage key", () => {
  expect(parseScoreBreakdown(["legacy_internal_field: 8", "业务能力：48"]).map(({ label, value }) => ({ label, value }))).toEqual([
    { label: "其他评分", value: "8" },
    { label: "业务能力", value: "48" },
  ]);
});
