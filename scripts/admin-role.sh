#!/usr/bin/env bash
# Explicit operator-only role assignment; never creates or verifies an account.
set -euo pipefail
[[ $# == 2 && ( "$1" == grant || "$1" == revoke ) ]] || { echo "Usage: sudo bash admin-role.sh grant|revoke email" >&2; exit 2; }
cd "${GYM_DEPLOY_DIR:-/opt/valerochkagym}"
role_enabled=false
[[ "$1" == grant ]] && role_enabled=true
docker compose --env-file .env -f compose.production.yaml exec -T postgres   psql -U gym -d gym -v ON_ERROR_STOP=1 -v admin_email="$2" -v enabled="$role_enabled" <<'SQL'
BEGIN;
SELECT id AS target_id, is_admin AS old_admin FROM users
WHERE email=lower(trim(:'admin_email')) AND email_verified FOR UPDATE
\gset
UPDATE users SET is_admin=:'enabled'::boolean WHERE id=:'target_id';
DELETE FROM admin_sessions a USING sessions s WHERE a.session_id=s.id AND s.user_id=:'target_id';
INSERT INTO admin_audit(actor_id,actor_email,user_id,action,operation_id,request_hash,reason,before_payload,after_payload)
VALUES ('00000000-0000-0000-0000-000000000000','operator@localhost',:'target_id',
CASE WHEN :'enabled'::boolean THEN 'grant_admin' ELSE 'revoke_admin' END,
gen_random_uuid(),repeat('0',64),'Права изменены оператором сервера',
jsonb_build_object('is_admin',:'old_admin'::boolean),jsonb_build_object('is_admin',:'enabled'::boolean));
SELECT email,is_admin FROM users WHERE id=:'target_id';
COMMIT;
SQL
