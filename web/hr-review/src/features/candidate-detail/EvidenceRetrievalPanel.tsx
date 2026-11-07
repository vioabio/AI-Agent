import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Select, Space, Typography } from "antd";
import { useState } from "react";

import { api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";
import type { AnalysisSnapshot, EvidenceReference } from "../../shared/api/types";
import { verifiedReferences } from "./evidenceReferences";

type Dimension = "industry_depth" | "beauty_continuity" | "role_evidence";

export function EvidenceRetrievalPanel({ fingerprint, runId, snapshot, onSelect, onSearchChange }: {
  fingerprint: string;
  runId: string;
  snapshot: AnalysisSnapshot;
  onSelect: (reference: EvidenceReference) => void;
  onSearchChange: () => void;
}) {
  const [dimension, setDimension] = useState<Dimension>("role_evidence");
  const [requested, setRequested] = useState(false);
  const search = useQuery({
    queryKey: queryKeys.evidenceSearch(fingerprint, runId, snapshot.snapshot_id, dimension),
    queryFn: () => api.evidenceSearch(fingerprint, dimension), enabled: false, retry: false,
  });
  const data = search.data;
  const sameVersion = data?.analysis_run_id === runId && data?.snapshot_id === snapshot.snapshot_id && data?.dimension_id === dimension;
  const hits = data?.status === "available" && sameVersion ? (data.hits ?? []).filter((hit) =>
    verifiedReferences(snapshot, [{ ...hit, evidence_id: `search:${hit.chunk_id}`, summary_index: 0 }], [hit.quote]).length === 1,
  ) : [];
  return (
    <section className="retrieval-panel" aria-label="岗位证据检索">
      <Typography.Title level={5}>按岗位查阅原文</Typography.Title>
      <Typography.Paragraph type="secondary">检索片段用于核验原文，不代表 AI 已认可该能力，也不会修改评分。</Typography.Paragraph>
      <Space wrap>
        <Select<Dimension> aria-label="证据检索方向" value={dimension} style={{ minWidth: 170 }}
          options={[{ value: "industry_depth", label: "专业与行业经验" },
            { value: "beauty_continuity", label: "相关技术与业务" }, { value: "role_evidence", label: "职责与落地" }]}
          onChange={(value) => { setDimension(value); setRequested(false); onSearchChange(); }} />
        <Button loading={search.isFetching} onClick={() => { setRequested(true); onSearchChange(); void search.refetch(); }}>检索原文片段</Button>
      </Space>
      {requested && search.isError ? <Alert type="error" message="原文检索失败，请重试。" /> : null}
      {requested && !search.isFetching && data ? (
        data.status === "unavailable" ? <Alert type="info" message={data.message ?? "当前分析暂无可检索原文。"} /> :
          !sameVersion ? <Alert type="warning" message="分析版本已更新，请重新加载候选人详情后检索。" /> :
            hits.length === 0 ? <Alert type="info" message="没有检索到可定位片段；这不表示候选人没有相关经历。" /> : (
              <div className="retrieval-results">
                <Typography.Text type="secondary">检索方向：{data.dimension_label}</Typography.Text>
                {hits.map((hit, index) => <article className="retrieval-hit" key={hit.chunk_id}>
                  <Typography.Text type="secondary">{hit.section}{hit.organization ? ` · ${hit.organization}` : ""}</Typography.Text>
                  <Typography.Paragraph ellipsis={{ rows: 3 }}>{hit.quote}</Typography.Paragraph>
                  <Button size="small" aria-label={`定位检索片段${index + 1}`} onClick={() => onSelect({
                    evidence_id: `search:${hit.chunk_id}`, snapshot_id: hit.snapshot_id, chunk_id: hit.chunk_id,
                    summary_index: 0, start: hit.start, end: hit.end, quote: hit.quote,
                  })}>定位原文</Button>
                </article>)}
              </div>
            )
      ) : null}
    </section>
  );
}
