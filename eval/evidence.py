"""Mechanical span checks; never infer semantic support from a matching quote."""
from __future__ import annotations


def valid_span(span: object, text: str, *, require_quote: bool = False) -> bool:
    if not isinstance(span, dict):
        return False
    start, end = span.get("start"), span.get("end")
    if type(start) is not int or type(end) is not int or not 0 <= start < end <= len(text):
        return False
    return not require_quote or span.get("quote") == text[start:end]


def _coverage(gold: dict, spans: list[dict]) -> float:
    intervals = sorted((max(gold["start"], span["start"]), min(gold["end"], span["end"]))
                       for span in spans if span["end"] > gold["start"] and span["start"] < gold["end"])
    covered, last_end = 0, gold["start"]
    for start, end in intervals:
        covered += max(0, end - max(start, last_end))
        last_end = max(last_end, end)
    return covered / (gold["end"] - gold["start"])


def evidence_metrics(rows: list[dict], predictions: dict[str, dict]) -> dict:
    gold_count = hits = citation_count = valid_citations = invalid_retrieved = 0
    for row in rows:
        text = row["resume_text"]
        prediction = predictions.get(row["sample_id"], {})
        retrieved = prediction.get("retrieved_spans", [])
        refs = prediction.get("evidence_refs", [])
        if not isinstance(retrieved, list):
            retrieved = [None]
        if not isinstance(refs, list):
            refs = [None]
        valid_retrieved = [span for span in retrieved if valid_span(span, text)]
        invalid_retrieved += len(retrieved) - len(valid_retrieved)
        for gold in row.get("gold_evidence", []):
            gold_count += 1
            hits += _coverage(gold, valid_retrieved) >= 0.5
        citation_count += len(refs)
        valid_citations += sum(valid_span(ref, text, require_quote=True) for ref in refs)
    return {
        "retrieval_recall": {
            "numerator": hits, "denominator": gold_count,
            "rate": hits / gold_count if gold_count else None,
            "definition": "union of valid retrieved spans covers at least 50% of each gold span",
        },
        "citation_location_validity": {
            "numerator": valid_citations, "denominator": citation_count,
            "rate": valid_citations / citation_count if citation_count else None,
        },
        "invalid_retrieved_span_count": invalid_retrieved,
        "semantic_support": {"status": "requires_human_judgement", "rate": None},
    }
