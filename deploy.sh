#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# InvoiceDirect Mass Mailer — Production Deployment Script
# Domain:  https://ap.invoicedirect.biz
# Backend: localhost:9199 (not exposed to internet)
# Frontend: nginx reverse-proxy on ports 80/443
# ─────────────────────────────────────────────────────────────────────────────

DOMAIN="ap.invoicedirect.biz"
EMAIL="${CERTBOT_EMAIL:-admin@invoicedirect.biz}"
COMPOSE="docker compose"

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

# ── Step 1: Build all images ────────────────────────────────────────────────
echo ""
echo "[1/4] Building Docker images..."
$COMPOSE build

# ── Step 2: Initial SSL certificate (first-time only) ──────────────────────
SSL_CERT_DIR="./ssl-certs"
if [ ! -f "${SSL_CERT_DIR}/fullchain.pem" ]; then
  echo ""
  echo "[2/4] Obtaining SSL certificate from Let's Encrypt..."
  echo "      Make sure DNS for ${DOMAIN} points to this server."

  # Create a temporary nginx config that serves HTTP only (for ACME challenge)
  mkdir -p "${SSL_CERT_DIR}"

  # Generate a self-signed cert so nginx can start with the SSL config
  openssl req -x509 -nodes -days 1 -newkey rsa:2048 \
    -keyout "${SSL_CERT_DIR}/privkey.pem" \
    -out "${SSL_CERT_DIR}/fullchain.pem" \
    -subj "/CN=${DOMAIN}" 2>/dev/null

  echo "  -> Starting services with temporary self-signed cert..."
  $COMPOSE up -d frontend

  echo "  -> Requesting real certificate from Let's Encrypt..."
  docker run --rm \
    -v "$(pwd)/ssl-certs:/etc/letsencrypt" \
    -v "$(pwd)/certbot-webroot:/var/www/certbot" \
    -p 80:80 \
    certbot/certbot certonly \
      --standalone \
      --preferred-challenges http \
      -d "${DOMAIN}" \
      --email "${EMAIL}" \
      --agree-tos \
      --non-interactive

  # Copy the real certs to the expected location
  cp "./ssl-certs/live/${DOMAIN}/fullchain.pem" "${SSL_CERT_DIR}/fullchain.pem"
  cp "./ssl-certs/live/${DOMAIN}/privkey.pem" "${SSL_CERT_DIR}/privkey.pem"

  echo "  -> SSL certificate obtained successfully."
  $COMPOSE down
else
  echo ""
  echo "[2/4] SSL certificate already exists — skipping."
fi

# ── Step 3: Start all services ──────────────────────────────────────────────
echo ""
echo "[3/4] Starting all services..."
$COMPOSE up -d

# ── Step 4: Wait for health checks ─────────────────────────────────────────
echo ""
echo "[4/4] Waiting for services to be healthy..."

for i in $(seq 1 30); do
  if $COMPOSE ps | grep -q "(healthy)"; then
    break
  fi
  echo "  ... waiting (${i}/30)"
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
