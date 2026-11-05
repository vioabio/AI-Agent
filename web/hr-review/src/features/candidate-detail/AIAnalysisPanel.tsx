import { parseScoreBreakdown } from "./scoreBreakdown";
import type { EvidenceReference } from "../../shared/api/types";

export function AIAnalysisPanel({
  analysisReason,
  scoreBreakdown,
  evidenceSummary,
  evidenceRefs = [],
  onEvidenceSelect,
}: {
  analysisReason?: string | null;
  scoreBreakdown: string[];
  evidenceSummary: string[];
  evidenceRefs?: EvidenceReference[];
  onEvidenceSelect?: (ref: EvidenceReference) => void;
}) {
  const scores = parseScoreBreakdown(scoreBreakdown);

  return (
    <article className="ai-analysis-document" aria-label="AI 判断依据内容">
      <div className="ai-score-grid" aria-label="评分拆解">
        {scores.length ? scores.map((score) => (
          <div className="ai-score-item" key={score.id}>
            <span className="ai-score-label">{score.label}</span>
            <strong className="ai-score-value">{score.value}<small>分</small></strong>
          </div>
        )) : <p className="ai-empty">暂无评分拆解</p>}
      </div>

      <section className="ai-analysis-section">
        <h6 className="document-section-title">分析结论</h6>
        <p className="ai-analysis-reason">{analysisReason?.trim() || "暂无分析结论"}</p>
      </section>

      <section className="ai-analysis-section">
        <h6 className="document-section-title">关键证据</h6>
        {evidenceSummary.length ? (
          <ol className="ai-evidence-list">
            {evidenceSummary.map((item, index) => {
              const ref = evidenceRefs.find((candidate) => candidate.summary_index === index);
              return <li key={`${index}-${item}`}>{item}{ref && onEvidenceSelect ? (
                <button type="button" className="evidence-source-link" aria-label={`查看证据${index + 1}原文`} onClick={() => onEvidenceSelect(ref)}>查看原文</button>
              ) : null}</li>;
            })}
          </ol>
        ) : <p className="ai-empty">暂无关键证据</p>}
      </section>
    </article>
  );
}
