const scoreLabels: Record<string, string> = {
  industry_depth: "行业深度",
  beauty_continuity: "行业连续性",
  role_evidence: "岗位证据",
};

const scoreOrder = ["industry_depth", "beauty_continuity", "role_evidence"] as const;
const englishFieldName = /^[a-z][a-z0-9_]*$/i;

export interface DisplayScore {
  id: string;
  label: string;
  value: string;
  order: number;
}

export function parseScoreBreakdown(items: string[]): DisplayScore[] {
  return items
    .map((item, index): DisplayScore | null => {
      const match = item.match(/^\s*([^:：]+?)\s*[:：]\s*(.*?)\s*$/);
      if (!match || !match[2]) return null;
      const key = match[1].trim();
      const knownOrder = scoreOrder.indexOf(key as (typeof scoreOrder)[number]);
      return {
        id: `${key}-${index}`,
        label: scoreLabels[key] ?? (englishFieldName.test(key) ? "其他评分" : key),
        value: match[2],
        order: knownOrder < 0 ? scoreOrder.length + index : knownOrder,
      };
    })
    .filter((item): item is DisplayScore => item !== null)
    .sort((left, right) => left.order - right.order);
}
