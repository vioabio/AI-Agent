import { parseScoreBreakdown } from "./scoreBreakdown";

export function AIAnalysisPanel({
  analysisReason,
  scoreBreakdown,
  evidenceSummary,
}: {
  analysisReason?: string | null;
  scoreBreakdown: string[];
  evidenceSummary: string[];
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
            {evidenceSummary.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}
          </ol>
        ) : <p className="ai-empty">暂无关键证据</p>}
      </section>
    </article>
  );
}
