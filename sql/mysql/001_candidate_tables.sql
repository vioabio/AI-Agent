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
