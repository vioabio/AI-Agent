from dataclasses import asdict
import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
from typing import Literal
import yaml

from liepin_rpa.analysis_contract import analysis_contract_from_job_profile, validate_analysis_payload
from liepin_rpa.analysis_intake import PROFILE_FILES, ROOT, INTAKE_VERSION, evaluate_collection_rules
from liepin_rpa.analysis_versioning import content_hash
from liepin_rpa.resume_evidence import build_snapshot
from liepin_rpa.retrieval.lexical import LexicalRetriever
from liepin_rpa.retrieval.plans import load_query_plan
from liepin_rpa.bounded_tools.intervals import calculate_intervals, month_id


class ToolRejected(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__("Read-only tool request rejected")


class FinalContractError(ValueError):
    pass


class StrictInput(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", frozen=True)


class SearchInput(StrictInput):
    dimension_id: Literal["industry_depth", "beauty_continuity", "role_evidence"]
    query: str | None = Field(max_length=256)  # Required nullable field for strict function schemas.

    @field_validator("query")
    @classmethod
    def nonempty_query(cls, value):
        if value is not None and not value.strip():
            raise ValueError("Use null for the bound dimension query")
        return value


class ChunkInput(StrictInput):
    chunk_id: str = Field(pattern=r"^[a-f0-9]{64}$")


class RequirementsInput(StrictInput):
    pass


class IntervalInput(StrictInput):
    skill: Literal["python_backend", "java_backend", "ai_deployment"]


class SourceCitation(StrictInput):
    summary_index: int = Field(ge=0)
    start: int = Field(ge=0)
    end: int = Field(gt=0)
    quote: str = Field(min_length=1, max_length=2000)


TOOLS = {
    "search_resume_evidence": (SearchInput, "检索当前简历快照的证据片段；未召回不表示没有经历。"),
    "read_evidence_chunk": (ChunkInput, "读取当前授权快照内的原文片段，不接受路径或其他候选人标识。"),
    "get_job_requirements": (RequirementsInput, "读取服务端绑定岗位的规则，不允许选择岗位或修改规则。"),
    "calculate_experience_intervals": (IntervalInput, "汇总带明确职责标记的自述区间；不把公司总任期算作技术年限。"),
}


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON property")
        result[key] = value
    return result


def _invalid_constant(_):
    raise ValueError("Invalid JSON constant")


class BoundTools:
    def __init__(self, *, snapshot, job_profile_id, collected_record, as_of_month):
        if snapshot != build_snapshot(snapshot.candidate_fingerprint, snapshot.raw_text) or len(snapshot.raw_text) > 50000:
            raise ValueError("Invalid or oversized bound snapshot")
        if job_profile_id not in PROFILE_FILES:
            raise ValueError("Unknown bound job")
        record = json.loads(json.dumps(collected_record, ensure_ascii=False, allow_nan=False))
        if not isinstance(record, dict) or record.get("experience") != snapshot.raw_text:
            raise ValueError("Collected facts must belong to the bound text")
        month_id(as_of_month)
        profile = yaml.safe_load((ROOT / "config/job_profiles" / PROFILE_FILES[job_profile_id]).read_text(encoding="utf-8"))
        self.snapshot, self.job_profile_id, self.as_of_month = snapshot, job_profile_id, as_of_month
        self.gate = evaluate_collection_rules(profile, record)
        self.contract = analysis_contract_from_job_profile(profile)
        self.retriever = LexicalRetriever(snapshot)
        self._chunks = {chunk.chunk_id: chunk for chunk in self.retriever.chunks}
        self.plan = load_query_plan(job_profile_id)
        self._requirements = {key: profile.get(key) for key in (
            "id", "title", "education", "education_requirement", "required_job_intent_city", "experience", "selection")}
        self.version = content_hash({"job": profile, "facts": record, "as_of": as_of_month,
            "intake": INTAKE_VERSION, "retrieval": self.retriever.version, "query_plan": self.plan.version,
            "schemas": self.schemas(), "implementation": {name: Path(__file__).with_name(name).read_text(encoding="utf-8")
                for name in ("registry.py", "intervals.py", "runner.py")}})

    @staticmethod
    def schemas():
        return [{"type": "function", "function": {"name": name, "description": description,
                 "parameters": model.model_json_schema(), "strict": True}}
                for name, (model, description) in TOOLS.items()]

    def prepare(self, name, arguments_json):
        if self.gate.state != "passed":
            raise ToolRejected("qualification_not_passed")
        if not isinstance(name, str) or name not in TOOLS:
            raise ToolRejected("tool_not_allowed")
        if not isinstance(arguments_json, str) or len(arguments_json) > 4096:
            raise ToolRejected("invalid_arguments")
        try:
            arguments = json.loads(arguments_json, object_pairs_hook=_object, parse_constant=_invalid_constant)
            return name, TOOLS[name][0].model_validate(arguments)
        except (ValueError, TypeError, ValidationError):
            raise ToolRejected("invalid_arguments") from None

    @staticmethod
    def _chunk(chunk):
        return {key: getattr(chunk, key) for key in ("chunk_id", "snapshot_id", "start", "end", "text", "section", "organization")}

    def execute_prepared(self, prepared):
        if self.gate.state != "passed":
            raise ToolRejected("qualification_not_passed")
        name, arguments = prepared
        if name == "search_resume_evidence":
            dimension = next(item for item in self.plan.dimensions if item.dimension_id == arguments.dimension_id)
            hits = self.retriever.search(arguments.query if arguments.query is not None else dimension.query,
                candidate_fingerprint=self.snapshot.candidate_fingerprint, snapshot_id=self.snapshot.snapshot_id, limit=3)
            body = {"dimension_id": arguments.dimension_id, "hits": [self._chunk(hit.chunk) for hit in hits],
                    "note": "检索位置正确不等于语义支持；空结果不表示没有经历。"}
        elif name == "read_evidence_chunk":
            chunk = self._chunks.get(arguments.chunk_id)
            if chunk is None:
                raise ToolRejected("snapshot_scope_violation")
            body = self._chunk(chunk)
        elif name == "get_job_requirements":
            body = {"requirements": self._requirements, "configured_gate": asdict(self.gate),
                    "note": "岗位要求不是候选人已满足要求的证明；其他现场规则仍需原采集链执行。"}
        elif name == "calculate_experience_intervals":
            body = calculate_intervals(self.snapshot, arguments.skill, self.as_of_month)
        else:
            raise ToolRejected("tool_not_allowed")
        return json.loads(json.dumps({"snapshot_id": self.snapshot.snapshot_id, "job_profile_id": self.job_profile_id,
            "source_text_is_data": True, "data": body}, ensure_ascii=False, allow_nan=False))

    def execute(self, name, arguments_json):
        return self.execute_prepared(self.prepare(name, arguments_json))

    def validate_final(self, payload, citations):
        if self.gate.state != "passed":
            raise ToolRejected("qualification_not_passed")
        try:
            errors = validate_analysis_payload(payload, contract=self.contract)
        except (TypeError, AttributeError, ValueError):
            raise FinalContractError("Final contract rejected") from None
        if errors or any(not isinstance(value, str) or not value.strip() for value in payload.get("evidence_summary", [])):
            raise FinalContractError("Final contract rejected")
        if not isinstance(citations, list) or len(citations) != len(payload["evidence_summary"]) or not citations:
            raise ToolRejected("citation_validation_failed")
        verified, seen = [], set()
        for value in citations:
            try:
                ref = SourceCitation.model_validate(value)
            except ValidationError:
                raise ToolRejected("citation_validation_failed") from None
            if (ref.summary_index >= len(payload["evidence_summary"]) or ref.summary_index in seen
                    or ref.end > len(self.snapshot.raw_text) or ref.start >= ref.end
                    or self.snapshot.raw_text[ref.start:ref.end] != ref.quote
                    or payload["evidence_summary"][ref.summary_index] != ref.quote):
                raise ToolRejected("citation_validation_failed")
            seen.add(ref.summary_index)
            verified.append({**ref.model_dump(), "snapshot_id": self.snapshot.snapshot_id})
        return verified
