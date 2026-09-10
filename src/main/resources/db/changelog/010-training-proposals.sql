--liquibase formatted sql

--changeset codex:010-training-proposals
CREATE TABLE training_proposal_authors (
    historical_account_id UUID PRIMARY KEY,
    live_account_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    source VARCHAR(8) NOT NULL CHECK (source = 'COACH'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (live_account_id IS NULL OR live_account_id = historical_account_id)
);

CREATE TABLE training_proposals (
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    author_id UUID REFERENCES training_proposal_authors(historical_account_id) ON DELETE RESTRICT,
    source VARCHAR(8) NOT NULL CHECK (source IN ('AI','COACH')),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','REVOKED','STALE')),
    current_version INT NOT NULL CHECK (current_version > 0),
    created_sequence BIGSERIAL NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((source = 'AI' AND author_id IS NULL) OR (source = 'COACH' AND author_id IS NOT NULL))
);
CREATE INDEX training_proposals_recipient_page ON training_proposals(recipient_id, created_sequence DESC);
CREATE INDEX training_proposals_author ON training_proposals(author_id) WHERE author_id IS NOT NULL;

CREATE TABLE training_proposal_versions (
    proposal_id UUID NOT NULL REFERENCES training_proposals(id) ON DELETE CASCADE,
    version INT NOT NULL CHECK (version > 0),
    draft JSONB NOT NULL,
    owner_revision BIGINT NOT NULL CHECK (owner_revision >= 0),
    catalog_revision BIGINT NOT NULL CHECK (catalog_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(proposal_id, version)
);

CREATE TABLE training_proposal_receipts (
    proposal_id UUID NOT NULL,
    version INT NOT NULL,
    routine_id UUID NOT NULL,
    calendar_plan_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    approved_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(proposal_id, version),
    FOREIGN KEY(proposal_id, version)
      REFERENCES training_proposal_versions(proposal_id, version) ON DELETE RESTRICT,
    UNIQUE(routine_id),
    UNIQUE(calendar_plan_id)
);

CREATE TABLE training_proposal_operations (
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    version INT NOT NULL CHECK (version > 0),
    request_sha256 CHAR(64) NOT NULL,
    raw_request BYTEA NOT NULL CHECK (octet_length(raw_request) BETWEEN 1 AND 524288),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(recipient_id, operation_id),
    FOREIGN KEY(proposal_id, version)
      REFERENCES training_proposal_versions(proposal_id, version) ON DELETE RESTRICT
);
CREATE INDEX training_proposal_operations_proposal ON training_proposal_operations(proposal_id, version);
