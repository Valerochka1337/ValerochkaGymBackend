#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != check && "${1:-}" != apply ]]; then
  echo 'Usage: migrate-catalog.sh check|apply [--gym.catalog.source=UUID] [--gym.catalog.actor=UUID --gym.catalog.reason=TEXT --gym.catalog.backup-confirmed=true --gym.catalog.client-ready=true]' >&2
  exit 2
fi
mode="$1"
shift
exec java -jar "${GYM_JAR:-build/libs/app.jar}" --server.port=0 "--gym.catalog.migration=$mode" "$@"
