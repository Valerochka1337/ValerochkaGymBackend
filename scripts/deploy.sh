#!/usr/bin/env bash
set -euo pipefail
cd "${GYM_DEPLOY_DIR:-/opt/valerochkagym}"
new_image="${1:?Pass an immutable image reference}"
[[ "$new_image" =~ ^ghcr.io/valerochka1337/valerochkagymbackend@sha256:[a-f0-9]{64}$ ]] || { echo 'Expected the backend GHCR image digest' >&2; exit 2; }
exec 9>.deploy.lock
flock -n 9 || { echo 'Deployment already running' >&2; exit 1; }
umask 077
compose=(docker compose --env-file .env -f compose.production.yaml)
old_image=$(sed -n 's/^BACKEND_IMAGE=//p' .env)
docker pull "$new_image"
if "${compose[@]}" ps --status running --services | grep -qx postgres; then ./backup.sh; fi
set_image() {
  local value="$1"
  sed '/^BACKEND_IMAGE=/d' .env > .env.next
  printf 'BACKEND_IMAGE=%s\n' "$value" >> .env.next
  mv .env.next .env
}
set_image "$new_image"
if ! "${compose[@]}" up -d --wait --wait-timeout 180 || ! curl --fail --silent --retry 5 --retry-delay 3 https://api.valerochkagym.tech/health; then
  echo 'Deployment failed; restoring the previous application image (database migrations are not reversed).' >&2
  if [[ -n "$old_image" ]]; then set_image "$old_image"; "${compose[@]}" up -d --wait --wait-timeout 180; fi
  exit 1
fi
printf '%s\n' "$old_image" > previous-image
