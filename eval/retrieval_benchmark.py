"""Synthetic source-retrieval experiment, not an LLM quality/cost benchmark."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import uuid

from eval.evidence import evidence_metrics
from eval.run_eval import ROOT, _digest, _source_manifest, load_dataset
from liepin_rpa.resume_evidence import build_snapshot
from liepin_rpa.retrieval.context import ContextPolicy, prepare_context
from liepin_rpa.retrieval.lexical import LexicalRetriever
from liepin_rpa.retrieval.plans import load_query_plan


def generate_fixtures(path: Path) -> None:
    rows = []
    for index in range(18):
        job = "ai_development_engineer_v1" if index % 2 == 0 else "fde_ai_deployment_engineer_v1"
        variant = index % 6
        lines, gold_spans, offset = [], [], 0

        def add(text, *, gold=False):
            nonlocal offset
            if gold:
                gold_spans.append({"start": offset, "end": offset + len(text)})
            lines.append(text + "\n")
            offset += len(text) + 1

        add(f"【纯合成检索夹具 retrieval-{index + 1:03d}，不是真实简历】")
        if variant != 3:
            add("教育经历：示范大学，统招本科，计算机专业。")
        add("求职意向：上海。")
        add("工作经历：")
        add("公司：虚构甲公司；岗位：后端开发；2020.01-2023.12。")
        for number in range(45):
            add(f"附注{number}：整理签到名单、汇总会务安排、归档纸质收据、登记固定资产清单。")
        add("项目：知识服务系统。")
        if variant == 5:
            add("职责是整理签到名单和会务物料。", gold=True)
        else:
            add("使用 Python FastAPI 开发 API，接入 MySQL 数据库并负责测试。", gold=True)
            add("负责大模型 RAG Agent Workflow，完成需求梳理与客户系统集成。", gold=True)
            add("独立使用 Docker 部署生产服务，完成上线交付与故障处理验收。", gold=True)
        if variant == 1:
            add("公司：虚构乙公司；岗位：技术助理；2024.01-至今。")
            add("使用 Python FastAPI 开发 API，接入 MySQL 数据库并负责测试。")
            add("仅参与原型演示，未负责生产上线。", gold=True)
        elif variant == 2:
            add("仅了解相关名词，未独立交付其他项目。", gold=True)
        elif variant == 4:
            add("事实核验：未承担额外职责。" + "登记清单。" * 1450, gold=True)
        for number in range(45, 90):
            add(f"附注{number}：汇总会务安排、登记固定资产清单、整理签到名单、归档纸质收据。")
        rows.append({
            "sample_id": f"retrieval-{index + 1:03d}", "group_id": f"retrieval-person-{index + 1:03d}",
            "source_type": "synthetic", "split": "dev" if index < 12 else "test",
            "job_profile_id": job, "resume_text": "".join(lines),
            "gold": "needs_review" if variant in (2, 3) else "not_relevant" if variant == 5 else "relevant",
            "gold_evidence": gold_spans,
        })
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def benchmark(dataset: Path, *, split: str = "dev", policy: ContextPolicy | None = None) -> dict:
    all_rows = load_dataset(dataset)
    if any(row["source_type"] != "synthetic" for row in all_rows):
        raise ValueError("This benchmark accepts synthetic fixtures only")
    if split not in {"dev", "test"}:
        raise ValueError("Invalid split")
    rows = [row for row in all_rows if row["split"] == split]
    if not rows:
        raise ValueError("Selected split has no samples")
    policy = policy or ContextPolicy()
    raw_predictions, routed_predictions, full_predictions = {}, {}, {}
    cases, versions = [], {}
    for row in rows:
        snapshot = build_snapshot(row["group_id"], row["resume_text"])
        retriever, plan = LexicalRetriever(snapshot), load_query_plan(row["job_profile_id"])
        versions[plan.job_profile_id] = plan.version
        lexical = prepare_context(retriever, plan, mode="lexical", policy=policy)
        full = prepare_context(retriever, plan, mode="fulltext", policy=policy)
        raw_hits = {}
        for dimension in plan.dimensions:
            for hit in retriever.search(dimension.query, candidate_fingerprint=snapshot.candidate_fingerprint,
                                        snapshot_id=snapshot.snapshot_id, limit=policy.top_k_per_dimension):
                raw_hits[hit.chunk.chunk_id] = {"start": hit.chunk.start, "end": hit.chunk.end}
        sample_id = row["sample_id"]
        raw_predictions[sample_id] = {"retrieved_spans": list(raw_hits.values())}
        routed_predictions[sample_id] = {"retrieved_spans": [{"start": a, "end": b} for a, b in lexical.source_spans]}
        full_predictions[sample_id] = {"retrieved_spans": [{"start": 0, "end": len(snapshot.raw_text)}]}
        cases.append({
            "sample_id": sample_id, "job_profile_id": plan.job_profile_id,
            "source_chars": len(snapshot.raw_text), "fulltext_serialized_chars": full.fulltext_serialized_chars,
            "lexical_serialized_chars": lexical.serialized_chars, "mode_used": lexical.mode_used,
            "reason": lexical.reason, "fulltext_mode_used": full.mode_used,
            "unknown_fact_kinds": list(lexical.unknown_fact_kinds),
            "context_fingerprint": lexical.context_fingerprint, "retrieval_version": lexical.retrieval_version,
            "raw_hit_count": len(raw_hits), "source_span_count": len(lexical.source_spans),
        })
    compact = [case for case in cases if case["mode_used"] == "lexical"]
    return {
        "schema_version": 1, "mode": "synthetic_source_retrieval_only", "quality_claim_allowed": False,
        "manifest": {"dataset_hash": _digest(dataset.read_bytes()), "split": split,
                     "query_plan_versions": versions, "policy": policy.__dict__, **_source_manifest()},
        "sample_count": len(rows), "cases": cases,
        "arms": {
            "fulltext": {"source_coverage": evidence_metrics(rows, full_predictions),
                         "mean_serialized_chars": sum(c["fulltext_serialized_chars"] for c in cases) / len(cases)},
            "lexical": {"raw_retrieval": evidence_metrics(rows, raw_predictions),
                        "guarded_context": evidence_metrics(rows, routed_predictions),
                        "routes": dict(Counter(c["mode_used"] for c in cases)),
                        "reasons": dict(Counter(c["reason"] for c in cases)),
                        "compact_case_count": len(compact),
                        "compact_mean_char_ratio": sum(c["lexical_serialized_chars"] / c["fulltext_serialized_chars"] for c in compact) / len(compact) if compact else None},
            "hybrid": {"status": "unavailable", "reason": "no_embedding_provider_configured",
                       "fusion_tested_with_supplied_fixture_vectors_only": True},
        },
        "llm_quality": {"status": "not_measured", "precision": None, "recall": None},
        "model_cost": {"status": "unknown", "amount": None}, "model_tokens": None,
        "default_path_decision": "keep_existing_fulltext_provider_path",
        "limitations": ["Synthetic labels and source coverage do not prove model quality or semantic support.",
                        "Character counts include context JSON, not model tokens or monetary savings.",
                        "No real embeddings, reranker or live model calls were evaluated."],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path)
    parser.add_argument("--generate-fixtures", action="store_true")
    parser.add_argument("--split", choices=("dev", "test"), default="dev")
    parser.add_argument("--context-chars", type=int, default=6000)
    parser.add_argument("--fulltext-chars", type=int, default=20000)
    parser.add_argument("--top-k", type=int, default=2)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "artifacts/eval/retrieval")
    args = parser.parse_args(argv)
    if bool(args.dataset) == args.generate_fixtures:
        parser.error("Choose exactly one of --dataset or --generate-fixtures")
    run_id = "retrieval-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    run_dir = args.output_dir / run_id
    try:
        run_dir.mkdir(parents=True, exist_ok=False)
        dataset = args.dataset or run_dir / "synthetic_retrieval.jsonl"
        if args.generate_fixtures:
            generate_fixtures(dataset)
        result = benchmark(dataset, split=args.split, policy=ContextPolicy(
            max_context_chars=args.context_chars, max_fulltext_chars=args.fulltext_chars, top_k_per_dimension=args.top_k))
    except (ValueError, OSError):
        parser.exit(2, "Retrieval benchmark rejected; check synthetic data and policy.\n")
    result["run_id"] = run_id
    path = run_dir / "report.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Synthetic retrieval only; no live calls: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
