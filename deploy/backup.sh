#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
ENV_FILE="$PROJECT_DIR/.env"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_DIR="$PROJECT_DIR/runtime/backups"
BACKUP_FILE="$BACKUP_DIR/cloudlicense-$STAMP.tar.gz"
BACKEND_STOPPED=false

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Deploy CloudLicense before creating a backup."
  exit 1
fi

restore_backend() {
  if [[ "$BACKEND_STOPPED" == true ]]; then
    docker compose --env-file "$ENV_FILE" start backend >/dev/null
  fi
}
trap restore_backend EXIT

cd "$PROJECT_DIR"
mkdir -p "$BACKUP_DIR"
docker compose --env-file "$ENV_FILE" stop backend
BACKEND_STOPPED=true
tar -czf "$BACKUP_FILE" runtime/data runtime/storage
docker compose --env-file "$ENV_FILE" start backend >/dev/null
BACKEND_STOPPED=false

echo "Backup created: $BACKUP_FILE"
