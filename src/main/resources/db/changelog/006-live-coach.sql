--liquibase formatted sql

--changeset codex:006-live-coach
ALTER TABLE sync_heads ADD COLUMN min_sync_version INTEGER NOT NULL DEFAULT 2;
CREATE TABLE coach_journal (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    workout_id UUID NOT NULL,
    device_id UUID NOT NULL,
    created_at BIGINT NOT NULL,
    sequence BIGSERIAL NOT NULL,
    payload JSONB,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(user_id, id)
);
CREATE INDEX coach_journal_page ON coach_journal(user_id, sequence);
CREATE INDEX coach_journal_workout ON coach_journal(user_id, workout_id);
