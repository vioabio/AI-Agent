import { LinkOutlined, SafetyCertificateOutlined } from "@ant-design/icons";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Divider, Drawer, Empty, Skeleton, Space, Tag, Timeline, Typography } from "antd";
import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { createPortal } from "react-dom";

import { ApiError, api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";
import {
  communicationStatusLabels,
  matchResultLabels,
  reviewStatusLabels,
} from "../../shared/displayLabels";
import { ReviewForm } from "../review-submit/ReviewForm";
import { AIAnalysisPanel } from "./AIAnalysisPanel";
import { ResumeDocument } from "./ResumeDocument";

const DEFAULT_DRAWER_WIDTH = 760;
const MIN_DRAWER_WIDTH = 620;
const DRAWER_WIDTH_STEP = 48;

interface ResizeSession {
  pointerId: number;
  startX: number;
  startWidth: number;
}

export function CandidateDetailDrawer({ fingerprint, onClose, canSubmit }: { fingerprint?: string; onClose: () => void; canSubmit: boolean }) {
  const [drawerWidth, setDrawerWidth] = useState(() => clampDrawerWidth(DEFAULT_DRAWER_WIDTH));
  const resizeSession = useRef<ResizeSession>();
  const detail = useQuery({
    queryKey: queryKeys.candidate(fingerprint ?? ""),
    queryFn: () => api.candidate(fingerprint ?? ""),
    enabled: Boolean(fingerprint),
  });
  useEffect(() => {
    const clampToViewport = () => setDrawerWidth((width) => clampDrawerWidth(width));
    window.addEventListener("resize", clampToViewport);
    return () => window.removeEventListener("resize", clampToViewport);
  }, []);

  const beginResize = (event: PointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    resizeSession.current = { pointerId: event.pointerId, startX: event.clientX, startWidth: drawerWidth };
  };
  const resize = (event: PointerEvent<HTMLDivElement>) => {
    const session = resizeSession.current;
    if (!session || event.pointerId !== session.pointerId) return;
    setDrawerWidth(clampDrawerWidth(session.startWidth + session.startX - event.clientX));
  };
  const finishResize = (event: PointerEvent<HTMLDivElement>) => {
    if (resizeSession.current?.pointerId !== event.pointerId) return;
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    resizeSession.current = undefined;
  };
  const resizeWithKeyboard = (event: KeyboardEvent<HTMLDivElement>) => {
    const widthByKey: Record<string, number> = {
      ArrowLeft: drawerWidth + DRAWER_WIDTH_STEP,
      ArrowRight: drawerWidth - DRAWER_WIDTH_STEP,
      Home: MIN_DRAWER_WIDTH,
      End: maxDrawerWidth(),
    };
    const nextWidth = widthByKey[event.key];
    if (nextWidth === undefined) return;
    event.preventDefault();
    setDrawerWidth(clampDrawerWidth(nextWidth));
  };
  const forbidden = detail.error instanceof ApiError && detail.error.status === 403;
  return (
    <>
      {fingerprint ? createPortal(
        <div
          className="drawer-resize-handle"
          style={{ right: drawerWidth - 6 }}
          role="separator"
          aria-label="调整候选人详情宽度"
          aria-orientation="vertical"
          aria-valuemin={MIN_DRAWER_WIDTH}
          aria-valuemax={maxDrawerWidth()}
          aria-valuenow={drawerWidth}
          aria-valuetext={`${drawerWidth} 像素`}
          tabIndex={0}
          title="拖动调整详情宽度，或使用左右方向键微调"
          onPointerDown={beginResize}
          onPointerMove={resize}
          onPointerUp={finishResize}
          onPointerCancel={finishResize}
          onLostPointerCapture={() => { resizeSession.current = undefined; }}
          onKeyDown={resizeWithKeyboard}
        />,
        document.body,
      ) : null}
      <Drawer title="候选人详情" width={drawerWidth} open={Boolean(fingerprint)} onClose={onClose} destroyOnHidden>
      {detail.isLoading ? <Skeleton active paragraph={{ rows: 10 }} /> : forbidden ? <Alert type="error" message="无权查看该岗位候选人" showIcon /> : detail.isError ? <Alert type="error" message="候选人详情加载失败" description={detail.error.message} showIcon action={<Button onClick={() => { void detail.refetch(); }}>重试</Button>} /> : detail.data ? (
        <Space direction="vertical" size={18} className="drawer-section">
          <div className="candidate-heading"><div><Typography.Title level={3}>{detail.data.summary.candidate_name ?? "未命名候选人"}</Typography.Title><Typography.Text type="secondary">{detail.data.summary.job_title ?? "岗位未知"} · AI {detail.data.summary.match_result ? matchResultLabels[detail.data.summary.match_result] ?? detail.data.summary.match_result : "未执行"} / {detail.data.summary.match_score ?? "-"} 分</Typography.Text></div><Tag color="blue">{reviewStatusLabels[detail.data.summary.review_status] ?? detail.data.summary.review_status}</Tag></div>
          <Descriptions size="small" bordered column={2} items={[
            { key: "communication", label: "沟通状态", children: communicationStatusLabels[detail.data.summary.communication_status] ?? detail.data.summary.communication_status },
            { key: "reviewer", label: "当前审核人", children: detail.data.summary.reviewer_name ?? "暂无" },
            { key: "version", label: "分析版本", children: detail.data.analysis_version ?? "-" },
            { key: "revision", label: "人工版本", children: detail.data.summary.revision },
          ]} />
          <SensitiveLinks sources={detail.data.sources} />
          <Section title="完整简历正文"><ResumeDocument value={detail.data.experience} /></Section>
          <Section title="AI 判断依据"><AIAnalysisPanel analysisReason={detail.data.analysis_reason} scoreBreakdown={detail.data.score_breakdown} evidenceSummary={detail.data.evidence_summary} /></Section>
          <Section title="来源与定位"><Space direction="vertical">{detail.data.sources.map((source) => <div className="source-card" key={`${source.created_at}-${source.search_keyword}`}><Typography.Text strong>{source.search_keyword}</Typography.Text><Typography.Text type="secondary">搜索包：{source.search_pack_id ?? "-"} · 来源账号：{source.source_account_alias ?? "-"} · 定位状态：{source.locator_quality}</Typography.Text>{source.platform_interaction_note ? <Alert type="warning" message="猎聘互动提示（非候选人姓名）" description={source.platform_interaction_note} /> : null}</div>)}</Space></Section>
          <Divider />
          <Section title="提交审核"><ReviewForm detail={detail.data} canSubmit={canSubmit} /></Section>
          <Section title="完整审核历史">{detail.data.history.length ? <Timeline items={detail.data.history.map((item) => ({ color: "#1f6f78", children: <div><Typography.Text strong>{item.reviewer_name} · {item.review_status}</Typography.Text><Typography.Paragraph>{item.feedback}</Typography.Paragraph><Typography.Text type="secondary">版本 {item.revision} · {new Date(item.created_at).toLocaleString("zh-CN", { hour12: false })}</Typography.Text></div> }))} /> : <Empty description="暂无人工审核历史" image={Empty.PRESENTED_IMAGE_SIMPLE} />}</Section>
        </Space>
      ) : null}
      </Drawer>
    </>
  );
}

function maxDrawerWidth(): number {
  return Math.max(MIN_DRAWER_WIDTH, window.innerWidth - 48);
}

function clampDrawerWidth(width: number): number {
  return Math.min(maxDrawerWidth(), Math.max(MIN_DRAWER_WIDTH, width));
}

function SensitiveLinks({ sources }: { sources: { share_link: string | null; locator_quality: string }[] }) {
  const link = sources.find((source) => source.share_link)?.share_link;
  return <Alert icon={<SafetyCertificateOutlined />} type={link ? "success" : "info"} showIcon message={link ? "正式猎聘简历链接已授权" : "暂无可显示的正式链接"} description={link ? <Button href={link} target="_blank" rel="noreferrer" icon={<LinkOutlined />}>打开正式猎聘链接</Button> : "可能尚未获取正式分享链接、已降级为收藏，或当前账号没有敏感链接权限。"} />;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return <section><Typography.Title level={5}>{title}</Typography.Title>{children}</section>;
}
