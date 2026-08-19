import {
  BarChartOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Layout, Space, Tabs, Typography } from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";

import { CandidateDetailDrawer } from "../features/candidate-detail/CandidateDetailDrawer";
import { MetricCards } from "../features/dashboard/MetricCards";
import { ReportPreviewDrawer } from "../features/report-preview/ReportPreviewDrawer";
import { CandidateTable } from "../features/review-queue/CandidateTable";
import { QueueFilters, type FilterValues } from "../features/review-queue/QueueFilters";
import { api, type QueueParams } from "../shared/api/client";
import { queryKeys } from "../shared/api/queryKeys";
import type { User } from "../shared/api/types";

const allJobs = ["daren_live_bd_v1", "finance_shared_management_v1"];
const scopeTabs = [
  { key: "pending", label: "待审核" },
  { key: "progress", label: "推进中" }, { key: "closed", label: "已结束" },
  { key: "all", label: "全部候选人" },
];

export function WorkbenchPage({ user }: { user: User }) {
  const [search, setSearch] = useSearchParams();
  const searchRef = useRef(search);
  const [filtersCollapsed, setFiltersCollapsed] = useState(false);
  const [selected, setSelected] = useState<string>();
  const [reportOpen, setReportOpen] = useState(false);
  const queryClient = useQueryClient();
  const params = useMemo(() => queueParams(search), [search]);
  const batches = useQuery({
    queryKey: queryKeys.batches,
    queryFn: api.batches,
    staleTime: 60_000,
  });
  const queue = useQuery({
    queryKey: queryKeys.queue(search.toString()),
    queryFn: () => api.queue(params),
    placeholderData: keepPreviousData,
  });
  const selectedReportBatch = params.batchId
    ? batches.data?.items.find((batch) => batch.batch_id === params.batchId)
    : batches.data?.items[0];
  const logout = useMutation({ mutationFn: api.logout, onSuccess: () => { queryClient.clear(); window.location.assign("/login"); } });
  const allowedJobs = ["admin", "recruiting_lead"].includes(user.role) ? allJobs : [...user.allowed_job_profile_ids];
  useEffect(() => { searchRef.current = search; }, [search]);
  const filters: FilterValues = {
    batch: params.batchId,
    job: params.jobProfileId,
    match: params.matchResult,
    status: params.reviewStatus,
    minScore: params.minScore,
    maxScore: params.maxScore,
    communication: params.communicationStatus,
    reviewerId: params.reviewerId,
    keyword: params.keyword,
    hasLink: params.hasShareLink === undefined ? undefined : String(params.hasShareLink),
  };
  const commitSearch = (next: URLSearchParams) => {
    searchRef.current = next;
    setSearch(next);
  };
  const patchSearch = (values: Record<string, string | number | undefined>) => {
    const next = new URLSearchParams(searchRef.current);
    Object.entries(values).forEach(([key, value]) => value === undefined || value === "" ? next.delete(key) : next.set(key, String(value)));
    next.set("page", "1");
    commitSearch(next);
  };
  return (
    <Layout className="workbench-shell">
      <Layout.Header className="topbar">
        <Space size={12}>
          <Button
            type="text"
            className="sidebar-toggle"
            aria-label={filtersCollapsed ? "展开筛选栏" : "收起筛选栏"}
            aria-expanded={!filtersCollapsed}
            icon={filtersCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setFiltersCollapsed((value) => !value)}
          />
          <div className="compact-logo"><TeamOutlined /></div>
          <div><Typography.Title level={4}>候选人审核与招聘进度工作台</Typography.Title><Typography.Text>AI 初筛事实与 HR 推进状态分离展示</Typography.Text></div>
        </Space>
        <Space size={12}>
          <Button ghost icon={<BarChartOutlined />} onClick={() => setReportOpen(true)}>日报实时预览</Button>
          <div className="current-user"><Typography.Text>{user.display_name}</Typography.Text><small>{user.role}</small></div>
          <Button type="text" danger icon={<LogoutOutlined />} loading={logout.isPending} onClick={() => logout.mutate()}>退出</Button>
        </Space>
      </Layout.Header>
      <Layout>
        <Layout.Sider
          width={236}
          collapsedWidth={0}
          collapsed={filtersCollapsed}
          collapsible
          trigger={null}
          theme="light"
          className="sidebar"
          aria-hidden={filtersCollapsed}
        >
          <QueueFilters
            values={filters}
            batches={(batches.data?.items ?? []).map((batch) => ({ value: batch.batch_id, label: batch.label }))}
            allowedJobs={allowedJobs}
            onChange={(key, value) => patchSearch({ [filterSearchKeys[key]]: value })}
            onReset={() => commitSearch(new URLSearchParams({ scope: params.scope }))}
          />
        </Layout.Sider>
        <Layout.Content className="workspace">
          <MetricCards batchId={params.batchId} jobProfileId={params.jobProfileId} reviewerId={params.reviewerId} />
          <div className="queue-card">
            <Tabs activeKey={params.scope} items={scopeTabs} onChange={(scope) => patchSearch({ scope })} />
            <CandidateTable
              items={queue.data?.items ?? []}
              loading={queue.isLoading}
              error={queue.error?.message}
              page={params.page}
              pageSize={params.pageSize}
              total={queue.data?.total ?? 0}
              sortBy={params.sortBy}
              sortOrder={params.sortOrder}
              onChange={({ page, pageSize, sortBy, sortOrder }) => {
                const next = new URLSearchParams(searchRef.current);
                next.set("page", String(page));
                next.set("page_size", String(pageSize));
                next.set("sort", sortBy);
                next.set("order", sortOrder);
                commitSearch(next);
              }}
              onOpen={setSelected}
              onRetry={() => { void queue.refetch(); }}
            />
          </div>
        </Layout.Content>
      </Layout>
      <CandidateDetailDrawer fingerprint={selected} onClose={() => setSelected(undefined)} canSubmit={user.role !== "read_only"} />
      <ReportPreviewDrawer
        open={reportOpen}
        onClose={() => setReportOpen(false)}
        batchId={selectedReportBatch?.batch_id}
        batchLabel={selectedReportBatch?.label}
        defaultsToLatest={!params.batchId}
        batchListSettled={batches.isFetched}
        jobProfileId={params.batchId ? params.jobProfileId : undefined}
      />
    </Layout>
  );
}

function queueParams(search: URLSearchParams): QueueParams {
  const hasLink = search.get("link");
  return {
    batchId: search.get("batch") ?? undefined,
    jobProfileId: search.get("job") ?? undefined,
    matchResult: search.get("match") ?? undefined, reviewStatus: search.get("status") ?? undefined,
    minScore: optionalNumber(search.get("min_score")), maxScore: optionalNumber(search.get("max_score")),
    reviewerId: optionalNumber(search.get("reviewer")), communicationStatus: search.get("communication") ?? undefined,
    keyword: search.get("keyword") ?? undefined, hasShareLink: hasLink ? hasLink === "true" : undefined,
    scope: normalizeScope(search.get("scope")), page: Number(search.get("page") ?? 1),
    pageSize: Number(search.get("page_size") ?? 20), sortBy: search.get("sort") ?? "updated_at",
    sortOrder: search.get("order") === "asc" ? "asc" : "desc",
  };
}

function normalizeScope(value: string | null): string {
  return !value || value === "mine" ? "pending" : value;
}

function optionalNumber(value: string | null): number | undefined {
  if (value === null || value === "") return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

const filterSearchKeys: Record<keyof FilterValues, string> = {
  batch: "batch",
  job: "job",
  match: "match",
  status: "status",
  minScore: "min_score",
  maxScore: "max_score",
  communication: "communication",
  reviewerId: "reviewer",
  keyword: "keyword",
  hasLink: "link",
};
