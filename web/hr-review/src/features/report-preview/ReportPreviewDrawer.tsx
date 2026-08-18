import { useQuery } from "@tanstack/react-query";
import { Alert, Descriptions, Drawer, Skeleton, Space, Statistic, Typography } from "antd";

import { api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";
import { reviewStatusOptions } from "../review-queue/reviewStatuses";

export function ReportPreviewDrawer({ open, onClose, batchId, jobProfileId }: { open: boolean; onClose: () => void; batchId?: string; jobProfileId?: string }) {
  const report = useQuery({
    queryKey: queryKeys.report(batchId, jobProfileId),
    queryFn: () => api.liveReport(batchId, jobProfileId),
    enabled: open,
    refetchInterval: open ? 20_000 : false,
  });
  return (
    <Drawer title="日报实时预览" width={560} open={open} onClose={onClose}>
      <Alert type="info" showIcon message="实时预览直接读取 MySQL 最新状态；不会发送日报，也不会修改已发送的飞书卡片。" />
      {report.isLoading ? <Skeleton active /> : report.isError ? <Alert type="error" message="日报预览加载失败" /> : report.data ? (
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
          <Typography.Text type="secondary">当前窗口新增反馈人数：{report.data.feedback_candidates_in_window} · 更新于 {new Date(report.data.generated_at).toLocaleTimeString("zh-CN", { hour12: false })}</Typography.Text>
        </Space>
      ) : null}
    </Drawer>
  );
}

function ReportSection({ title, children }: { title: string; children: React.ReactNode }) {
  return <div><Typography.Title level={5}>{title}</Typography.Title><div className="report-stat-row">{children}</div></div>;
}
