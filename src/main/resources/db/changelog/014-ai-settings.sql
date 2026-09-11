--liquibase formatted sql

--changeset codex:014-ai-settings
CREATE TABLE ai_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  revision BIGINT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  base_url VARCHAR(2048) NOT NULL DEFAULT 'https://api.openai.com',
  encrypted_api_key TEXT,
  text_model VARCHAR(200) NOT NULL DEFAULT '',
  vision_model VARCHAR(200) NOT NULL DEFAULT '',
  coach_model VARCHAR(200) NOT NULL DEFAULT '',
  coach_models TEXT NOT NULL DEFAULT '',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID
);
INSERT INTO ai_settings (id) VALUES (1);
