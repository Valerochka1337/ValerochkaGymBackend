#!/usr/bin/env bash
# Run as root from a staged copy of this repository on the existing Ubuntu server.
set -euo pipefail
[[ $(id -u) == 0 ]] || { echo 'Run with sudo' >&2; exit 1; }
command -v docker >/dev/null
docker compose version >/dev/null
command -v nginx >/dev/null
test -f /etc/letsencrypt/live/api.valerochkagym.tech/fullchain.pem
deploy_root=/opt/valerochkagym
install -d -m 0755 "$deploy_root"
install -d -m 0700 -o valerochka -g valerochka "$deploy_root/incoming"
install -m 0644 infra/compose.production.yaml "$deploy_root/compose.production.yaml"
install -m 0644 infra/nginx.conf "$deploy_root/nginx.conf"
for script in backup.sh verify-restore.sh deploy.sh install-and-deploy.sh; do install -m 0755 "scripts/$script" "$deploy_root/$script"; done
if [[ ! -f "$deploy_root/.env" ]]; then
  umask 077
  {
    printf 'DATABASE_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'TOKEN_PEPPER=%s\n' "$(openssl rand -hex 48)"
    printf '%s\n' 'BACKEND_IMAGE=valerochkagym-backend:mvp' 'GOOGLE_CLIENT_ID=193759830496-lucic3av1r1l4441ed54ap9e30fkolfl.apps.googleusercontent.com' 'MAIL_ENABLED=false' 'MAIL_FROM=noreply@valerochkagym.tech' 'SMTP_HOST=localhost' 'SMTP_PORT=587' 'SMTP_AUTH=true' 'SMTP_TLS=true'
  } > "$deploy_root/.env"
fi
install -m 0644 infra/valerochkagym-backup.service /etc/systemd/system/
install -m 0644 infra/valerochkagym-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now valerochkagym-backup.timer
printf 'Prepared %s; SMTP is disabled until credentials are configured.\n' "$deploy_root"
