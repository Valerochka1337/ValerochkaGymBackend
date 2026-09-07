#!/usr/bin/env bash
set -euo pipefail
cd "${GYM_DEPLOY_DIR:-/opt/valerochkagym}"
new_image="${1:?Pass an immutable image reference}"
[[ "$new_image" =~ ^ghcr.io/valerochka1337/valerochkagymbackend@sha256:[a-f0-9]{64}$ ]] || { echo 'Expected the backend GHCR image digest' >&2; exit 2; }
exec 9>.deploy.lock
flock -n 9 || { echo 'Deployment already running' >&2; exit 1; }
umask 077
env_backup=$(mktemp .env.rollback.XXXXXX)
cp .env "$env_backup"
cleanup() { rm -f "$env_backup" incoming/smtp.json; }
trap cleanup EXIT
compose=(docker compose --env-file .env -f compose.production.yaml)
old_image=$(sed -n 's/^BACKEND_IMAGE=//p' .env)
docker pull "$new_image"
if [[ -f incoming/admin-role.sh ]]; then install -m 0755 incoming/admin-role.sh admin-role.sh; fi
if [[ -f incoming/admin-password.sh ]]; then install -m 0755 incoming/admin-password.sh admin-password.sh; fi
if "${compose[@]}" ps --status running --services | grep -qx postgres; then ./backup.sh; fi
if [[ -f incoming/smtp.json ]]; then
  install -m 0755 incoming/smtp-config.py smtp-config.py
  python3 smtp-config.py apply incoming/smtp.json .env
  rm -f incoming/smtp.json
fi
set_image() {
  local value="$1"
  sed '/^BACKEND_IMAGE=/d' .env > .env.next
  printf 'BACKEND_IMAGE=%s\n' "$value" >> .env.next
  mv .env.next .env
}
set_image "$new_image"
if ! "${compose[@]}" up -d --wait --wait-timeout 180 || ! curl --fail --silent --retry 5 --retry-delay 3 https://api.valerochkagym.tech/health; then
  echo 'Deployment failed; restoring the previous application image and environment (database migrations are not reversed).' >&2
  mv "$env_backup" .env
  if [[ -n "$old_image" ]]; then "${compose[@]}" up -d --wait --wait-timeout 180; fi
  exit 1
fi
printf '%s\n' "$old_image" > previous-image
