--liquibase formatted sql

--changeset codex:013-calendar-ai-attempts
CREATE TABLE calendar_ai_attempts (
  owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  request_id UUID NOT NULL,
  raw_request_sha256 CHAR(64) NOT NULL,
  state VARCHAR(12) NOT NULL CHECK (state IN ('PROCESSING','COMMITTING','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')),
  admitted_at TIMESTAMPTZ NOT NULL,
  deadline_at TIMESTAMPTZ NOT NULL,
  lease_until TIMESTAMPTZ NOT NULL,
  terminal_status INT,
  terminal_code VARCHAR(64),
  receipt JSONB,
  PRIMARY KEY (owner_id, request_id),
  CHECK ((state='SUCCEEDED') = (receipt IS NOT NULL)),
  CHECK (receipt IS NULL OR state='SUCCEEDED'),
  CHECK (terminal_status IS NULL OR state IN ('FAILED','CANCELLED','INTERRUPTED')),
  CHECK (terminal_code IS NULL OR state IN ('FAILED','CANCELLED','INTERRUPTED'))
);
CREATE INDEX calendar_ai_attempts_lease ON calendar_ai_attempts(owner_id, lease_until);
CREATE INDEX records_calendar_ai_history ON records(user_id, ((payload->>'finishedAt')::numeric), id)
  WHERE kind='workout' AND NOT deleted AND jsonb_typeof(payload->'finishedAt')='number';
CREATE INDEX records_calendar_ai_kind ON records(user_id, kind, id) WHERE NOT deleted;
