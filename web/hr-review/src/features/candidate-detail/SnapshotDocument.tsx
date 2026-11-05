import { useEffect, useRef } from "react";
import type { AnalysisSnapshot, EvidenceReference } from "../../shared/api/types";

export function SnapshotDocument({ snapshot, reference }: {
  snapshot: AnalysisSnapshot;
  reference: EvidenceReference;
}) {
  const mark = useRef<HTMLElement>(null);
  const points = Array.from(snapshot.raw_text);
  const valid = reference.snapshot_id === snapshot.snapshot_id &&
    Number.isSafeInteger(reference.start) && Number.isSafeInteger(reference.end) &&
    reference.start >= 0 && reference.start < reference.end && reference.end <= points.length &&
    points.slice(reference.start, reference.end).join("") === reference.quote;
  useEffect(() => {
    if (valid) {
      mark.current?.scrollIntoView?.({ behavior: "smooth", block: "center" });
      mark.current?.focus({ preventScroll: true });
    }
  }, [valid, reference.evidence_id, snapshot.snapshot_id]);
  if (!valid) return <p role="status">原文引用不可用，请重新加载。</p>;
  return (
    <article className="resume-document snapshot-document" aria-label="分析时简历原文">
      <pre>{points.slice(0, reference.start).join("")}<mark ref={mark} tabIndex={-1} aria-label="引用原文">{points.slice(reference.start, reference.end).join("")}</mark>{points.slice(reference.end).join("")}</pre>
    </article>
  );
}
