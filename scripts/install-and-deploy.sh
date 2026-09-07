#!/usr/bin/env bash
# Root-owned entrypoint installed during bootstrap. The deployment key is privileged by design.
set -euo pipefail
cd /opt/valerochkagym
install -m 0644 incoming/compose.production.yaml compose.production.yaml
for script in deploy.sh backup.sh verify-restore.sh; do install -m 0755 "incoming/$script" "$script"; done
registry_config=$(mktemp -d)
trap 'rm -rf "$registry_config"' EXIT
export DOCKER_CONFIG="$registry_config"
docker login ghcr.io --username "${2:?Registry username required}" --password-stdin
./deploy.sh "${1:?Image digest required}"
