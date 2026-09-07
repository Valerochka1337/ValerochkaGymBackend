#!/usr/bin/env bash
set -euo pipefail
cd "${GYM_DEPLOY_DIR:-/opt/valerochkagym}"
umask 077
mkdir -p backups
backup_path="backups/gym-$(date -u +%Y%m%dT%H%M%S).dump"
docker compose --env-file .env -f compose.production.yaml exec -T postgres pg_dump -U gym -d gym -Fc > "$backup_path.partial"
test -s "$backup_path.partial"
mv "$backup_path.partial" "$backup_path"
# Optional off-host copy. Set GYM_BACKUP_REMOTE to a configured rclone destination.
if [[ -n "${GYM_BACKUP_REMOTE:-}" ]]; then rclone copyto "$backup_path" "$GYM_BACKUP_REMOTE/$(basename "$backup_path")"; fi
find backups -name 'gym-*.dump' -mtime +14 -delete
printf '%s\n' "$backup_path"
