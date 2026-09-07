--liquibase formatted sql

--changeset codex:004-admin-credentials
CREATE TABLE admin_credentials (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    username VARCHAR(64) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'),
    password_hash VARCHAR(255) NOT NULL
);
-- Require a password login for existing browser sessions after the authentication change.
DELETE FROM admin_sessions;
