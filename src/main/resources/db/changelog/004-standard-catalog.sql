--liquibase formatted sql

--changeset codex:005-standard-catalog
CREATE TABLE catalog_state (
    id INT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    source_user_id UUID,
    activated_at TIMESTAMPTZ
);
INSERT INTO catalog_state(id) VALUES (1);
CREATE TABLE standard_records (
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('exercise','gym','routine')),
    id UUID NOT NULL,
    revision BIGINT NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    payload JSONB NOT NULL,
    PRIMARY KEY(kind,id)
);
CREATE TABLE equipment (
    id VARCHAR(100) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    payload JSONB NOT NULL
);
ALTER TABLE admin_audit ALTER COLUMN user_id DROP NOT NULL;
