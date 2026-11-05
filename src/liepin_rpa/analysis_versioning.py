"""Stable inference identities; no credentials, raw prompts or resumes in manifests."""
from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping


def content_hash(value: object) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def pipeline_manifest(
    *, job_profile: Mapping, system_prompt: str, provider: str, model: str,
    decoding_params: Mapping, contract_version: str, rules_version: str,
    retrieval_version: str = "fulltext-v1", tool_version: str = "none",
) -> dict:
    allowed = {"temperature", "top_p", "seed", "max_tokens"}
    if set(decoding_params) - allowed:
        raise ValueError("Unsupported decoding parameter; credentials do not belong in manifests")
    if any(not isinstance(v, (int, float, type(None))) or isinstance(v, bool) for v in decoding_params.values()):
        raise ValueError("Decoding parameters must be numeric or null")
    if any(not isinstance(v, str) or not v for v in (
        provider, model, contract_version, rules_version, retrieval_version, tool_version
    )):
        raise ValueError("Inference identity fields must be nonempty strings")
    manifest = {
        "schema_version": 1,
        "job_profile_hash": content_hash(dict(job_profile)),
        "prompt_hash": content_hash(system_prompt),
        "provider": provider, "model": model, "decoding_params": dict(decoding_params),
        "contract_version": contract_version, "rules_version": rules_version,
        "retrieval_version": retrieval_version, "tool_version": tool_version,
    }
    manifest["pipeline_fingerprint"] = content_hash(manifest)
    return manifest


def analysis_cache_key(*, candidate_id: str, resume_text: str, pipeline_fingerprint: str) -> str:
    if not candidate_id or len(pipeline_fingerprint) != 64 or any(
        c not in "0123456789abcdef" for c in pipeline_fingerprint
    ):
        raise ValueError("Candidate and full pipeline fingerprint are required")
    return content_hash({
        "candidate_id": candidate_id, "resume_hash": content_hash(resume_text),
        "pipeline_fingerprint": pipeline_fingerprint,
    })
