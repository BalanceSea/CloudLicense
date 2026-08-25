#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
ENV_FILE="$PROJECT_DIR/.env"
usage() {
  echo "Usage: sudo bash deploy/deploy.sh"
}

if [[ $# -ne 0 ]]; then
  usage
  exit 2
fi

if [[ $(id -u) -ne 0 ]]; then
  echo "Run this script with sudo so it can prepare persistent directory permissions."
  exit 1
fi

command -v docker >/dev/null 2>&1 || {
  echo "Docker Engine is not installed. Install Docker Engine and the Compose plugin first."
  exit 1
}
docker compose version >/dev/null 2>&1 || {
  echo "The Docker Compose plugin is not available."
  exit 1
}

random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
  fi
}

cd "$PROJECT_DIR"
umask 077
if [[ ! -f "$ENV_FILE" ]]; then
  cat > "$ENV_FILE" <<EOF
CLOUDLICENSE_SITE_ADDRESS=:80
CLOUDLICENSE_PUBLIC_ORIGIN=http://localhost:5173
CLOUDLICENSE_ADMIN_KEY=$(random_hex)
CLOUDLICENSE_LICENSE_PEPPER=$(random_hex)
CLOUDLICENSE_DB_PASSWORD=$(random_hex)
CLOUDLICENSE_DB_NAME=cloudlicense
CLOUDLICENSE_DB_USER=cloudlicense
CLOUDLICENSE_VERIFY_RATE_LIMIT=120
CLOUDLICENSE_TRUST_FORWARDED_FOR=false
CLOUDLICENSE_API_PORT=2345
CLOUDLICENSE_WEB_HTTP_PORT=5173
CLOUDLICENSE_WEB_HTTPS_PORT=5443
EOF
  echo "Created .env with generated secrets (values are not printed)."
else
  echo "Using existing .env; existing secrets and port settings are preserved."
fi

install -d -m 0750 runtime/storage runtime/backups
chown -R 10001:10001 runtime/storage

docker compose --env-file "$ENV_FILE" config --quiet
docker compose --env-file "$ENV_FILE" build --pull
docker compose --env-file "$ENV_FILE" up -d --remove-orphans

echo "Waiting for the backend health check..."
for attempt in $(seq 1 60); do
  if docker compose --env-file "$ENV_FILE" exec -T backend \
      curl --fail --silent http://127.0.0.1:8080/api/v1/public/plugins >/dev/null 2>&1; then
    docker compose --env-file "$ENV_FILE" ps
    echo "CloudLicense web: http://<server-ip>:5173/"
    echo "User center: http://<server-ip>:5173/download.html"
    echo "API: http://<server-ip>:2345/"
    echo "Logs: cd '$PROJECT_DIR' && docker compose logs -f --tail=200"
    exit 0
  fi
  sleep 2
done

echo "Deployment did not become healthy within 120 seconds."
docker compose --env-file "$ENV_FILE" ps
docker compose --env-file "$ENV_FILE" logs --tail=200 backend
exit 1
