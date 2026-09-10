--liquibase formatted sql
--changeset codex:011-manual-health
CREATE TABLE health_owner_state (
 owner_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE RESTRICT,
 server_sequence BIGINT NOT NULL DEFAULT 0 CHECK(server_sequence>=0),
 health_revision BIGINT NOT NULL DEFAULT 0 CHECK(health_revision>=0),
 stored_bytes BIGINT NOT NULL DEFAULT 0 CHECK(stored_bytes>=0)
);
CREATE TABLE health_logicals (
 owner_id UUID NOT NULL REFERENCES health_owner_state(owner_id) ON DELETE RESTRICT,
 logical_id UUID NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('health_report','health_observation','health_restriction')),
 created_at BIGINT NOT NULL CHECK(created_at>=0), PRIMARY KEY(owner_id,logical_id), UNIQUE(owner_id,logical_id,kind)
);
CREATE TABLE health_events (
 owner_id UUID NOT NULL REFERENCES health_owner_state(owner_id) ON DELETE RESTRICT,
 health_revision BIGINT NOT NULL CHECK(health_revision>0), event_type TEXT NOT NULL CHECK(event_type IN ('VERSION','HEAD')),
 body TEXT NOT NULL, PRIMARY KEY(owner_id,health_revision)
);
CREATE TABLE health_versions (
 owner_id UUID NOT NULL, version_id UUID NOT NULL, logical_id UUID NOT NULL, kind TEXT NOT NULL,
 parent_version_id UUID, state TEXT NOT NULL CHECK(state IN ('CONFIRMED','TOMBSTONE')),
 server_sequence BIGINT NOT NULL CHECK(server_sequence>0), health_revision BIGINT NOT NULL,
 body TEXT NOT NULL, fingerprint TEXT NOT NULL, report_logical_id UUID, report_kind TEXT NOT NULL DEFAULT 'health_report' CHECK(report_kind='health_report'),
 PRIMARY KEY(owner_id,version_id), UNIQUE(owner_id,version_id,logical_id,kind), UNIQUE(owner_id,server_sequence), UNIQUE(owner_id,health_revision),
 FOREIGN KEY(owner_id,logical_id,kind) REFERENCES health_logicals(owner_id,logical_id,kind) ON DELETE RESTRICT,
 FOREIGN KEY(owner_id,parent_version_id,logical_id,kind) REFERENCES health_versions(owner_id,version_id,logical_id,kind) ON DELETE RESTRICT,
 FOREIGN KEY(owner_id,report_logical_id,report_kind) REFERENCES health_logicals(owner_id,logical_id,kind) ON DELETE RESTRICT,
 FOREIGN KEY(owner_id,health_revision) REFERENCES health_events(owner_id,health_revision) ON DELETE RESTRICT
);
CREATE TABLE health_head_history (
 owner_id UUID NOT NULL, logical_id UUID NOT NULL, head_revision BIGINT NOT NULL CHECK(head_revision>0),
 current_version_id UUID NOT NULL, kind TEXT NOT NULL, deleted BOOLEAN NOT NULL, health_revision BIGINT NOT NULL,
 PRIMARY KEY(owner_id,logical_id,head_revision), UNIQUE(owner_id,health_revision),
 FOREIGN KEY(owner_id,current_version_id,logical_id,kind) REFERENCES health_versions(owner_id,version_id,logical_id,kind) ON DELETE RESTRICT,
 FOREIGN KEY(owner_id,health_revision) REFERENCES health_events(owner_id,health_revision) ON DELETE RESTRICT
);
CREATE TABLE health_heads (
 owner_id UUID NOT NULL, logical_id UUID NOT NULL, head_revision BIGINT NOT NULL,
 PRIMARY KEY(owner_id,logical_id),
 FOREIGN KEY(owner_id,logical_id,head_revision) REFERENCES health_head_history(owner_id,logical_id,head_revision) ON DELETE RESTRICT
);
CREATE TABLE health_operations (
 owner_id UUID NOT NULL REFERENCES health_owner_state(owner_id) ON DELETE RESTRICT, operation_id UUID NOT NULL,
 raw_hash TEXT NOT NULL, raw_body BYTEA NOT NULL, result_body BYTEA NOT NULL, PRIMARY KEY(owner_id,operation_id)
);
CREATE TABLE health_ai_disclosures (
 owner_id UUID PRIMARY KEY REFERENCES health_owner_state(owner_id) ON DELETE RESTRICT,
 revision BIGINT NOT NULL CHECK(revision>0), notice_version INT NOT NULL CHECK(notice_version>0), enabled BOOLEAN NOT NULL, recorded_at BIGINT NOT NULL CHECK(recorded_at>=0)
);
CREATE TABLE health_disclosure_operations (
 owner_id UUID NOT NULL REFERENCES health_owner_state(owner_id) ON DELETE RESTRICT, operation_id UUID NOT NULL,
 raw_hash TEXT NOT NULL, raw_body BYTEA NOT NULL, result_body BYTEA NOT NULL, PRIMARY KEY(owner_id,operation_id)
);
