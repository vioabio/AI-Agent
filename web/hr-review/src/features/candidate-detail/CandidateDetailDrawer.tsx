import { LinkOutlined, SafetyCertificateOutlined } from "@ant-design/icons";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Divider, Drawer, Empty, Skeleton, Space, Tag, Timeline, Typography } from "antd";

import { ApiError, api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";
import {
  communicationStatusLabels,
  matchResultLabels,
  reviewStatusLabels,
} from "../../shared/displayLabels";
import { ReviewForm } from "../review-submit/ReviewForm";

export function CandidateDetailDrawer({ fingerprint, onClose, canSubmit }: { fingerprint?: string; onClose: () => void; canSubmit: boolean }) {
  const detail = useQuery({
    queryKey: queryKeys.candidate(fingerprint ?? ""),
    queryFn: () => api.candidate(fingerprint ?? ""),
    enabled: Boolean(fingerprint),
  });
  const forbidden = detail.error instanceof ApiError && detail.error.status === 403;
  return (
    <Drawer title="候选人详情" width={760} open={Boolean(fingerprint)} onClose={onClose} destroyOnClose>
      {detail.isLoading ? <Skeleton active paragraph={{ rows: 10 }} /> : forbidden ? <Alert type="error" message="无权查看该岗位候选人" showIcon /> : detail.isError ? <Alert type="error" message="候选人详情加载失败" description={detail.error.message} showIcon action={<Button onClick={() => { void detail.refetch(); }}>重试</Button>} /> : detail.data ? (
        <Space direction="vertical" size={18} className="drawer-section">
          <div className="candidate-heading"><div><Typography.Title level={3}>{detail.data.summary.candidate_name ?? "未命名候选人"}</Typography.Title><Typography.Text type="secondary">{detail.data.summary.job_title ?? "岗位未知"} · AI {detail.data.summary.match_result ? matchResultLabels[detail.data.summary.match_result] ?? detail.data.summary.match_result : "未执行"} / {detail.data.summary.match_score ?? "-"} 分</Typography.Text></div><Tag color="blue">{reviewStatusLabels[detail.data.summary.review_status] ?? detail.data.summary.review_status}</Tag></div>
          <Descriptions size="small" bordered column={2} items={[
            { key: "communication", label: "沟通状态", children: communicationStatusLabels[detail.data.summary.communication_status] ?? detail.data.summary.communication_status },
            { key: "reviewer", label: "当前负责人", children: detail.data.summary.reviewer_name ?? "未分配" },
            { key: "version", label: "分析版本", children: detail.data.analysis_version ?? "-" },
            { key: "revision", label: "人工版本", children: detail.data.summary.revision },
          ]} />
          <SensitiveLinks sources={detail.data.sources} />
          <Section title="完整简历正文"><div className="resume-text">{detail.data.experience || "暂无完整简历正文"}</div></Section>
          <Section title="AI 判断依据"><Typography.Paragraph>{detail.data.analysis_reason || "暂无分析理由"}</Typography.Paragraph>{detail.data.score_breakdown.map((item) => <Tag key={item}>{item}</Tag>)}<ul>{detail.data.evidence_summary.map((item) => <li key={item}>{item}</li>)}</ul></Section>
          <Section title="来源与定位"><Space direction="vertical">{detail.data.sources.map((source) => <div className="source-card" key={`${source.created_at}-${source.search_keyword}`}><Typography.Text strong>{source.search_keyword}</Typography.Text><Typography.Text type="secondary">搜索包：{source.search_pack_id ?? "-"} · 来源账号：{source.source_account_alias ?? "-"} · 定位状态：{source.locator_quality}</Typography.Text>{source.platform_interaction_note ? <Alert type="warning" message="猎聘互动提示（非候选人姓名）" description={source.platform_interaction_note} /> : null}</div>)}</Space></Section>
          <Divider />
          <Section title="提交审核"><ReviewForm detail={detail.data} canSubmit={canSubmit} /></Section>
          <Section title="完整审核历史">{detail.data.history.length ? <Timeline items={detail.data.history.map((item) => ({ color: "#1f6f78", children: <div><Typography.Text strong>{item.reviewer_name} · {item.review_status}</Typography.Text><Typography.Paragraph>{item.feedback}</Typography.Paragraph><Typography.Text type="secondary">版本 {item.revision} · {new Date(item.created_at).toLocaleString("zh-CN", { hour12: false })}</Typography.Text></div> }))} /> : <Empty description="暂无人工审核历史" image={Empty.PRESENTED_IMAGE_SIMPLE} />}</Section>
        </Space>
      ) : null}
    </Drawer>
  );
}

function SensitiveLinks({ sources }: { sources: { share_link: string | null; locator_quality: string }[] }) {
  const link = sources.find((source) => source.share_link)?.share_link;
  return <Alert icon={<SafetyCertificateOutlined />} type={link ? "success" : "info"} showIcon message={link ? "正式猎聘简历链接已授权" : "暂无可显示的正式链接"} description={link ? <Button href={link} target="_blank" rel="noreferrer" icon={<LinkOutlined />}>打开正式猎聘链接</Button> : "可能尚未获取正式分享链接、已降级为收藏，或当前账号没有敏感链接权限。"} />;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return <section><Typography.Title level={5}>{title}</Typography.Title>{children}</section>;
}
