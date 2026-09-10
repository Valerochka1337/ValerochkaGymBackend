--liquibase formatted sql

--changeset codex:012-coach-relations
CREATE TABLE coach_relations (
 id UUID PRIMARY KEY,
 coach_id UUID NOT NULL,
 recipient_id UUID NOT NULL,
 live_coach_id UUID REFERENCES users(id) ON DELETE RESTRICT,
 live_recipient_id UUID REFERENCES users(id) ON DELETE RESTRICT,
 state VARCHAR(8) NOT NULL CHECK (state IN ('ACTIVE','REVOKED')),
 calendar BOOLEAN NOT NULL,
 completed_workouts BOOLEAN NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 CHECK (coach_id <> recipient_id),
 CHECK (live_coach_id IS NULL OR live_coach_id=coach_id),
 CHECK (live_recipient_id IS NULL OR live_recipient_id=recipient_id),
 CHECK (state <> 'ACTIVE' OR (live_coach_id IS NOT NULL AND live_recipient_id IS NOT NULL AND revoked_at IS NULL))
);
CREATE UNIQUE INDEX coach_relations_live_pair ON coach_relations(coach_id,recipient_id) WHERE state='ACTIVE';
CREATE INDEX coach_relations_recipient ON coach_relations(recipient_id,id);
CREATE INDEX coach_relations_coach ON coach_relations(coach_id,id);
CREATE TABLE coach_relation_directory_heads (
 actor_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0)
);
CREATE TABLE coach_relation_invitations (
 id UUID PRIMARY KEY,
 coach_id UUID NOT NULL,
 live_coach_id UUID REFERENCES users(id) ON DELETE RESTRICT,
 key_version INT NOT NULL CHECK(key_version>0),
 digest CHAR(64) NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 consumed_by UUID,
 live_consumed_by UUID REFERENCES users(id) ON DELETE RESTRICT,
 relation_id UUID REFERENCES coach_relations(id) ON DELETE RESTRICT,
 CHECK(live_coach_id IS NULL OR live_coach_id=coach_id),
 CHECK(live_consumed_by IS NULL OR live_consumed_by=consumed_by),
 CHECK ((consumed_by IS NULL) = (relation_id IS NULL)),
 UNIQUE(key_version,digest)
);
CREATE TABLE coach_relation_operations (
 actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 operation_id UUID NOT NULL,
 action TEXT NOT NULL,
 normalized_route TEXT NOT NULL,
 resource_tuple TEXT NOT NULL,
 raw_sha256 CHAR(64) NOT NULL,
 raw_request BYTEA,
 result JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(actor_id,operation_id),
 CHECK ((action IN ('CREATE_INVITE','ACCEPT_INVITE') AND raw_request IS NULL) OR
        (action NOT IN ('CREATE_INVITE','ACCEPT_INVITE') AND octet_length(raw_request) BETWEEN 1 AND 524288))
);
ALTER TABLE training_proposals ADD COLUMN origin_relation_id UUID REFERENCES coach_relations(id) ON DELETE RESTRICT;
ALTER TABLE training_proposal_versions ADD COLUMN origin_relation_id UUID REFERENCES coach_relations(id) ON DELETE RESTRICT;

--changeset codex:012-coach-relations-origin-guard splitStatements:false
CREATE FUNCTION enforce_coach_proposal_origin() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE origin UUID; proposal_source TEXT; coach UUID; recipient UUID;
BEGIN
 IF TG_TABLE_NAME='training_proposals' THEN
  IF TG_OP='UPDATE' AND (NEW.origin_relation_id IS DISTINCT FROM OLD.origin_relation_id OR NEW.author_id IS DISTINCT FROM OLD.author_id OR NEW.recipient_id IS DISTINCT FROM OLD.recipient_id OR NEW.source IS DISTINCT FROM OLD.source) THEN
   RAISE EXCEPTION 'immutable proposal origin' USING ERRCODE='23514';
  END IF;
  IF NEW.source='COACH' AND (TG_OP='INSERT' OR NEW.origin_relation_id IS NOT NULL) THEN
   SELECT coach_id, recipient_id INTO coach,recipient FROM coach_relations WHERE id=NEW.origin_relation_id;
   IF coach IS DISTINCT FROM NEW.author_id OR recipient IS DISTINCT FROM NEW.recipient_id THEN RAISE EXCEPTION 'invalid proposal origin' USING ERRCODE='23514'; END IF;
  END IF;
  IF NEW.source='AI' AND NEW.origin_relation_id IS NOT NULL THEN RAISE EXCEPTION 'invalid AI origin' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT origin_relation_id,source INTO origin,proposal_source FROM training_proposals WHERE id=NEW.proposal_id;
  IF NEW.origin_relation_id IS DISTINCT FROM origin OR (proposal_source='COACH' AND origin IS NULL) THEN RAISE EXCEPTION 'invalid version origin' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND NEW.origin_relation_id IS DISTINCT FROM OLD.origin_relation_id THEN RAISE EXCEPTION 'immutable version origin' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER coach_proposal_origin BEFORE INSERT OR UPDATE ON training_proposals FOR EACH ROW EXECUTE FUNCTION enforce_coach_proposal_origin();
CREATE TRIGGER coach_version_origin BEFORE INSERT OR UPDATE ON training_proposal_versions FOR EACH ROW EXECUTE FUNCTION enforce_coach_proposal_origin();
