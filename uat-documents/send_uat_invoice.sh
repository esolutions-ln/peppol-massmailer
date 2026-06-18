#!/usr/bin/env bash
#
# UAT live-send helper.
# Generates a fresh ZIMRA-fiscalised PDF and POSTs it to the running backend,
# delivering to lucky.ncube@gmail.com from no-reply@invoicedirect.biz.
#
# Pre-req (one-time, in the Brevo dashboard):
#   Senders, Domains & IPs → Authorized IPs → add 77.246.50.229
#
# Run from the repo root:
#   ./uat-documents/send_uat_invoice.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

ADMIN_PASSWORD=$(grep ^ADMIN_PASSWORD= .env | cut -d= -f2-)
if [[ -z "${ADMIN_PASSWORD:-}" ]]; then
  echo "ERROR: ADMIN_PASSWORD not found in .env" >&2
  exit 1
fi

echo "▸ Generating fresh fiscal PDF…"
python3 uat-documents/generate_invoice.py

echo "▸ Authenticating as admin…"
TOKEN=$(curl -sS -X POST http://127.0.0.1:9199/api/v1/admin/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")

if [[ -z "${TOKEN:-}" ]]; then
  echo "ERROR: admin login failed" >&2
  exit 1
fi

echo "▸ Sending invoice to lucky.ncube@gmail.com…"
RESPONSE=$(curl -sS -X POST http://127.0.0.1:9199/api/v1/mail/invoice/upload \
  -H "X-API-Key: $TOKEN" \
  -F "pdf=@uat-documents/UAT-Invoice-INV-UAT-2026-0001.pdf;type=application/pdf" \
  -F "metadata=<uat-documents/metadata.json;type=application/json" \
  -w "\n__HTTP_STATUS__:%{http_code}")

STATUS="${RESPONSE##*__HTTP_STATUS__:}"
BODY="${RESPONSE%$'\n'__HTTP_STATUS__:*}"

echo
echo "HTTP $STATUS"
echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"

if [[ "$STATUS" == "200" ]]; then
  echo
  echo "✓ Delivered. Check the inbox at lucky.ncube@gmail.com."
  exit 0
else
  echo
  echo "✗ Delivery failed. Most likely cause: Brevo IP allowlist."
  echo "  Add 77.246.50.229 in Brevo → Senders, Domains & IPs → Authorized IPs."
  exit 1
fi
