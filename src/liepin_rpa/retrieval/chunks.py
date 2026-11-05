from __future__ import annotations

from dataclasses import dataclass
import re

from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.resume_evidence import ResumeSnapshot, build_snapshot

CHUNKER_VERSION = "source-lines-v1"
SECTION = re.compile(r"(候选人信息|基本信息|求职意向|工作内容|工作经历|项目内容|项目经历|教育经历|专业技能|技能特长|自我评价)\s*[:：]")
ANCHOR = re.compile(r"(?:^|(?<=[；;]))\s*(?:公司|任职单位|工作单位|项目)\s*[:：]", re.M)
ORGANIZATION = re.compile(r"^\s*(?:公司|任职单位|工作单位)\s*[:：]([^；;\r\n]{1,80})")


@dataclass(frozen=True)
class ResumeChunk:
    chunk_id: str
    snapshot_id: str
    start: int
    end: int
    text: str
    section: str
    organization: str | None
    context_spans: tuple[tuple[int, int], ...]


def chunking_version(max_chars: int, overlap: int) -> str:
    return content_hash({"algorithm": CHUNKER_VERSION, "max_chars": max_chars, "overlap": overlap})


def split_snapshot(snapshot: ResumeSnapshot, *, max_chars: int = 512, overlap: int = 64) -> tuple[ResumeChunk, ...]:
    if snapshot != build_snapshot(snapshot.candidate_fingerprint, snapshot.raw_text):
        raise ValueError("Snapshot identity/content mismatch")
    if type(max_chars) is not int or type(overlap) is not int or not 64 <= max_chars <= 4000 or not 0 <= overlap < max_chars:
        raise ValueError("Invalid chunk size/overlap")
    text, chunks = snapshot.raw_text, []
    # Strong section/company anchors are additional boundaries even on compact lines.
    boundaries = sorted({0, len(text)} | {m.start() for m in SECTION.finditer(text)} | {m.start() for m in ANCHOR.finditer(text)})
    section, organization = "未分节", None
    section_span, organization_span = None, None
    version = chunking_version(max_chars, overlap)
    for begin, stop in zip(boundaries, boundaries[1:]):
        cursor = begin
        for line in text[begin:stop].splitlines(keepends=True):
            end = cursor + len(line)
            if not line.strip():
                cursor = end
                continue
            heading = SECTION.match(line)
            if heading:
                section, organization, organization_span = heading.group(1), None, None
                section_span = (cursor, cursor + heading.end())
            company = ORGANIZATION.match(line)
            if company:
                organization = company.group(1)
                organization_span = (cursor, cursor + company.end())
            contexts = tuple(span for span in (section_span, organization_span) if span is not None)
            start = cursor
            while start < end:
                chunk_end = min(start + max_chars, end)
                chunk_id = content_hash({"snapshot_id": snapshot.snapshot_id, "version": version,
                                         "start": start, "end": chunk_end})
                chunks.append(ResumeChunk(chunk_id, snapshot.snapshot_id, start, chunk_end,
                                          text[start:chunk_end], section, organization, contexts))
                if chunk_end == end:
                    break
                start = chunk_end - overlap
            cursor = end
    return tuple(chunks)
