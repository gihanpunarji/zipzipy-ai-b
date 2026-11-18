#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
mkdir -p backups
file="backups/contextiq-$(date -u +%Y%m%dT%H%M%SZ)-$$.dump"
docker compose --env-file .env.production -f compose.production.yml exec -T postgres \
  pg_dump -U contextiq -d contextiq -Fc > "${file}.partial"
mv "${file}.partial" "$file"
echo "Backup saved to $file. Copy it off this VM; it contains private application data."
