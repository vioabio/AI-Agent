import { Button, Result, Space, Table, Tag, Typography } from "antd";
import type { ColumnsType, TablePaginationConfig } from "antd/es/table";
import type { SorterResult } from "antd/es/table/interface";

import type { CandidateSummary } from "../../shared/api/types";
import {
  communicationStatusLabels,
  matchResultLabels,
  reviewStatusLabels,
} from "../../shared/displayLabels";

const matchColors: Record<string, string> = { relevant: "green", needs_review: "gold", not_relevant: "default" };

interface Props {
  items: CandidateSummary[];
  loading: boolean;
  error?: string;
  page: number;
  pageSize: number;
  total: number;
  sortBy: string;
  sortOrder: "asc" | "desc";
  onChange: (value: { page: number; pageSize: number; sortBy: string; sortOrder: "asc" | "desc" }) => void;
  onOpen: (fingerprint: string) => void;
  onRetry: () => void;
}

export function CandidateTable(props: Props) {
  if (props.error) return <Result status="error" title="候选人列表加载失败" subTitle={props.error} extra={<Button onClick={props.onRetry}>重新加载</Button>} />;
  return (
    <Table<CandidateSummary>
      rowKey="candidate_fingerprint"
      size="small"
      sticky
      loading={props.loading}
      dataSource={props.items}
      columns={columns(props)}
      locale={{ emptyText: <Result status="info" title="当前筛选条件下暂无候选人" subTitle="可以切换页签或调整左侧筛选条件" /> }}
      pagination={{ current: props.page, pageSize: props.pageSize, total: props.total, showSizeChanger: true, showTotal: (value) => `共 ${value} 人` }}
      onChange={(pagination: TablePaginationConfig, _filters, sorter) => {
        const activeSorter = Array.isArray(sorter) ? sorter[0] : sorter;
        props.onChange({
          page: pagination.current ?? 1,
          pageSize: pagination.pageSize ?? 20,
          sortBy: sorterField(activeSorter) ?? props.sortBy,
          sortOrder: activeSorter.order === "ascend" ? "asc" : activeSorter.order === "descend" ? "desc" : props.sortOrder,
        });
      }}
      scroll={{ x: 1280, y: "calc(100vh - 390px)" }}
    />
  );
}

function columns(props: Props): ColumnsType<CandidateSummary> {
  return [
    { title: "候选人", dataIndex: "candidate_name", fixed: "left", width: 120, sorter: true, sortOrder: sortOrder("candidate_name", props), render: (value?: string) => <Typography.Text strong>{value ?? "未命名"}</Typography.Text> },
    { title: "岗位", dataIndex: "job_title", width: 180, ellipsis: true },
    { title: "年龄", dataIndex: "age", width: 70, render: (value?: number) => value ?? "-" },
    { title: "AI 结论", dataIndex: "match_result", width: 105, render: (value?: string) => <Tag color={matchColors[value ?? ""]}>{value ? matchResultLabels[value] ?? value : "未执行"}</Tag> },
    { title: "AI 分数", dataIndex: "match_score", width: 85, sorter: true, sortOrder: sortOrder("match_score", props), render: (value?: number) => value ?? "-" },
    { title: "当前公司/职位", dataIndex: "current_company_title", width: 160, ellipsis: true, render: (value?: string) => value ?? "-" },
    { title: "沟通状态", dataIndex: "communication_status", width: 110, render: communicationLabel },
    { title: "HR 状态", dataIndex: "review_status", width: 110, sorter: true, sortOrder: sortOrder("review_status", props), render: (value: string) => reviewStatusLabels[value] ?? value },
    { title: "审核人", dataIndex: "reviewer_name", width: 100, render: (value?: string) => value ?? "暂无" },
    { title: "更新时间", dataIndex: "updated_at", width: 150, sorter: true, sortOrder: sortOrder("updated_at", props), render: (value?: string | null) => value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "-" },
    { title: "操作", fixed: "right", width: 90, render: (_, item) => <Space><Button type="link" onClick={() => props.onOpen(item.candidate_fingerprint)}>查看</Button></Space> },
  ];
}

function sorterField(sorter: SorterResult<CandidateSummary>): string | undefined {
  return typeof sorter.field === "string" ? sorter.field : undefined;
}

function sortOrder(field: string, props: Props): "ascend" | "descend" | undefined {
  if (props.sortBy !== field) return undefined;
  return props.sortOrder === "asc" ? "ascend" : "descend";
}

function communicationLabel(value: string) {
  return communicationStatusLabels[value] ?? value;
}
