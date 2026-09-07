--liquibase formatted sql

--changeset codex:001-auth
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash TEXT,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    google_subject VARCHAR(255) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name VARCHAR(100) NOT NULL,
    access_hash CHAR(64) NOT NULL UNIQUE,
    access_expires_at TIMESTAMPTZ NOT NULL,
    refresh_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE TABLE refresh_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    used_at TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_session ON refresh_tokens(session_id);
CREATE TABLE email_challenges (
    email VARCHAR(254) NOT NULL,
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('verify', 'reset', 'delete')),
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY(email, purpose)
);
CREATE TABLE google_nonces (
    nonce_hash CHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE rate_limits (
    bucket CHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    count INT NOT NULL
);

--changeset codex:002-sync
CREATE TABLE sync_heads (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
CREATE TABLE records (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('exercise','gym','routine','workout','measurement','schedule')),
    id UUID NOT NULL,
    revision BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload JSONB,
    PRIMARY KEY(user_id,kind,id),
    CHECK ((deleted AND payload IS NULL) OR (NOT deleted AND payload IS NOT NULL))
);
CREATE INDEX records_changes ON records(user_id,revision,kind,id);
CREATE TABLE sync_operations (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,operation_id)
);
