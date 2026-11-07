const sectionLabels = [
  "候选人信息",
  "基本信息",
  "求职意向",
  "个人优势",
  "工作内容",
  "工作经历",
  "工作职责",
  "工作业绩",
  "项目经历",
  "项目描述",
  "项目职责",
  "教育经历",
  "专业技能",
  "技能特长",
  "证书",
  "培训经历",
  "语言能力",
  "自我评价",
] as const;

export type ResumeBlock =
  | { kind: "section"; label: string; text: string }
  | { kind: "numbered"; marker: string; text: string }
  | { kind: "paragraph"; text: string };

const sectionSource = sectionLabels.join("|");
const sectionBoundary = new RegExp(`\\s*(?=(${sectionSource})\\s*[:：])`, "g");
const sectionLine = new RegExp(`^(${sectionSource})\\s*([:：])\\s*(.*)$`);

export function parseResumeText(value: string): ResumeBlock[] {
  const normalized = value.replace(/\r\n?/g, "\n").trim();
  const shouldEnhanceSingleLine = normalized.length >= 120 && !normalized.includes("\n");
  const structured = shouldEnhanceSingleLine
    ? normalized
      .replace(sectionBoundary, "\n")
      .replace(/([；;])\s*/g, "$1\n")
      .replace(/\s+(?=((?:[1-9]|1\d)[.、．])\s+)/g, "\n")
      .trim()
    : normalized;

  if (!structured) return [];
  return structured
    .split(/\n+/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line): ResumeBlock => {
      const section = sectionLine.exec(line);
      if (section) return { kind: "section", label: `${section[1]}${section[2]}`, text: section[3] };
      const numbered = line.match(/^((?:[1-9]|1\d)[.、．])\s*(.*)$/);
      if (numbered) return { kind: "numbered", marker: numbered[1], text: numbered[2] };
      return { kind: "paragraph", text: line };
    });
}
