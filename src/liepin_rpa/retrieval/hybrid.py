"""Exact cosine + RRF over supplied vectors. No embedding provider is invoked."""
from __future__ import annotations

from dataclasses import dataclass
import json
import math
from pathlib import Path

from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.retrieval.lexical import LexicalRetriever, SearchHit


class IndexMismatch(ValueError):
    pass


def _vector(value) -> tuple[float, ...]:
    if not isinstance(value, (list, tuple)) or not 1 <= len(value) <= 4096:
        raise ValueError("Invalid embedding dimension")
    if any(type(n) not in (int, float) or not math.isfinite(n) for n in value):
        raise ValueError("Embedding values must be finite numbers")
    result = tuple(float(n) for n in value)
    norm = math.sqrt(sum(n * n for n in result))
    if not math.isfinite(norm) or norm == 0:
        raise ValueError("Embedding must have a finite nonzero norm")
    return tuple(n / norm for n in result)


class DenseIndex:
    def __init__(self, retriever: LexicalRetriever, *, model_id: str, chunk_vectors: dict,
                 query_vectors: dict[str, list[float] | tuple[float, ...]]):
        if not isinstance(model_id, str) or not model_id:
            raise ValueError("Embedding model identity is required")
        if set(chunk_vectors) != {chunk.chunk_id for chunk in retriever.chunks}:
            raise ValueError("Dense index must cover exactly the bound snapshot chunks")
        self.snapshot_id = retriever.snapshot.snapshot_id
        self.retrieval_version = retriever.version
        self.model_id = model_id
        self._raw_vectors = {key: tuple(value) for key, value in chunk_vectors.items()}
        self._raw_queries = {key: tuple(value) for key, value in query_vectors.items()}
        self.vectors = {key: _vector(value) for key, value in chunk_vectors.items()}
        self.queries = {content_hash(query): _vector(value) for query, value in query_vectors.items()}
        dimensions = {len(value) for value in list(self.vectors.values()) + list(self.queries.values())}
        if len(dimensions) != 1:
            raise ValueError("Inconsistent embedding dimensions")
        self.version = content_hash({"snapshot": self.snapshot_id, "retrieval": self.retrieval_version,
                                     "model": model_id, "vectors": self.vectors, "queries": self.queries})

    def search(self, retriever: LexicalRetriever, query: str, limit: int) -> tuple[SearchHit, ...] | None:
        if (self.snapshot_id, self.retrieval_version) != (retriever.snapshot.snapshot_id, retriever.version):
            raise IndexMismatch("Dense index snapshot/chunking identity is stale")
        try:
            digest = content_hash({"snapshot": self.snapshot_id, "retrieval": self.retrieval_version,
                                   "model": self.model_id, "vectors": self.vectors, "queries": self.queries})
        except (ValueError, TypeError):
            raise IndexMismatch("Dense index content was modified") from None
        if digest != self.version:
            raise IndexMismatch("Dense index content was modified")
        vector = self.queries.get(content_hash(query))
        if vector is None:
            return None
        hits = [SearchHit(chunk, sum(a * b for a, b in zip(vector, self.vectors[chunk.chunk_id])), "cosine")
                for chunk in retriever.chunks]
        # Positive similarity is only a retrieval floor, never evidence confidence.
        return tuple(sorted((h for h in hits if h.score > 0), key=lambda h: (-h.score, h.chunk.start))[:limit])

    def save(self, path: Path) -> None:
        document = {"schema_version": 1, "snapshot_id": self.snapshot_id,
                    "retrieval_version": self.retrieval_version, "model_id": self.model_id,
                    "chunk_vectors": self._raw_vectors, "query_vectors": self._raw_queries,
                    "index_hash": self.version}
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document, ensure_ascii=False, sort_keys=True, allow_nan=False), encoding="utf-8")

    @classmethod
    def load(cls, path: Path, retriever: LexicalRetriever) -> "DenseIndex":
        if path.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("Dense index file is too large")
        document = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(document, dict) or document.get("schema_version") != 1:
            raise ValueError("Invalid dense index format")
        if (document.get("snapshot_id"), document.get("retrieval_version")) != (retriever.snapshot.snapshot_id, retriever.version):
            raise IndexMismatch("Dense index snapshot/chunking identity is stale")
        try:
            index = cls(retriever, model_id=document["model_id"], chunk_vectors=document["chunk_vectors"],
                        query_vectors=document["query_vectors"])
        except (KeyError, TypeError):
            raise ValueError("Invalid dense index format") from None
        if index.version != document.get("index_hash"):
            raise IndexMismatch("Dense index checksum mismatch")
        return index


@dataclass(frozen=True)
class HybridResult:
    hits: tuple[SearchHit, ...]
    method: str
    fallback_reason: str | None


def hybrid_search(retriever: LexicalRetriever, query: str, *, candidate_fingerprint: str,
                  snapshot_id: str, index: DenseIndex | None, limit: int = 5, rank_constant: int = 60) -> HybridResult:
    lexical = retriever.search(query, candidate_fingerprint=candidate_fingerprint, snapshot_id=snapshot_id, limit=limit)
    if type(rank_constant) is not int or rank_constant < 1:
        raise ValueError("RRF rank constant must be positive")
    if index is None:
        return HybridResult(lexical, "bm25", "embedding_not_configured")
    try:
        dense = index.search(retriever, query, limit)
    except IndexMismatch:
        return HybridResult(lexical, "bm25", "stale_dense_index")
    if dense is None:
        return HybridResult(lexical, "bm25", "query_embedding_missing")
    scores, chunks = {}, {}
    for ranking in (lexical, dense):
        for rank, hit in enumerate(ranking, 1):
            key = hit.chunk.chunk_id
            chunks[key] = hit.chunk
            scores[key] = scores.get(key, 0) + 1 / (rank_constant + rank)
    hits = [SearchHit(chunks[key], value, "rrf") for key, value in scores.items()]
    return HybridResult(tuple(sorted(hits, key=lambda h: (-h.score, h.chunk.start))[:limit]), "hybrid", None)
