CREATE TABLE conversation_constraints (
    conversation_id VARCHAR(64) PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    settings_json TEXT NOT NULL
);
