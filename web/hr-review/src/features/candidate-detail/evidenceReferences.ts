import type { AnalysisSnapshot, EvidenceReference } from "../../shared/api/types";

export function verifiedReferences(
  snapshot: AnalysisSnapshot | null | undefined,
  references: EvidenceReference[] | undefined,
  summaries: string[],
): EvidenceReference[] {
  if (!snapshot || snapshot.normalization_version !== "exact-codepoints-v1") return [];
  const points = Array.from(snapshot.raw_text);
  return (references ?? []).filter((ref) =>
    ref.snapshot_id === snapshot.snapshot_id &&
    Number.isSafeInteger(ref.summary_index) && ref.summary_index >= 0 &&
    Number.isSafeInteger(ref.start) && Number.isSafeInteger(ref.end) &&
    ref.start >= 0 && ref.start < ref.end && ref.end <= points.length &&
    summaries[ref.summary_index] === ref.quote &&
    points.slice(ref.start, ref.end).join("") === ref.quote,
  );
}
