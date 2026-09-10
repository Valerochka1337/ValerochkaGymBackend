--liquibase formatted sql

--changeset codex:008-workout-notes
ALTER TABLE records DROP CONSTRAINT records_kind_check;
ALTER TABLE records ADD CONSTRAINT records_kind_check CHECK (kind IN ('exercise','gym','routine','workout','measurement','schedule','calendar_plan','calendar_rule','calendar_exception','exercise_hint'));
