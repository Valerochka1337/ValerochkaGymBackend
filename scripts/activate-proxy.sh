#!/usr/bin/env bash
set -euo pipefail
[[ $(id -u) == 0 ]] || { echo 'Run with sudo' >&2; exit 1; }
cd /opt/valerochkagym
curl --fail --silent http://127.0.0.1:18080/actuator/health/readiness >/dev/null
target=$(readlink -f /etc/nginx/sites-enabled/api.valerochkagym.tech)
test -f "$target"
backup=$(mktemp /opt/valerochkagym/nginx-before-backend.XXXXXX)
cp "$target" "$backup"
install -m 0644 nginx.conf "$target"
if ! nginx -t; then cp "$backup" "$target";exit 1; fi
if ! systemctl reload nginx; then cp "$backup" "$target";nginx -t;systemctl reload nginx;exit 1; fi
curl --fail --silent https://api.valerochkagym.tech/health
