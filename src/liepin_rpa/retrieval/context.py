"""Offline context planner; the production analysis gateway remains full-text."""
from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import re

from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.retrieval.hybrid import DenseIndex, hybrid_search
from liepin_rpa.retrieval.lexical import LexicalRetriever
from liepin_rpa.retrieval.plans import DIMENSIONS, QueryPlan

CONTEXT_VERSION = "fact-preserving-context-v2"
FACT_PATTERNS = {
    "education": re.compile(r"教育|学历|统招|本科|硕士|博士|大专|非全日制"),
    "intent": re.compile(r"求职|意向|工作城市"),
    "chronology": re.compile(r"(?:19|20)\d{2}(?:[./年-]\d{1,2})?|至今"),
    "qualification": re.compile(r"没有|未|无|不|非|仅|缺少|了解|熟悉|参与|协助"),
    "ownership": re.compile(r"(?:公司|任职单位|工作单位|项目)\s*[:：]"),
}
REQUIRED_FACTS = ("education", "intent", "chronology")
UNKNOWN_FACT_PATTERNS = {
    "education": re.compile(r"(?:教育(?:经历)?|学历)\s*[:：]?\s*(?:未知|不详|未填写|未提供|缺失|暂无)"),
    "intent": re.compile(r"(?:求职(?:意向)?|意向|工作城市)\s*[:：]?\s*(?:未知|不详|未填写|未提供|缺失|暂无)"),
    "chronology": re.compile(r"(?:任职时间|工作时间|起止时间)\s*[:：]?\s*(?:未知|不详|未填写|未提供|缺失|暂无)"),
}


@dataclass(frozen=True)
class ContextPolicy:
    max_context_chars: int = 6000
    max_fulltext_chars: int = 20000
    short_text_chars: int = 1000
    top_k_per_dimension: int = 2

    def __post_init__(self):
        for value in (self.max_context_chars, self.max_fulltext_chars, self.short_text_chars, self.top_k_per_dimension):
            if type(value) is not int or value < 1:
                raise ValueError("Context limits must be positive integers")
        if self.top_k_per_dimension > 20:
            raise ValueError("Retrieval k cannot exceed 20")


@dataclass(frozen=True)
class PreparedContext:
    requested_mode: str
    mode_used: str
    reason: str
    snapshot_id: str
    retrieval_version: str
    context_fingerprint: str
    dimension_candidates: dict[str, bool | None]
    unknown_fact_kinds: tuple[str, ...]
    context_json: str | None
    source_spans: tuple[tuple[int, int], ...]
    serialized_chars: int
    fulltext_serialized_chars: int


def _facts(text: str):
    spans, kinds, offset = [], set(), 0
    for line in text.splitlines(keepends=True):
        matches = {kind for kind, pattern in FACT_PATTERNS.items() if pattern.search(line)}
        if matches:
            spans.append((offset, offset + len(line)))
            kinds.update(matches)
        offset += len(line)
    # Markers are not qualification parsing. Explicit unknown values stay unknown
    # even if another line contains a date or the same field name.
    return spans, tuple(kind for kind in REQUIRED_FACTS
                        if kind not in kinds or UNKNOWN_FACT_PATTERNS[kind].search(text))


def prepare_context(retriever: LexicalRetriever, plan: QueryPlan, *, mode: str = "lexical",
                    policy: ContextPolicy | None = None, dense_index: DenseIndex | None = None) -> PreparedContext:
    if mode not in {"fulltext", "lexical", "hybrid"}:
        raise ValueError("Unknown context mode")
    if len(plan.dimensions) != len(DIMENSIONS) or {q.dimension_id for q in plan.dimensions} != set(DIMENSIONS):
        raise ValueError("Context requires a complete dimension query plan")
    policy = policy or ContextPolicy()
    snapshot, policy_record = retriever.snapshot, asdict(policy)
    text = snapshot.raw_text
    mandatory, unknown = _facts(text)
    spans, coverage, fallback_reasons = list(mandatory), {}, []
    for dimension in plan.dimensions:
        if mode == "fulltext":
            coverage[dimension.dimension_id] = None
            continue
        if mode == "hybrid":
            result = hybrid_search(retriever, dimension.query, candidate_fingerprint=snapshot.candidate_fingerprint,
                                   snapshot_id=snapshot.snapshot_id, index=dense_index, limit=policy.top_k_per_dimension)
            hits = result.hits
            if result.fallback_reason:
                fallback_reasons.append(result.fallback_reason)
        else:
            hits = retriever.search(dimension.query, candidate_fingerprint=snapshot.candidate_fingerprint,
                                    snapshot_id=snapshot.snapshot_id, limit=policy.top_k_per_dimension)
        # Availability of ranked candidates is NOT semantic evidence coverage.
        coverage[dimension.dimension_id] = bool(hits)
        for hit in hits:
            spans.append((hit.chunk.start, hit.chunk.end))
            spans.extend(hit.chunk.context_spans)
    version = content_hash({"algorithm": CONTEXT_VERSION, "retriever": retriever.version,
                            "plan": asdict(plan), "policy": policy_record,
                            "dense_index": dense_index.version if dense_index is not None else None})

    def render(selected):
        return json.dumps({
            "snapshot_id": snapshot.snapshot_id, "normalization_version": snapshot.normalization_version,
            "unknown_fact_kinds": list(unknown),
            "segments": [{"start": start, "end": end, "text": text[start:end]} for start, end in selected],
        }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    def finish(used, reason, selected, encoded):
        fingerprint = content_hash({"snapshot": snapshot.snapshot_id, "job": plan.job_profile_id,
                                    "mode": mode, "version": version, "used": used, "reason": reason,
                                    "context_hash": content_hash(encoded)})
        return PreparedContext(mode, used, reason, snapshot.snapshot_id, version, fingerprint, coverage,
                               unknown, encoded, tuple(selected), len(encoded) if encoded else 0, len(full))

    full = render([(0, len(text))])
    # Missing raw facts are unknown, never an automatic negative hiring decision.
    if unknown:
        return finish("needs_review", "unknown_required_facts", (), None)

    def full_or_review(reason):
        if len(full) <= policy.max_fulltext_chars:
            return finish("fulltext", reason, [(0, len(text))], full)
        return finish("needs_review", reason + ":fulltext_over_budget", (), None)

    if mode == "fulltext":
        return full_or_review("fulltext_requested")
    if len(text) <= policy.short_text_chars:
        return full_or_review("short_resume")
    if not all(coverage.values()):
        return full_or_review("missing_dimension_candidates")
    # Keep distinct, source-ordered spans; do not splice different employers into a sentence.
    selected = []
    for span in sorted(set(spans), key=lambda s: (s[0], -s[1])):
        if not any(start <= span[0] and end >= span[1] for start, end in selected):
            selected.append(span)
    encoded = render(selected)
    if len(encoded) > policy.max_context_chars:
        return full_or_review("retrieved_context_over_budget")
    used = "lexical" if mode == "lexical" or fallback_reasons else "hybrid"
    reason = sorted(set(fallback_reasons))[0] if fallback_reasons else "dimension_retrieval"
    return finish(used, reason, selected, encoded)
