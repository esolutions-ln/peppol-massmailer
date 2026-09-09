#!/usr/bin/env bash
set -euo pipefail

# Run from this script's directory (massmailer/) so the relative paths below
# — .env, pdf-watcher-common, ./inbox — resolve regardless of the caller's cwd.
cd "$(dirname "${BASH_SOURCE[0]}")"

# ─────────────────────────────────────────────────────────────────────────────
# InvoiceDirect Mass Mailer — Production Deployment Script
# Domain:   https://ap.invoicedirect.biz
# Backend:  127.0.0.1:9199 (internal only)
# Frontend: 127.0.0.1:8199 (internal only)
# Both are reverse-proxied and TLS-terminated by a host-level nginx — not part
# of this compose stack — whose certs are managed by its own certbot renewal.
# ─────────────────────────────────────────────────────────────────────────────

DOMAIN="ap.invoicedirect.biz"
# docker-compose.prod.yml points mass-mailer at the platform's existing host
# Postgres (via host.docker.internal) instead of the base file's own
# dockerized "postgres" service, which would otherwise try to bind the same
# host port 5432 that the real Postgres is already listening on.
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"

# Resolve mvn explicitly: this script may run over a non-interactive SSH
# session, where PATH additions from ~/.bashrc / ~/.profile (e.g. an
# sdkman/manual Maven install under /opt) are not sourced.
if command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  # "|| true" guards against `set -o pipefail`: find can exit non-zero (e.g.
  # permission-denied on some unrelated /opt subdirectory) even with stderr
  # suppressed, which would otherwise trip `set -e` right here regardless of
  # whether a path was actually found.
  MVN="$(find /opt -maxdepth 3 -type f -name mvn 2>/dev/null | head -1 || true)"
  if [ -z "${MVN}" ]; then
    echo "ERROR: mvn not found on PATH or under /opt."
    exit 1
  fi
fi

echo "================================================"
echo " InvoiceDirect Deployment"
echo " Domain: https://${DOMAIN}"
echo "================================================"

# ── Pre-flight checks ──────────────────────────────────────────────────────
if [ ! -f .env ]; then
  echo "ERROR: .env file not found. Copy .env.example and fill in your secrets."
  exit 1
fi

source .env

if [ -z "${ADMIN_PASSWORD:-}" ]; then
  echo "ERROR: ADMIN_PASSWORD must be set in .env"
  exit 1
fi

# ── Step 1: Build application JAR ────────────────────────────────────────────
echo ""
echo "[1/4] Building application..."

if [ ! -d pdf-watcher-common ]; then
  echo "ERROR: pdf-watcher-common/ directory not found."
  exit 1
fi

echo "  -> Installing pdf-watcher-common module..."
"${MVN}" install -f pdf-watcher-common/pom.xml -q

echo "  -> Building mass-mailer service..."
"${MVN}" clean package -DskipTests -q

# ── Step 2: Build Docker images ─────────────────────────────────────────────
echo ""
echo "[2/4] Building Docker images..."
$COMPOSE build

# ── Step 3: Start all services ──────────────────────────────────────────────
# TLS for ${DOMAIN} is terminated by a host-level nginx (not this compose
# stack) using certs at /etc/letsencrypt/live/${DOMAIN}/, managed by its own
# certbot renewal outside this repo. The dockerized "frontend"/"mass-mailer"
# services only bind to 127.0.0.1 (see docker-compose.prod.yml) and are
# reverse-proxied by that host nginx — there is nothing for this script to
# provision on ports 80/443, and doing so would conflict with host nginx
# already listening there.
#
# --no-deps + explicit service names: the base docker-compose.yml's "postgres"
# service is still merged in (docker-compose.prod.yml doesn't remove it), and
# its depends_on would otherwise try to start it — clashing with the real
# Postgres already listening on host port 5432.
echo ""
echo "[3/4] Starting all services..."
$COMPOSE up -d --no-deps mass-mailer frontend

# ── Step 4: Wait for health checks ─────────────────────────────────────────
echo ""
echo "[4/4] Waiting for services to be healthy..."

for i in $(seq 1 60); do
  status=$($COMPOSE ps --format '{{.Service}} {{.Status}}' | awk '$1=="mass-mailer"{$1=""; sub(/^ /,""); print}')
  if echo "$status" | grep -q "(healthy)"; then
    echo "  -> mass-mailer healthy"
    break
  fi
  echo "  ... waiting for mass-mailer (${i}/60): ${status:-not running}"
  sleep 5
done

echo ""
echo "================================================"
echo " Deployment complete!"
echo ""
echo " Frontend:  https://${DOMAIN}"
echo " API Docs:  https://${DOMAIN}/swagger-ui.html"
echo " Backend:   http://127.0.0.1:9199 (internal)"
echo " Health:    https://${DOMAIN}/actuator/health"
echo ""
echo " Admin:     ${ADMIN_USERNAME:-admin}"
echo "================================================"

$COMPOSE ps
