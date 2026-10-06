#!/usr/bin/env bash
# =============================================================================
# register-brevo-webhook.sh — registers the InvoiceDirect delivery-event webhook
# with Brevo (POST https://api.brevo.com/v3/webhooks, type "transactional").
#
# Reads BREVO_API_KEY and BREVO_WEBHOOK_TOKEN from massmailer/.env (or the
# environment). Brevo sends events with "Authorization: Bearer $BREVO_WEBHOOK_TOKEN".
# Skips creation if a webhook for the same URL already exists. Never prints secrets.
#
# Usage: ./scripts/register-brevo-webhook.sh [webhook_url]
#   default URL: https://ap.invoicedirect.biz/webhooks/brevo/transactional
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/massmailer/.env}"
if [ -f "$ENV_FILE" ]; then
  set -a; source "$ENV_FILE"; set +a
fi

URL="${1:-https://ap.invoicedirect.biz/webhooks/brevo/transactional}"
API="${BREVO_BASE_URL:-https://api.brevo.com/v3}"

[ -n "${BREVO_API_KEY:-}" ]       || { echo "ERROR: BREVO_API_KEY not set"; exit 1; }
[ -n "${BREVO_WEBHOOK_TOKEN:-}" ] || { echo "ERROR: BREVO_WEBHOOK_TOKEN not set (generate: openssl rand -hex 32)"; exit 1; }

existing=$(curl -fsS "$API/webhooks?type=transactional" \
  -H "accept: application/json" -H "api-key: $BREVO_API_KEY")
if echo "$existing" | grep -q "\"url\":\"$URL\""; then
  echo "A Brevo transactional webhook for $URL already exists — nothing to do."
  echo "(Delete it in Brevo → Transactional → Settings → Webhooks to re-register.)"
  exit 0
fi

payload=$(cat <<JSON
{
  "type": "transactional",
  "url": "$URL",
  "description": "InvoiceDirect delivery events",
  "events": ["delivered", "hardBounce", "softBounce", "blocked", "invalid",
             "deferred", "spam", "unsubscribed"],
  "auth": { "type": "bearer", "token": "$BREVO_WEBHOOK_TOKEN" }
}
JSON
)

curl -fsS -X POST "$API/webhooks" \
  -H "accept: application/json" -H "content-type: application/json" \
  -H "api-key: $BREVO_API_KEY" \
  --data "$payload"
echo
echo "Registered Brevo webhook → $URL"
