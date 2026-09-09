--liquibase formatted sql

--changeset codex:007-calendar-plans
ALTER TABLE records DROP CONSTRAINT records_kind_check;
ALTER TABLE records ADD CONSTRAINT records_kind_check CHECK (kind IN ('exercise','gym','routine','workout','measurement','schedule','calendar_plan','calendar_rule','calendar_exception'));
