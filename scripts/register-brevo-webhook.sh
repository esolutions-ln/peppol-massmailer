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
  [ -r "$ENV_FILE" ] || { echo "ERROR: $ENV_FILE is not readable by $(id -un) — run with sudo"; exit 1; }
  echo "Using env file: $ENV_FILE"
  set -a; source "$ENV_FILE"; set +a
elif [ -z "${BREVO_API_KEY:-}" ]; then
  echo "ERROR: env file not found: $ENV_FILE"
  echo "       Run from the repo checkout, or point at it: ENV_FILE=/path/to/.env $0"
  exit 1
fi

# Tolerate .env files saved with Windows (CRLF) line endings.
BREVO_API_KEY="${BREVO_API_KEY:-}";             BREVO_API_KEY="${BREVO_API_KEY%$'\r'}"
BREVO_WEBHOOK_TOKEN="${BREVO_WEBHOOK_TOKEN:-}"; BREVO_WEBHOOK_TOKEN="${BREVO_WEBHOOK_TOKEN%$'\r'}"

URL="${1:-https://ap.invoicedirect.biz/webhooks/brevo/transactional}"
API="${BREVO_BASE_URL:-https://api.brevo.com/v3}"

[ -n "$BREVO_API_KEY" ]       || { echo "ERROR: BREVO_API_KEY not set in $ENV_FILE"; exit 1; }
[ -n "$BREVO_WEBHOOK_TOKEN" ] || { echo "ERROR: BREVO_WEBHOOK_TOKEN not set in $ENV_FILE (add: BREVO_WEBHOOK_TOKEN=\$(openssl rand -hex 32))"; exit 1; }

# brevo METHOD PATH [JSON] — prints the response body; on a non-2xx status shows
# Brevo's error message (e.g. 400 validation detail, 401 unauthorised IP) and exits.
brevo() {
  local method="$1" path="$2" data="${3:-}" out status body
  local args=(-sS -X "$method" "$API$path" -w $'\n%{http_code}'
              -H "accept: application/json" -H "api-key: $BREVO_API_KEY")
  [ -n "$data" ] && args+=(-H "content-type: application/json" --data "$data")
  out=$(curl "${args[@]}")
  status="${out##*$'\n'}"
  body="${out%$'\n'*}"
  if [ "${status:0:1}" != "2" ]; then
    echo "ERROR: Brevo $method $path returned HTTP $status:" >&2
    echo "$body" >&2
    exit 1
  fi
  printf '%s' "$body"
}

existing=$(brevo GET "/webhooks?type=transactional")
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

created=$(brevo POST "/webhooks" "$payload")
echo "Registered Brevo webhook → $URL  $created"
