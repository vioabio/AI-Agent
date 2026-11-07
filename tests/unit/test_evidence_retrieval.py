from dataclasses import replace
import json

import pytest

from eval.retrieval_benchmark import benchmark, generate_fixtures
from liepin_rpa.resume_evidence import build_snapshot
from liepin_rpa.retrieval.chunks import split_snapshot
from liepin_rpa.retrieval.context import ContextPolicy, prepare_context
from liepin_rpa.retrieval.hybrid import DenseIndex, IndexMismatch, hybrid_search
from liepin_rpa.retrieval.lexical import LexicalRetriever
from liepin_rpa.retrieval.plans import DIMENSIONS, DimensionQuery, QueryPlan, load_query_plan


def _retriever(text=None, **kwargs):
    text = text or "教育经历：统招本科。\r\n求职意向：上海。\r\n工作经历：\r\n公司：甲公司；2020.01-2023.12。\r\nRAG/Agent 开发。\r\n公司：乙公司；2024.01-至今。\r\n独立部署和验收。\r\n"
    return LexicalRetriever(build_snapshot("person-one", text), **kwargs)


def _search(retriever, query, **kwargs):
    return retriever.search(query, candidate_fingerprint=retriever.snapshot.candidate_fingerprint,
                            snapshot_id=retriever.snapshot.snapshot_id, **kwargs)


def _plan(query="RAG"):
    return QueryPlan("test-job", "test-plan-v1", tuple(DimensionQuery(dim, dim, query) for dim in DIMENSIONS))


def test_chunks_are_exact_codepoint_ranges_and_do_not_merge_employers():
    retriever = _retriever("工作经历：\r\n公司：甲公司；2020.01-2023.12。\r\n甲的经历🙂\r\n公司：乙公司；2024.01-至今。\r\n乙的经历。")
    chunks = retriever.chunks
    assert all(chunk.text == retriever.snapshot.raw_text[chunk.start:chunk.end] for chunk in chunks)
    assert not any("甲公司" in chunk.text and "乙公司" in chunk.text for chunk in chunks)
    assert next(chunk for chunk in chunks if "甲的经历" in chunk.text).organization == "甲公司"
    assert next(chunk for chunk in chunks if "乙的经历" in chunk.text).organization == "乙公司"
    for chunk in chunks:
        assert all(retriever.snapshot.raw_text[a:b] for a, b in chunk.context_spans)


def test_long_chunks_overlap_without_rewriting_or_losing_text():
    text = "🙂" + "甲乙丙丁" * 120
    chunks = _retriever(text, max_chars=64, overlap=8).chunks
    assert chunks[0].start == 0 and chunks[-1].end == len(text)
    assert all(b.start == a.end - 8 for a, b in zip(chunks, chunks[1:]))
    assert all(chunk.text == text[chunk.start:chunk.end] for chunk in chunks)
    assert len({chunk.chunk_id for chunk in chunks}) == len(chunks)


@pytest.mark.parametrize("bad", [{"max_chars": 0}, {"max_chars": 64, "overlap": 64}, {"overlap": -1}])
def test_invalid_chunk_configuration_is_rejected(bad):
    with pytest.raises(ValueError):
        _retriever(**bad)


def test_forged_snapshot_and_cross_candidate_search_are_rejected():
    retriever = _retriever()
    with pytest.raises(ValueError, match="mismatch"):
        split_snapshot(replace(retriever.snapshot, raw_text="伪造正文"))
    with pytest.raises(ValueError, match="scope"):
        retriever.search("RAG", candidate_fingerprint="other-person", snapshot_id=retriever.snapshot.snapshot_id)
    with pytest.raises(ValueError, match="scope"):
        retriever.search("RAG", candidate_fingerprint="person-one", snapshot_id="older-version")


def test_lexical_retrieval_normalizes_search_only_and_is_deterministic():
    retriever = _retriever("项目经历：\n部署 ＲＡＧ/Agent 服务🙂。\n备注：整理签到清单。\n")
    first = _search(retriever, "rag")
    assert first == _search(retriever, "rag")
    assert first[0].chunk.text == "部署 ＲＡＧ/Agent 服务🙂。\n"
    assert _search(retriever, "agent")[0].chunk == first[0].chunk
    assert _search(retriever, "绝不存在的 uniquezzz") == ()
    assert _search(retriever, "") == ()


def test_job_query_profiles_are_explicit_versioned_and_complete():
    ai = load_query_plan("ai_development_engineer_v1")
    fde = load_query_plan("fde_ai_deployment_engineer_v1")
    assert tuple(item.dimension_id for item in ai.dimensions) == DIMENSIONS
    assert ai.version != fde.version
    assert any("后端" in item.query for item in ai.dimensions)
    with pytest.raises(ValueError):
        load_query_plan("../../.env")


def test_context_keeps_dates_education_negation_and_ownership():
    text = "教育经历：统招本科。\n求职意向：上海。\n公司：甲公司；2020.01-至今。\n" + (
        "备注：整理清单、归档纸质收据。\n" * 70
    ) + "项目：知识库。\n独立开发 RAG。\n仅参与原型，未负责生产上线。\n"
    retriever = _retriever(text)
    context = prepare_context(retriever, _plan())
    assert context.mode_used == "lexical"
    encoded = json.loads(context.context_json)
    body = "".join(segment["text"] for segment in encoded["segments"])
    for fact in ("统招本科", "求职意向", "2020.01", "甲公司", "仅参与原型", "未负责生产上线"):
        assert fact in body
    assert context.serialized_chars == len(context.context_json) <= 6000
    for segment in encoded["segments"]:
        assert text[segment["start"]:segment["end"]] == segment["text"]


def test_context_falls_back_to_fulltext_and_never_silently_truncates():
    text = "教育经历：本科。\n求职意向：上海。\n工作经历：2020.01-至今。\n" + "RAG 项目说明。\n" * 150
    retriever = _retriever(text)
    result = prepare_context(retriever, _plan("unfindable"))
    assert result.mode_used == "fulltext" and result.reason == "missing_dimension_candidates"
    assert json.loads(result.context_json)["segments"][0]["text"] == text
    blocked = prepare_context(retriever, _plan("unfindable"), policy=ContextPolicy(max_fulltext_chars=100))
    assert blocked.mode_used == "needs_review" and blocked.context_json is None
    tiny = prepare_context(retriever, _plan(), policy=ContextPolicy(max_context_chars=1))
    assert tiny.mode_used == "fulltext" and tiny.reason == "retrieved_context_over_budget"


def test_short_and_unknown_fact_cases_are_explicit_and_do_not_change_hiring_labels():
    short = prepare_context(_retriever(), _plan())
    assert short.mode_used == "fulltext" and short.reason == "short_resume"
    unknown = prepare_context(_retriever("RAG 项目，但没有教育或求职信息。"), _plan())
    # Mere mentions are source markers, not proof of satisfying a qualification.
    assert unknown.mode_used == "needs_review" and "chronology" in unknown.unknown_fact_kinds
    assert not hasattr(unknown, "match_result")


def test_configuration_and_query_changes_invalidate_context_fingerprints():
    retriever = _retriever()
    original = prepare_context(retriever, _plan())
    changed = prepare_context(retriever, _plan("Agent"))
    budget_change = prepare_context(retriever, _plan(), policy=ContextPolicy(max_context_chars=7000))
    assert len({original.context_fingerprint, changed.context_fingerprint, budget_change.context_fingerprint}) == 3


@pytest.mark.parametrize("kind,line", [
    ("education", "学历：未提供。"), ("intent", "求职意向：未知。"),
    ("chronology", "任职时间：不详。"),
])
def test_explicit_unknown_fact_is_not_satisfied_by_unrelated_markers(kind, line):
    result = prepare_context(_retriever("教育经历：本科。\n求职意向：上海。\n毕业于2020年。\n" + line), _plan())
    assert result.mode_used == "needs_review"
    assert kind in result.unknown_fact_kinds


def _dense(retriever):
    chunks = retriever.chunks
    return DenseIndex(retriever, model_id="fixture-vectors-not-a-real-model",
                      chunk_vectors={c.chunk_id: [1.0, 0.2 + i * 0.01] for i, c in enumerate(chunks)},
                      query_vectors={"RAG": [1.0, 0.5]})


def test_dense_roundtrip_rrf_and_missing_provider_fallback(tmp_path):
    retriever = _retriever()
    index = _dense(retriever)
    path = tmp_path / "fixture-index.json"
    index.save(path)
    restored = DenseIndex.load(path, retriever)
    assert restored.version == index.version
    kwargs = {"candidate_fingerprint": "person-one", "snapshot_id": retriever.snapshot.snapshot_id}
    result = hybrid_search(retriever, "RAG", index=restored, **kwargs)
    assert result.method == "hybrid" and result.hits
    assert all(hit.method == "rrf" for hit in result.hits)
    assert hybrid_search(retriever, "RAG", index=None, **kwargs).fallback_reason == "embedding_not_configured"
    assert hybrid_search(retriever, "Agent", index=index, **kwargs).fallback_reason == "query_embedding_missing"


def test_stale_or_modified_dense_index_cannot_be_reused(tmp_path):
    old = _retriever()
    current = _retriever(old.snapshot.raw_text + "新版本\n")
    index = _dense(old)
    kwargs = {"candidate_fingerprint": "person-one", "snapshot_id": current.snapshot.snapshot_id}
    assert hybrid_search(current, "RAG", index=index, **kwargs).fallback_reason == "stale_dense_index"
    index.save(tmp_path / "index.json")
    with pytest.raises(IndexMismatch):
        DenseIndex.load(tmp_path / "index.json", current)
    index.vectors[next(iter(index.vectors))] = (0.0, 1.0)
    assert hybrid_search(old, "RAG", index=index, candidate_fingerprint="person-one",
                         snapshot_id=old.snapshot.snapshot_id).fallback_reason == "stale_dense_index"


@pytest.mark.parametrize("vector", [[0, 0], [float("nan"), 1], [1, 0, 0], [True, 1]])
def test_invalid_dense_vectors_are_rejected(vector):
    retriever = _retriever()
    with pytest.raises(ValueError):
        DenseIndex(retriever, model_id="fixture", chunk_vectors={c.chunk_id: vector for c in retriever.chunks},
                   query_vectors={"RAG": [1, 0]})


def test_retrieval_benchmark_reports_coverage_not_fake_model_accuracy(tmp_path):
    path = tmp_path / "synthetic.jsonl"
    generate_fixtures(path)
    report = benchmark(path)
    assert report["sample_count"] == 12
    assert report["quality_claim_allowed"] is False
    assert report["arms"]["fulltext"]["source_coverage"]["retrieval_recall"]["rate"] == 1
    assert report["arms"]["hybrid"]["status"] == "unavailable"
    assert report["model_cost"]["amount"] is None and report["llm_quality"]["precision"] is None
    assert report["default_path_decision"] == "keep_existing_fulltext_provider_path"
    assert "resume_text" not in json.dumps(report)
