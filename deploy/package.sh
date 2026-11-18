#!/usr/bin/env bash
# Bundle current source (including uncommitted changes) without local secrets or build output.
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ -d ContextIQ-frontend ]] || { echo 'Sibling frontend checkout is missing.' >&2; exit 1; }
umask 077
archive="${TMPDIR:-/tmp}/contextiq-source-$(date -u +%Y%m%dT%H%M%SZ)-$$.tar.gz"
tar --exclude='.git' --exclude='.env*' --exclude='node_modules' --exclude='target' \
    --exclude='dist' --exclude='backups' --exclude='.idea' --exclude='*.log' \
    --exclude='*.pem' --exclude='*.key' --exclude='*.tsbuildinfo' \
    -czf "$archive" ContextIQ ContextIQ-frontend
printf 'Source bundle: %s\n' "$archive"
