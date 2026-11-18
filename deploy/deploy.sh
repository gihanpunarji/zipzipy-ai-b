#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env.production ]]; then
  echo 'Run python3 deploy/configure.py --host YOUR_HOST --email YOUR_EMAIL first.' >&2
  exit 1
fi
if [[ ! -f ../ContextIQ-frontend/Dockerfile ]]; then
  echo 'Place the frontend checkout in ../ContextIQ-frontend first.' >&2
  exit 1
fi
compose=(docker compose --env-file .env.production -f compose.production.yml)
"${compose[@]}" config --quiet
# Build before changing running services. Do not print rendered config: it contains secrets.
"${compose[@]}" build
"${compose[@]}" up -d --wait postgres ollama
# Read only model names, not secrets; do not source an executable .env file.
model=$(sed -n 's/^OLLAMA_MODEL=//p' .env.production)
embedding=$(sed -n 's/^OLLAMA_EMBEDDING_MODEL=//p' .env.production)
"${compose[@]}" exec -T ollama ollama pull "${model:-gemma4:e2b}"
"${compose[@]}" exec -T ollama ollama pull "${embedding:-nomic-embed-text:latest}"
"${compose[@]}" up -d backend web
host=$(sed -n 's/^PUBLIC_HOST=//p' .env.production)
echo "Waiting for public HTTPS health at https://${host}/api/v1/health"
for attempt in {1..60}; do
  if curl --fail --silent --show-error --max-time 10 "https://${host}/api/v1/health"; then
    echo
    echo "Deployment responds at https://${host}. Now run the acceptance checks in docs/deployment.md."
    exit 0
  fi
  sleep 5
done
echo 'Health check failed. Check DNS, ports 80/443, and backend/web logs. Do not delete volumes.' >&2
exit 1
