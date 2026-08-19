import { Button, Divider, Input, InputNumber, Select, Space, Typography } from "antd";
import { useEffect, useState } from "react";

import { reviewStatusOptions } from "./reviewStatuses";

export interface FilterValues {
  batch?: string;
  job?: string;
  match?: string;
  status?: string;
  minScore?: number;
  maxScore?: number;
  communication?: string;
  reviewerName?: string;
  keyword?: string;
  hasLink?: string;
}

interface Props {
  values: FilterValues;
  batches: readonly { value: string; label: string }[];
  allowedJobs: readonly string[];
  onChange: (key: keyof FilterValues, value: string | number | undefined) => void;
  onReset: () => void;
}

const jobLabels: Record<string, string> = {
  daren_live_bd_v1: "达人营销与达播负责人",
  finance_shared_management_v1: "财务共享与管理经理",
};

export function QueueFilters({ values, batches, allowedJobs, onChange, onReset }: Props) {
  const set = <Key extends keyof FilterValues>(key: Key, value: FilterValues[Key]) => onChange(key, value);
  const [keywordDraft, setKeywordDraft] = useState(values.keyword ?? "");
  const [reviewerDraft, setReviewerDraft] = useState(values.reviewerName ?? "");
  useEffect(() => { setKeywordDraft(values.keyword ?? ""); }, [values.keyword]);
  useEffect(() => { setReviewerDraft(values.reviewerName ?? ""); }, [values.reviewerName]);
  return (
    <aside className="filter-panel" aria-label="候选人筛选">
      <Space direction="vertical" size={14} className="filter-stack">
        <div>
          <Typography.Text className="filter-label">搜索候选人</Typography.Text>
          <Input.Search
            placeholder="姓名、公司、职位或关键词"
            allowClear
            value={keywordDraft}
            onSearch={(value) => set("keyword", value || undefined)}
            onChange={(event) => {
              const value = event.target.value;
              setKeywordDraft(value);
              if (!value) set("keyword", undefined);
            }}
          />
        </div>
        <FilterSelect
          label="搜索批次"
          value={values.batch}
          options={[...batches]}
          onChange={(value) => set("batch", value)}
        />
        <FilterSelect
          label="岗位"
          value={values.job}
          options={allowedJobs.map((value) => ({ value, label: jobLabels[value] ?? value }))}
          onChange={(value) => set("job", value)}
        />
        <FilterSelect
          label="AI 结论"
          value={values.match}
          options={[
            { value: "relevant", label: "匹配" },
            { value: "needs_review", label: "待复核" },
          ]}
          onChange={(value) => set("match", value)}
        />
        <div>
          <Typography.Text className="filter-label">AI 分数范围</Typography.Text>
          <Space.Compact block>
            <InputNumber
              aria-label="AI 最低分"
              min={0}
              max={100}
              placeholder="最低"
              value={values.minScore}
              onChange={(value) => set("minScore", value ?? undefined)}
            />
            <Input className="score-separator" value="至" readOnly tabIndex={-1} aria-hidden="true" />
            <InputNumber
              aria-label="AI 最高分"
              min={0}
              max={100}
              placeholder="最高"
              value={values.maxScore}
              onChange={(value) => set("maxScore", value ?? undefined)}
            />
          </Space.Compact>
        </div>
        <FilterSelect
          label="HR 状态"
          value={values.status}
          options={reviewStatusOptions}
          onChange={(value) => set("status", value)}
        />
        <FilterSelect
          label="沟通状态"
          value={values.communication}
          options={[
            { value: "not_sent", label: "未发送" },
            { value: "sent", label: "已发送" },
            { value: "failed", label: "发送失败" },
            { value: "previously_contacted", label: "历史已沟通" },
          ]}
          onChange={(value) => set("communication", value)}
        />
        <div>
          <Typography.Text className="filter-label">审核人</Typography.Text>
          <Input.Search
            aria-label="审核人姓名"
            allowClear
            placeholder="输入审核人姓名"
            value={reviewerDraft}
            onSearch={(value) => set("reviewerName", value.trim() || undefined)}
            onChange={(event) => {
              const value = event.target.value;
              setReviewerDraft(value);
              if (!value) set("reviewerName", undefined);
            }}
          />
        </div>
        <FilterSelect
          label="正式简历链接"
          value={values.hasLink}
          options={[{ value: "true", label: "有正式链接" }, { value: "false", label: "暂无正式链接" }]}
          onChange={(value) => set("hasLink", value)}
        />
        <Divider />
        <Button onClick={onReset} block>重置筛选</Button>
      </Space>
    </aside>
  );
}

function FilterSelect({ label, value, options, onChange }: {
  label: string;
  value?: string;
  options: { value: string; label: string }[];
  onChange: (value: string | undefined) => void;
}) {
  return <div><Typography.Text className="filter-label">{label}</Typography.Text><Select aria-label={label} allowClear value={value} options={options} onChange={onChange} /></div>;
}
