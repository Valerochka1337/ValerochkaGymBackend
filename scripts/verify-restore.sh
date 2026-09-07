#!/usr/bin/env bash
# Restore into a disposable database; never overwrites the production database.
set -euo pipefail
cd "${GYM_DEPLOY_DIR:-/opt/valerochkagym}"
dump_path="${1:?Pass the backup dump to verify}"
test -s "$dump_path"
restore_db="gym_restore_$(date +%s)_$$"
compose=(docker compose --env-file .env -f compose.production.yaml)
cleanup() { "${compose[@]}" exec -T postgres dropdb -U gym --if-exists "$restore_db"; }
trap cleanup EXIT
"${compose[@]}" exec -T postgres createdb -U gym "$restore_db"
"${compose[@]}" exec -T postgres pg_restore -U gym -d "$restore_db" --exit-on-error --no-owner < "$dump_path"
"${compose[@]}" exec -T postgres psql -U gym -d "$restore_db" -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS migrations FROM databasechangelog; SELECT count(*) AS users FROM users; SELECT count(*) AS records FROM records;'
