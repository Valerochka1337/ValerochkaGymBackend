#!/usr/bin/env bash
# Operator-only credential provisioning. Read an Argon2id hash from stdin, never a raw password.
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: sudo bash admin-password.sh username email < password-hash-file' >&2; exit 2; }
[[ "$1" =~ ^[a-z0-9][a-z0-9_.-]{0,63}$ ]] || { echo 'Invalid username' >&2; exit 2; }
IFS= read -r password_hash
[[ "$password_hash" =~ ^\$argon2id\$v=19\$m=19456,t=2,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$ ]] || { echo 'Expected an Argon2id hash with the application parameters' >&2; exit 2; }
cd "${GYM_DEPLOY_DIR:-/opt/valerochkagym}"
docker compose --env-file .env -f compose.production.yaml exec -T postgres \
  psql -U gym -d gym -v ON_ERROR_STOP=1 -v admin_email="$2" -v username="$1" -v password_hash="$password_hash" <<'SQL'
BEGIN;
SELECT id AS target_id FROM users
WHERE email=lower(trim(:'admin_email')) AND email_verified AND is_admin FOR UPDATE
\gset
INSERT INTO admin_credentials(user_id,username,password_hash)
VALUES (:'target_id',:'username',:'password_hash')
ON CONFLICT(user_id) DO UPDATE SET username=EXCLUDED.username,password_hash=EXCLUDED.password_hash;
DELETE FROM admin_sessions a USING sessions s WHERE a.session_id=s.id AND s.user_id=:'target_id';
INSERT INTO admin_audit(actor_id,actor_email,user_id,action,operation_id,request_hash,reason,after_payload)
VALUES ('00000000-0000-0000-0000-000000000000','operator@localhost',:'target_id',
'set_admin_password',gen_random_uuid(),repeat('0',64),'Учётные данные админки заданы оператором сервера',
jsonb_build_object('username',:'username'));
SELECT username FROM admin_credentials WHERE user_id=:'target_id';
COMMIT;
SQL
