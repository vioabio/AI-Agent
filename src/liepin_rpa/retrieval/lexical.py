from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
import math
import re
import unicodedata

from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.resume_evidence import ResumeSnapshot
from liepin_rpa.retrieval.chunks import ResumeChunk, chunking_version, split_snapshot

TOKENIZER_VERSION = "nfkc-latin-han-bigrams-v1"
BM25_VERSION = "bm25-k1-1.2-b-0.75-v1"
RUN = re.compile(r"[a-z0-9][a-z0-9+#]*|[\u3400-\u9fff]+")


def tokenize(value: str, *, query: bool = False) -> list[str]:
    tokens = []
    for match in RUN.finditer(unicodedata.normalize("NFKC", value).lower()):
        word = match.group()
        if "\u3400" <= word[0] <= "\u9fff":
            if not query or len(word) == 1:
                tokens.extend(word)
            tokens.extend(word[i:i + 2] for i in range(len(word) - 1))
        else:
            tokens.append(word)
    return tokens


@dataclass(frozen=True)
class SearchHit:
    chunk: ResumeChunk
    score: float
    method: str = "bm25"


class LexicalRetriever:
    def __init__(self, snapshot: ResumeSnapshot, *, max_chars: int = 512, overlap: int = 64):
        self.snapshot = snapshot
        self.chunks = split_snapshot(snapshot, max_chars=max_chars, overlap=overlap)
        self.chunking_version = chunking_version(max_chars, overlap)
        self.version = content_hash({"chunking": self.chunking_version,
                                     "tokenizer": TOKENIZER_VERSION, "ranker": BM25_VERSION})
        self._counts = [Counter(tokenize(chunk.text)) for chunk in self.chunks]
        self._lengths = [sum(count.values()) for count in self._counts]
        self._average_length = sum(self._lengths) / len(self._lengths) if self._lengths else 1
        self._frequencies = Counter(token for count in self._counts for token in count)

    def verify_scope(self, candidate_fingerprint: str, snapshot_id: str) -> None:
        if (candidate_fingerprint, snapshot_id) != (self.snapshot.candidate_fingerprint, self.snapshot.snapshot_id):
            raise ValueError("Retrieval cannot cross candidate/snapshot scope")

    def search(self, query: str, *, candidate_fingerprint: str, snapshot_id: str, limit: int = 5) -> tuple[SearchHit, ...]:
        self.verify_scope(candidate_fingerprint, snapshot_id)
        if not isinstance(query, str) or len(query) > 256 or type(limit) is not int or not 1 <= limit <= 20:
            raise ValueError("Invalid retrieval query or limit")
        terms, scores = sorted(set(tokenize(query, query=True))), []
        count = len(self.chunks)
        for index, frequencies in enumerate(self._counts):
            score = 0.0
            for term in terms:
                frequency = frequencies.get(term, 0)
                if not frequency:
                    continue
                df = self._frequencies[term]
                inverse = math.log1p((count - df + 0.5) / (df + 0.5))
                norm = 1.2 * (1 - 0.75 + 0.75 * self._lengths[index] / (self._average_length or 1))
                score += inverse * frequency * (1.2 + 1) / (frequency + norm)
            if score > 0:
                scores.append(SearchHit(self.chunks[index], score))
        return tuple(sorted(scores, key=lambda hit: (-hit.score, hit.chunk.start))[:limit])
