#!/usr/bin/env bash
# docker compose with the production files and profile, from anywhere. Arguments pass through:
#   deploy/compose.sh ps
#   deploy/compose.sh logs -f api-gateway
set -euo pipefail
cd "$(dirname "$0")/.."
exec docker compose -f docker-compose.yml -f deploy/compose.prod.yml --profile services "$@"
