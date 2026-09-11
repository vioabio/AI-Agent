-- M1：版本化宝可梦主数据与队伍约束
CREATE TABLE IF NOT EXISTS game_release (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    patch_no VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS data_release (
    id VARCHAR(64) PRIMARY KEY,
    game_release_id VARCHAR(64) NOT NULL REFERENCES game_release(id),
    source_url TEXT NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS pokemon_species (
    id BIGSERIAL PRIMARY KEY,
    national_no INTEGER NOT NULL UNIQUE,
    name_zh VARCHAR(64) NOT NULL,
    slug VARCHAR(128) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS pokemon_form (
    id BIGSERIAL PRIMARY KEY,
    species_id BIGINT NOT NULL REFERENCES pokemon_species(id),
    form_key VARCHAR(64) NOT NULL,
    name_zh VARCHAR(64) NOT NULL,
    UNIQUE (species_id, form_key)
);

CREATE TABLE IF NOT EXISTS form_fact_version (
    form_id BIGINT NOT NULL REFERENCES pokemon_form(id),
    data_release_id VARCHAR(64) NOT NULL REFERENCES data_release(id),
    type1 VARCHAR(16) NOT NULL,
    type2 VARCHAR(16),
    hp INTEGER NOT NULL,
    attack INTEGER NOT NULL,
    defense INTEGER NOT NULL,
    sp_attack INTEGER NOT NULL,
    sp_defense INTEGER NOT NULL,
    speed INTEGER NOT NULL,
    PRIMARY KEY (form_id, data_release_id)
);

CREATE TABLE IF NOT EXISTS ability (
    id BIGSERIAL PRIMARY KEY,
    name_zh VARCHAR(64) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS item (
    id BIGSERIAL PRIMARY KEY,
    name_zh VARCHAR(64) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS move_fact_version (
    id BIGSERIAL PRIMARY KEY,
    move_key VARCHAR(64) NOT NULL,
    data_release_id VARCHAR(64) NOT NULL REFERENCES data_release(id),
    name_zh VARCHAR(64) NOT NULL,
    power INTEGER,
    type VARCHAR(16) NOT NULL,
    UNIQUE (move_key, data_release_id)
);

CREATE TABLE IF NOT EXISTS learnset_version (
    form_id BIGINT NOT NULL REFERENCES pokemon_form(id),
    move_id BIGINT NOT NULL REFERENCES move_fact_version(id),
    data_release_id VARCHAR(64) NOT NULL REFERENCES data_release(id),
    method VARCHAR(32) NOT NULL,
    PRIMARY KEY (form_id, move_id, data_release_id)
);

CREATE TABLE IF NOT EXISTS ruleset_version (
    id VARCHAR(64) PRIMARY KEY,
    game_release_id VARCHAR(64) NOT NULL REFERENCES game_release(id),
    name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS pokemon_availability (
    form_id BIGINT NOT NULL REFERENCES pokemon_form(id),
    data_release_id VARCHAR(64) NOT NULL REFERENCES data_release(id),
    ruleset_id VARCHAR(64) REFERENCES ruleset_version(id),
    available BOOLEAN NOT NULL,
    PRIMARY KEY (form_id, data_release_id, ruleset_id)
);

CREATE TABLE IF NOT EXISTS saved_team (
    id BIGSERIAL PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    ruleset_id VARCHAR(64) NOT NULL REFERENCES ruleset_version(id),
    data_release_id VARCHAR(64) NOT NULL REFERENCES data_release(id)
);

CREATE TABLE IF NOT EXISTS team_member (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES saved_team(id) ON DELETE CASCADE,
    form_id BIGINT NOT NULL REFERENCES pokemon_form(id),
    slot INTEGER NOT NULL CHECK (slot BETWEEN 1 AND 6),
    UNIQUE (team_id, slot)
);

CREATE TABLE IF NOT EXISTS team_member_move (
    team_member_id BIGINT NOT NULL REFERENCES team_member(id) ON DELETE CASCADE,
    move_id BIGINT NOT NULL REFERENCES move_fact_version(id),
    slot INTEGER NOT NULL CHECK (slot BETWEEN 1 AND 4),
    PRIMARY KEY (team_member_id, slot),
    UNIQUE (team_member_id, move_id)
);
