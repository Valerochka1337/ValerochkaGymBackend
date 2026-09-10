--liquibase formatted sql

--changeset codex:009-basic-profile
ALTER TABLE records DROP CONSTRAINT records_kind_check;
ALTER TABLE records ADD CONSTRAINT records_kind_check CHECK (kind IN ('exercise','gym','routine','workout','measurement','schedule','calendar_plan','calendar_rule','calendar_exception','exercise_hint','profile'));

CREATE UNIQUE INDEX records_one_profile_per_owner ON records(user_id) WHERE kind = 'profile';
ALTER TABLE records ADD CONSTRAINT records_profile_live CHECK (kind <> 'profile' OR deleted = FALSE);
