import { useQuery } from "@tanstack/react-query";
import { Alert, Descriptions, Drawer, Skeleton, Space, Statistic, Typography } from "antd";

import { api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";
import { reviewStatusOptions } from "../review-queue/reviewStatuses";

type ReportPreviewDrawerProps = {
  open: boolean;
  onClose: () => void;
  batchId?: string;
  batchLabel?: string;
  defaultsToLatest: boolean;
  batchListSettled: boolean;
  jobProfileId?: string;
};

export function ReportPreviewDrawer({
  open,
  onClose,
  batchId,
  batchLabel,
  defaultsToLatest,
  batchListSettled,
  jobProfileId,
}: ReportPreviewDrawerProps) {
  const report = useQuery({
    queryKey: queryKeys.report(batchId, jobProfileId),
    queryFn: () => api.liveReport(batchId, jobProfileId),
    enabled: open && Boolean(batchId),
    refetchInterval: open ? 20_000 : false,
  });
  return (
    <Drawer title="日报实时预览" width={560} open={open} onClose={onClose}>
      {batchId ? (
        <Alert
          type="info"
          showIcon
          message={defaultsToLatest ? "未选择批次，已自动展示最新完成批次" : "正在展示所选批次"}
          description={`${batchLabel ?? batchId}${defaultsToLatest ? "（全部岗位）" : ""}；数据只读，不会发送日报或修改已发送的飞书卡片。`}
        />
      ) : batchListSettled ? (
        <Alert type="warning" showIcon message="暂无可用于日报预览的已完成批次" />
      ) : null}
      {!batchId ? (!batchListSettled ? <Skeleton active /> : null) : report.isLoading ? <Skeleton active /> : report.isError ? <Alert type="error" message="日报预览加载失败" /> : report.data ? (
        <Space direction="vertical" size={24} className="drawer-section">
          <ReportSection title="AI 原始三态">
            <Statistic title="匹配" value={report.data.ai_relevant} />
            <Statistic title="待复核" value={report.data.ai_needs_review} />
            <Statistic title="不匹配" value={report.data.ai_not_relevant} />
          </ReportSection>
          <ReportSection title="AI 可行动待办">
            <Statistic title="当前待审核" value={report.data.actionable_pending} />
          </ReportSection>
          <div>
            <Typography.Title level={5}>HR 实际招聘进度</Typography.Title>
            <Descriptions column={2} size="small" bordered items={reviewStatusOptions.map((item) => ({ key: item.value, label: item.label, children: report.data.hr_status_counts[item.value] ?? 0 }))} />
          </div>
          <Typography.Text type="secondary">当前批次已反馈候选人：{report.data.feedback_candidates_in_window} · 更新于 {new Date(report.data.generated_at).toLocaleTimeString("zh-CN", { hour12: false })}</Typography.Text>
        </Space>
      ) : null}
    </Drawer>
  );
}

function ReportSection({ title, children }: { title: string; children: React.ReactNode }) {
  return <div><Typography.Title level={5}>{title}</Typography.Title><div className="report-stat-row">{children}</div></div>;
}
