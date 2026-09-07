--liquibase formatted sql

--changeset codex:003-admin
ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE admin_sessions (
    token_hash CHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX admin_sessions_expiry ON admin_sessions(expires_at);
CREATE TABLE admin_audit (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID NOT NULL,
    actor_email VARCHAR(254) NOT NULL,
    user_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    kind VARCHAR(32),
    record_id UUID,
    operation_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    before_payload JSONB,
    after_payload JSONB,
    revision BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(actor_id,operation_id)
);
-- Audit identities deliberately survive account deletion; no authentication secrets are recorded.
CREATE INDEX admin_audit_recent ON admin_audit(id DESC);
CREATE INDEX admin_audit_user ON admin_audit(user_id,id DESC);
CREATE INDEX records_admin_kind ON records(kind,user_id,id);
