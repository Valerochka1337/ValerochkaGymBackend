--liquibase formatted sql

--changeset codex:015-calendar-draft-jobs
CREATE TABLE calendar_draft_jobs (
  owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  request_id UUID NOT NULL,
  session_id UUID NOT NULL,
  request_digest CHAR(64) NOT NULL,
  intent JSONB NOT NULL,
  state VARCHAR(16) NOT NULL CHECK(state IN ('QUEUED','RUNNING','READY','FAILED','SUPERSEDED','STALE','EXPIRED')),
  current_job BOOLEAN NOT NULL DEFAULT TRUE,
  executions INT NOT NULL DEFAULT 0,
  lease_token UUID,
  lease_until TIMESTAMPTZ,
  error_code VARCHAR(64),
  result JSONB,
  proposal_id UUID,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(owner_id, request_id)
);
CREATE UNIQUE INDEX calendar_draft_jobs_current ON calendar_draft_jobs(owner_id) WHERE current_job;
CREATE INDEX calendar_draft_jobs_queue ON calendar_draft_jobs(state, lease_until, created_at) WHERE current_job;
CREATE TABLE calendar_draft_job_supersessions (
  owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  request_id UUID NOT NULL,
  PRIMARY KEY(owner_id, request_id)
);
