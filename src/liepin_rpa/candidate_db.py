from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


CREATE_TABLES_SQL = """
CREATE TABLE IF NOT EXISTS candidate_profiles (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  candidate_identity_hash CHAR(32) NOT NULL,
  candidate_source_id VARCHAR(64) DEFAULT NULL,
  platform VARCHAR(32) NOT NULL DEFAULT 'liepin',
  name VARCHAR(128) NOT NULL,
  age INT DEFAULT NULL,
  education VARCHAR(64) DEFAULT NULL,
  work_years INT DEFAULT NULL,
  current_company VARCHAR(255) DEFAULT NULL,
  current_position VARCHAR(255) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_candidate_identity_hash (candidate_identity_hash),
  KEY idx_name_age (name, age),
  KEY idx_current_company (current_company)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS candidate_work_experiences (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  candidate_id BIGINT UNSIGNED NOT NULL,
  sequence_no INT NOT NULL,
  company_name VARCHAR(255) DEFAULT NULL,
  position_name VARCHAR(255) DEFAULT NULL,
  period_start VARCHAR(16) DEFAULT NULL,
  period_end VARCHAR(16) DEFAULT NULL,
  period_raw_text VARCHAR(128) DEFAULT NULL,
  work_content_json JSON NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_candidate_sequence (candidate_id, sequence_no),
  KEY idx_company_name (company_name),
  CONSTRAINT fk_work_experience_candidate
    FOREIGN KEY (candidate_id) REFERENCES candidate_profiles(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS candidate_search_hits (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  candidate_id BIGINT UNSIGNED NOT NULL,
  search_task_id VARCHAR(128) DEFAULT NULL,
  search_brand_keyword VARCHAR(128) DEFAULT NULL,
  search_role_keyword VARCHAR(128) DEFAULT NULL,
  is_match TINYINT(1) DEFAULT NULL,
  collected_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_candidate_search_task (candidate_id, search_task_id),
  KEY idx_search_keywords (search_brand_keyword, search_role_keyword),
  CONSTRAINT fk_search_hit_candidate
    FOREIGN KEY (candidate_id) REFERENCES candidate_profiles(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
""".strip()


@dataclass(slots=True)
class CandidateDbPreview:
    candidate_profile: dict[str, Any]
    work_experiences: list[dict[str, Any]]
    search_hit: dict[str, Any]


def load_resume_report(report_path: Path) -> dict[str, Any]:
    return json.loads(report_path.read_text(encoding="utf-8"))


def build_candidate_db_preview(report_payload: dict[str, Any]) -> CandidateDbPreview:
    basic_info = report_payload.get("basic_info", {})
    source = report_payload.get("source", {})
    identity_hash = build_candidate_identity_hash(basic_info)

    candidate_profile = {
        "candidate_identity_hash": identity_hash,
        "candidate_source_id": report_payload.get("candidate_id"),
        "platform": source.get("platform") or "liepin",
        "name": basic_info.get("name"),
        "age": basic_info.get("age"),
        "education": basic_info.get("education"),
        "work_years": basic_info.get("work_years"),
        "current_company": basic_info.get("current_company"),
        "current_position": basic_info.get("current_position"),
    }

    work_experiences: list[dict[str, Any]] = []
    for experience in report_payload.get("work_experiences", []):
        work_experiences.append(
            {
                "sequence_no": experience.get("sequence"),
                "company_name": nested_get(experience, "company", "normalized_name"),
                "position_name": nested_get(experience, "position", "normalized_title"),
                "period_start": nested_get(experience, "employment_period", "start_date"),
                "period_end": nested_get(experience, "employment_period", "end_date"),
                "period_raw_text": nested_get(experience, "employment_period", "raw_text"),
                "work_content": collect_work_content(experience),
            }
        )

    search_hit = {
        "search_task_id": source.get("search_task_id"),
        "search_brand_keyword": source.get("search_brand_keyword"),
        "search_role_keyword": source.get("search_role_keyword"),
        "is_match": None,
        "collected_at": source.get("collected_at"),
    }

    return CandidateDbPreview(
        candidate_profile=candidate_profile,
        work_experiences=work_experiences,
        search_hit=search_hit,
    )


def build_candidate_identity_hash(basic_info: dict[str, Any]) -> str:
    material = "|".join(
        [
            str(basic_info.get("name") or ""),
            str(basic_info.get("age") or ""),
            str(basic_info.get("education") or ""),
            str(basic_info.get("work_years") or ""),
            str(basic_info.get("current_company") or ""),
            str(basic_info.get("current_position") or ""),
        ]
    )
    return hashlib.md5(material.encode("utf-8")).hexdigest()


def collect_work_content(experience: dict[str, Any]) -> list[str]:
    merged: list[str] = []
    for item in experience.get("responsibilities", []):
        if item and item not in merged:
            merged.append(item)
    for item in experience.get("achievements", []):
        if item and item not in merged:
            merged.append(item)
    if not merged and experience.get("raw_description"):
        merged.append(experience["raw_description"])
    return merged


def nested_get(payload: dict[str, Any], *keys: str) -> Any:
    current: Any = payload
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current
