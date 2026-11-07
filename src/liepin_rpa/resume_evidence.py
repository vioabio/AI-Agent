"""Exact immutable text and verifiable code-point references; no fuzzy guessing."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib

from liepin_rpa.analysis_versioning import content_hash

NORMALIZATION_VERSION = "exact-codepoints-v1"


def resume_text_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class ResumeSnapshot:
    snapshot_id: str
    candidate_fingerprint: str
    content_hash: str
    normalization_version: str
    raw_text: str


@dataclass(frozen=True)
class EvidenceReference:
    evidence_id: str
    snapshot_id: str
    chunk_id: str
    summary_index: int
    start: int
    end: int
    quote: str


def build_snapshot(candidate_fingerprint: str, raw_text: str) -> ResumeSnapshot:
    if not candidate_fingerprint or not isinstance(raw_text, str) or not raw_text.strip():
        raise ValueError("Snapshot requires candidate identity and nonempty original text")
    digest = resume_text_hash(raw_text)
    snapshot_id = content_hash({
        "candidate_fingerprint": candidate_fingerprint, "content_hash": digest,
        "normalization_version": NORMALIZATION_VERSION,
    })
    # No trim, CRLF conversion or Unicode normalization: offsets address original text.
    return ResumeSnapshot(snapshot_id, candidate_fingerprint, digest, NORMALIZATION_VERSION, raw_text)


def _chunk_id(snapshot_id: str, start: int, end: int) -> str:
    # A citation span is an addressable source chunk, not an embedding/RAG index.
    return content_hash({"snapshot_id": snapshot_id, "start": start, "end": end})


def _evidence_id(run_id: str, chunk_id: str, summary_index: int) -> str:
    return content_hash({"analysis_run_id": run_id, "chunk_id": chunk_id, "summary_index": summary_index})


def build_exact_references(snapshot: ResumeSnapshot, run_id: str, summaries: list[str]) -> tuple[EvidenceReference, ...]:
    references = []
    for index, quote in enumerate(summaries):
        if not isinstance(quote, str) or not quote.strip():
            continue
        start = snapshot.raw_text.find(quote)
        if start < 0 or snapshot.raw_text.find(quote, start + 1) >= 0:
            continue
        end = start + len(quote)
        chunk_id = _chunk_id(snapshot.snapshot_id, start, end)
        references.append(EvidenceReference(
            _evidence_id(run_id, chunk_id, index), snapshot.snapshot_id,
            chunk_id, index, start, end, quote,
        ))
    return tuple(references)


def valid_reference(snapshot: ResumeSnapshot, run_id: str, ref: EvidenceReference, summaries: list[str]) -> bool:
    if ref.snapshot_id != snapshot.snapshot_id or type(ref.summary_index) is not int:
        return False
    if not 0 <= ref.summary_index < len(summaries) or summaries[ref.summary_index] != ref.quote:
        return False
    if type(ref.start) is not int or type(ref.end) is not int or not 0 <= ref.start < ref.end <= len(snapshot.raw_text):
        return False
    if snapshot.raw_text[ref.start:ref.end] != ref.quote:
        return False
    chunk_id = _chunk_id(snapshot.snapshot_id, ref.start, ref.end)
    return ref.chunk_id == chunk_id and ref.evidence_id == _evidence_id(run_id, chunk_id, ref.summary_index)
