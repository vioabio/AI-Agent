import { Alert, Button, Typography } from "antd";
import { useState } from "react";

import type { CandidateDetail, EvidenceReference } from "../../shared/api/types";
import { AIAnalysisPanel } from "./AIAnalysisPanel";
import { ResumeDocument } from "./ResumeDocument";
import { SnapshotDocument } from "./SnapshotDocument";
import { verifiedReferences } from "./evidenceReferences";
import { EvidenceRetrievalPanel } from "./EvidenceRetrievalPanel";

export function EvidenceSourcePanel({ detail }: { detail: CandidateDetail }) {
  const [selection, setSelection] = useState<{ origin: "ai" | "search"; reference: EvidenceReference }>();
  const snapshot = detail.analysis_snapshot;
  const refs = detail.evidence_status === "verified"
    ? verifiedReferences(snapshot, detail.evidence_refs, detail.evidence_summary) : [];
  const selected = detail.evidence_status !== "verified" || !selection ? undefined :
    selection.origin === "ai" ? refs.find((ref) => ref.evidence_id === selection.reference.evidence_id) :
      verifiedReferences(snapshot, [selection.reference], [selection.reference.quote])[0];
  return (
    <div className="evidence-source-panel">
      <section>
        <Typography.Title level={5}>完整简历正文{snapshot && !snapshot.is_current ? "（分析记录）" : ""}</Typography.Title>
        <ResumeDocument value={detail.experience} />
      </section>
      <section>
        <Typography.Title level={5}>AI 判断依据</Typography.Title>
        <AIAnalysisPanel analysisReason={detail.analysis_reason} scoreBreakdown={detail.score_breakdown}
          evidenceSummary={detail.evidence_summary} evidenceRefs={refs}
          onEvidenceSelect={(ref) => setSelection({ origin: "ai", reference: ref })} />
        {detail.evidence_status === "invalid" ? (
          <Alert type="warning" showIcon message="原文引用校验未通过" description="暂时无法定位证据，请核对简历或重新分析。" />
        ) : detail.evidence_summary.length > 0 && refs.length === 0 ? (
          <Typography.Paragraph type="secondary">证据摘要暂不支持原文定位。</Typography.Paragraph>
        ) : null}
      </section>
      {snapshot && detail.analysis_run_id && detail.evidence_status === "verified" ? (
        <EvidenceRetrievalPanel fingerprint={detail.summary.candidate_fingerprint} runId={detail.analysis_run_id}
          snapshot={snapshot} onSelect={(reference) => setSelection({ origin: "search", reference })}
          onSearchChange={() => setSelection((current) => current?.origin === "search" ? undefined : current)} />
      ) : null}
      {snapshot && selected ? (
        <section aria-label="原文证据定位">
          <div className="snapshot-heading"><Typography.Title level={5}>{selection?.origin === "search" ? "检索片段原文（非 AI 结论引用）" : "分析时的原文证据"}</Typography.Title><Button size="small" onClick={() => setSelection(undefined)}>收起原文定位</Button></div>
          {!snapshot.is_current ? <Alert type="warning" showIcon message="该引用来自历史简历版本" description="已有更新的简历，当前高亮保留分析时保存的原文。" /> : null}
          <SnapshotDocument snapshot={snapshot} reference={selected} />
        </section>
      ) : null}
    </div>
  );
}
