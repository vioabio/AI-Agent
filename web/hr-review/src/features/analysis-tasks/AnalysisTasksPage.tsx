import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Drawer, Empty, Space, Table, Tag, Typography } from "antd";
import { useState } from "react";
import { Link } from "react-router";

import { ApiError, api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";
import type { AnalysisTask, AnalysisTaskDetail, EvidenceReference } from "../../shared/api/types";
import { matchResultLabels } from "../../shared/displayLabels";
import { SnapshotDocument } from "../candidate-detail/SnapshotDocument";
import { verifiedReferences } from "../candidate-detail/evidenceReferences";
import { ExecutionDetails } from "./ExecutionDetails";
import { jobLabels as jobs } from "../../shared/jobProfiles";

const statuses: Record<string, string> = {
  pending: "等待分析", running: "分析中", retry_wait: "等待重试", succeeded: "分析成功",
  failed: "分析失败", cancelled: "已取消", superseded: "已有新版本",
};
const errors: Record<string, string> = { timeout: "调用超时", rate_limited: "限流等待",
  local_rate_limit: "等待调度额度", invalid_output: "输出未通过校验", budget_exhausted: "预算不足",
  deadline_exceeded: "任务已超时", lease_expired: "等待任务恢复", worker_error: "执行暂时失败" };

export function AnalysisTasksPage() {
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<string>();
  const client = useQueryClient();
  const list = useQuery({ queryKey: queryKeys.analysisTasks(page), queryFn: () => api.analysisTasks(page), retry: false });
  const detail = useQuery({ queryKey: queryKeys.analysisTask(selected ?? ""), queryFn: () => api.analysisTask(selected ?? ""),
    enabled: Boolean(selected), retry: false });
  const retry = useMutation({ mutationFn: (task: AnalysisTask) => api.retryAnalysisTask(task.job_id, task.lease_token),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: ["analysis-tasks"] });
      await client.invalidateQueries({ queryKey: ["analysis-task"] }); },
    onError: () => { void client.invalidateQueries({ queryKey: ["analysis-tasks"] }); },
  });
  const refresh = () => { void list.refetch(); if (selected) void detail.refetch(); };
  return <main className="shadow-workspace">
    <Space className="shadow-toolbar" wrap>
      <Typography.Title level={3}>影子分析任务</Typography.Title>
      <Link to="/">返回审核工作台</Link>
      <Button onClick={refresh} loading={list.isFetching}>刷新任务</Button>
    </Space>
    <Alert type="info" showIcon message="合成数据 · 预计算结果 · 未调用真实模型"
      description="此入口独立展示采集与分析状态，不修改正式评分、人工审核或沟通状态。提前安排重试只调整调度时间，演示 Worker 不会自动运行。" />
    {retry.isSuccess ? <Alert type="success" message="已更新重试调度时间，尝试次数和预算保持不变。" /> : null}
    {retry.isError ? <Alert type="error" message={retry.error instanceof ApiError && retry.error.status === 409
      ? "任务状态已变化，请刷新后重试。" : "重试调度失败，请刷新任务或联系负责人。"} /> : null}
    {list.isError ? <Alert type="error" message="任务列表暂时不可用，请重试。" /> : null}
    {list.data?.enabled === false ? <Alert type="info" message="此环境尚未启用影子分析任务。" /> :
      <Table<AnalysisTask> rowKey="job_id" loading={list.isLoading} dataSource={list.data?.items ?? []}
        scroll={{ x: 980 }} pagination={{ current: page, pageSize: 20, total: list.data?.total ?? 0,
          showSizeChanger: false, onChange: setPage }} locale={{ emptyText: <Empty description="暂无授权范围内的影子任务" /> }}
        columns={[
          { title: "虚构候选人", dataIndex: "candidate_name", width: 230 },
          { title: "岗位", dataIndex: "job_profile_id", render: (value: string) => jobs[value] ?? value },
          { title: "采集状态", dataIndex: "collection_completed", render: (value: boolean) => <Tag>{value ? "采集已结束" : "采集中"}</Tag> },
          { title: "分析状态", dataIndex: "status", render: (value: string) => <Tag color={value === "succeeded" ? "green" : "default"}>{statuses[value]}</Tag> },
          { title: "尝试", render: (_, row) => `${row.attempts}/${row.max_attempts}` },
          { title: "说明", dataIndex: "last_error_code", render: (value: string | null) => value ? errors[value] ?? "需要核验" : "—" },
          { title: "操作", render: (_, row) => <Space>
            <Button size="small" aria-label={`查看任务：${row.candidate_name}`} onClick={() => { setSelected(row.job_id); retry.reset(); }}>查看</Button>
            {row.can_retry ? <Button size="small" loading={retry.isPending} aria-label={`安排重试：${row.candidate_name}`}
              onClick={() => retry.mutate(row)}>提前安排重试</Button> : null}
          </Space> },
        ]} />}
    <Drawer title="影子任务与分析快照" width={760} open={Boolean(selected)} onClose={() => setSelected(undefined)} destroyOnClose>
      {detail.isLoading ? <Typography.Paragraph>正在加载任务…</Typography.Paragraph> :
        detail.isError ? <Alert type="warning" message="任务版本已变化、无权限或暂时不可用，请刷新列表。" /> :
          selected && detail.data?.task.job_id === selected ? <TaskDetail key={selected} detail={detail.data} /> : null}
    </Drawer>
  </main>;
}

function TaskDetail({ detail }: { detail: AnalysisTaskDetail }) {
  const [selected, setSelected] = useState<EvidenceReference>();
  const result = detail.result_status === "verified" ? detail.result : null;
  const refs = result ? verifiedReferences(detail.snapshot, detail.evidence_refs, result.evidence_summary) : [];
  const reference = refs.find((item) => item.evidence_id === selected?.evidence_id);
  return <div className="shadow-detail">
    <Alert type="info" message="独立影子记录，不作为自动沟通或淘汰依据。" />
    <Descriptions column={1} size="small" items={[
      { key: "name", label: "虚构候选人", children: detail.task.candidate_name },
      { key: "job", label: "岗位", children: jobs[detail.task.job_profile_id] ?? detail.task.job_profile_id },
      { key: "state", label: "分析状态", children: statuses[detail.task.status] },
      { key: "collection", label: "采集状态", children: detail.task.collection_completed ? "采集已结束" : "采集中" },
    ]} />
    <ExecutionDetails execution={detail.execution} />
    {detail.result_status === "invalid" ? <Alert type="warning" message="当前结果校验未通过，请核验数据。" /> : null}
    {result ? <section>
      <Typography.Title level={5}>预计算影子结果</Typography.Title>
      <Typography.Paragraph>{matchResultLabels[result.match_result]} · {result.match_score} 分</Typography.Paragraph>
      <Typography.Paragraph>{result.analysis_reason}</Typography.Paragraph>
      <ul>{result.evidence_summary.map((quote, index) => {
        const ref = refs.find((item) => item.summary_index === index);
        return <li key={index}>{quote}{ref ? <Button type="link" aria-label={`查看影子证据${index + 1}原文`}
          onClick={() => setSelected(ref)}>查看原文</Button> : null}</li>;
      })}</ul>
    </section> : <Typography.Paragraph>暂无校验通过的分析结果。</Typography.Paragraph>}
    {detail.snapshot ? <section>
      <Typography.Title level={5}>本任务保存的简历快照</Typography.Title>
      {reference ? <SnapshotDocument snapshot={detail.snapshot} reference={reference} /> :
        <pre className="shadow-original" aria-label="影子任务原文">{detail.snapshot.raw_text}</pre>}
    </section> : null}
  </div>;
}
