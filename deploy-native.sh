#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# InvoiceDirect Mass Mailer — Native (bare-metal) Deployment Script
# Backend:  systemd service on port 9199
# Frontend: nginx serving static files + reverse proxy to backend
# ─────────────────────────────────────────────────────────────────────────────

DOMAIN="ap.invoicedirect.biz"
APP_USER="mailer"
APP_DIR="/opt/massmailer"
FRONTEND_DIR="/var/www/massmailer"
SERVICE_NAME="massmailer"
JAR_NAME="mass-mailer.jar"
CREDENTIALS_PATH="/etc/mailer/google-oauth-credentials.json"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Pre-flight ─────────────────────────────────────────────────────────────
[ "$(id -u)" -eq 0 ] || error "Run this script as root (sudo ./deploy-native.sh)"
[ -f .env ] || error ".env file not found. Copy .env.example and fill in your secrets."

source .env

[ -n "${ADMIN_PASSWORD:-}" ] || error "ADMIN_PASSWORD must be set in .env"
[ -n "${DB_PASS:-}" ]        || error "DB_PASS must be set in .env"

info "Checking required tools..."
for cmd in java mvn node npm nginx psql; do
  command -v "$cmd" &>/dev/null || error "'$cmd' is not installed. See README for prerequisites."
done

echo ""
echo "================================================"
echo " InvoiceDirect Native Deployment"
echo " Domain: https://${DOMAIN}"
echo "================================================"


# ── Step 1: Build backend JAR ──────────────────────────────────────────────
info "[1/6] Building backend JAR..."
mvn package -DskipTests -B -q
JAR_PATH=$(ls target/*.jar | head -1)
[ -f "$JAR_PATH" ] || error "JAR not found after build."
info "Built: $JAR_PATH"

# ── Step 2: Build frontend ─────────────────────────────────────────────────
info "[2/6] Building frontend..."
(cd frontend && npm ci --silent && npm run build)
[ -d frontend/dist ] || error "Frontend dist not found after build."

# ── Step 3: Create system user and directories ─────────────────────────────
info "[3/6] Setting up system user and directories..."

id "$APP_USER" &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin "$APP_USER"

mkdir -p "$APP_DIR" "$FRONTEND_DIR" /etc/mailer /var/log/massmailer

cp "$JAR_PATH" "$APP_DIR/$JAR_NAME"

if [ -f google-oauth-credentials.json ]; then
  cp google-oauth-credentials.json "$CREDENTIALS_PATH"
  chmod 600 "$CREDENTIALS_PATH"
  chown "$APP_USER:$APP_USER" "$CREDENTIALS_PATH"
fi

cp -r frontend/dist/. "$FRONTEND_DIR/"

chown -R "$APP_USER:$APP_USER" "$APP_DIR" /var/log/massmailer
chmod 750 "$APP_DIR"

# ── Step 4: Write environment file ────────────────────────────────────────
info "[4/6] Writing environment config..."

cat > /etc/massmailer.env <<EOF
DB_URL=jdbc:postgresql://${DB_HOST:-localhost}:${DB_PORT:-5432}/massmailer
DB_USER=${DB_USER:-mailer}
DB_PASS=${DB_PASS}
SMTP_HOST=${SMTP_HOST:-smtp.gmail.com}
SMTP_PORT=${SMTP_PORT:-587}
SMTP_USERNAME=${SMTP_USERNAME:-}
SMTP_PASSWORD=${SMTP_PASSWORD:-}
MAIL_FROM=${MAIL_FROM:-}
MAIL_FROM_NAME=${MAIL_FROM_NAME:-eSolutions}
APP_BASE_URL=${APP_BASE_URL:-https://${DOMAIN}}
RATE_LIMIT=${RATE_LIMIT:-10}
BATCH_SIZE=${BATCH_SIZE:-50}
ADMIN_USERNAME=${ADMIN_USERNAME:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD}
GOOGLE_OAUTH2_CREDENTIALS_PATH=${CREDENTIALS_PATH}
GOOGLE_OAUTH2_REFRESH_TOKEN=${GOOGLE_OAUTH2_REFRESH_TOKEN:-}
EOF

chmod 600 /etc/massmailer.env
chown "$APP_USER:$APP_USER" /etc/massmailer.env


# ── Step 5: Install systemd service ───────────────────────────────────────
info "[5/6] Installing systemd service..."

cat > /etc/systemd/system/${SERVICE_NAME}.service <<UNIT
[Unit]
Description=InvoiceDirect Mass Mailer Service
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=${APP_USER}
WorkingDirectory=${APP_DIR}
EnvironmentFile=/etc/massmailer.env
ExecStart=java --enable-preview -XX:+UseZGC -Xmx512m -jar ${APP_DIR}/${JAR_NAME}
Restart=on-failure
RestartSec=10
StandardOutput=append:/var/log/massmailer/app.log
StandardError=append:/var/log/massmailer/app.log
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/var/log/massmailer /etc/mailer

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"


# ── Step 6: Configure nginx ────────────────────────────────────────────────
info "[6/6] Configuring nginx..."

NGINX_CONF=/etc/nginx/sites-available/massmailer

cat > "$NGINX_CONF" <<NGINX
server {
    listen 80;
    server_name ${DOMAIN};
    return 301 https://\$host\$request_uri;
}

server {
    listen 443 ssl;
    server_name ${DOMAIN};

    ssl_certificate     /etc/letsencrypt/live/${DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${DOMAIN}/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    root ${FRONTEND_DIR};
    index index.html;

    location /api/ {
        proxy_pass         http://127.0.0.1:9199/api/;
        proxy_set_header   Host \$host;
        proxy_set_header   X-Real-IP \$remote_addr;
        proxy_set_header   X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout    120s;
        proxy_send_timeout    60s;
    }

    location /peppol/ {
        proxy_pass http://127.0.0.1:9199/peppol/;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location /webhooks/ {
        proxy_pass http://127.0.0.1:9199/webhooks/;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location /swagger-ui/ {
        proxy_pass http://127.0.0.1:9199/swagger-ui/;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location /v3/api-docs {
        proxy_pass http://127.0.0.1:9199/v3/api-docs;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location /actuator/ {
        proxy_pass http://127.0.0.1:9199/actuator/;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }
}
NGINX

# Enable site
ln -sf "$NGINX_CONF" /etc/nginx/sites-enabled/massmailer
rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true

nginx -t || error "nginx config test failed"
systemctl reload nginx

# ── Done ───────────────────────────────────────────────────────────────────
echo ""
echo "================================================"
echo " Deployment complete!"
echo ""
echo " Frontend:  https://${DOMAIN}"
echo " API Docs:  https://${DOMAIN}/swagger-ui.html"
echo " Health:    https://${DOMAIN}/actuator/health"
echo " Logs:      journalctl -u ${SERVICE_NAME} -f"
echo "            tail -f /var/log/massmailer/app.log"
echo "================================================"

systemctl status "$SERVICE_NAME" --no-pager
