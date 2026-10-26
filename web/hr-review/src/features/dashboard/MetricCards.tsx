import { useQuery } from "@tanstack/react-query";
import { Card, Col, Row, Skeleton, Statistic } from "antd";

import { api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";

const metrics = [
  ["actionable", "可行动候选人", "#1f6f78"],
  ["pending_review", "待审核", "#c27a19"],
  ["recommended_contact", "建议沟通", "#3978a8"],
  ["contacted", "已联系", "#5068a8"],
  ["interviewing", "面试中", "#805ca8"],
  ["rejected", "已淘汰", "#9a5b5b"],
  ["hired", "已录用", "#287a55"],
] as const;

export function MetricCards({ batchId, jobProfileId, reviewerName }: { batchId?: string; jobProfileId?: string; reviewerName?: string }) {
  const summary = useQuery({
    queryKey: queryKeys.dashboard(batchId, jobProfileId, reviewerName),
    queryFn: () => api.dashboard(batchId, jobProfileId, reviewerName),
    refetchInterval: 20_000,
  });
  return (
    <Row gutter={[12, 12]} className="metric-grid">
      {metrics.map(([key, label, color]) => (
        <Col flex="1 1 130px" key={key}>
          <Card size="small" className="metric-card">
            {summary.isLoading ? <Skeleton active paragraph={false} /> : (
              <Statistic title={label} value={summary.data?.[key] ?? 0} valueStyle={{ color }} />
            )}
          </Card>
        </Col>
      ))}
    </Row>
  );
}
