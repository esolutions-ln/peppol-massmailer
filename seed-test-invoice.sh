#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# Generates test invoice PDF + sidecar in the Docker inbox mount.
# Drop these into ./inbox/ and the watcher will pick them up.
#
# Usage:
#   ./pdf-watcher-agent/seed-test-invoice.sh <org-uuid>
# ─────────────────────────────────────────────────────────────────────────────

if [ $# -lt 1 ]; then
  echo "Usage: $0 <organization-uuid>"
  echo ""
  echo "Creates a test invoice PDF + sidecar in ./inbox/"
  echo "The in-process watcher (or standalone agent) will pick it up."
  exit 1
fi

ORG_ID="$1"
INBOX_DIR="$(cd "$(dirname "$0")/.." && pwd)/inbox"
mkdir -p "$INBOX_DIR"

TIMESTAMP=$(date +%s)
INVOICE_NUM="INV-TEST-${TIMESTAMP}"
PDF_FILE="${INBOX_DIR}/${INVOICE_NUM}.pdf"
SIDECAR_FILE="${INBOX_DIR}/${INVOICE_NUM}.json"

cat > "$PDF_FILE" <<'PDFEOF'
%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[]/Count 0>>endobj
xref
0 3
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
trailer<</Size 3/Root 1 0 R>>
startxref
131
%%EOF
PDFEOF

cat > "$SIDECAR_FILE" <<SIDECAR_EOF
{
  "organizationId": "${ORG_ID}",
  "campaignName": "Test Invoice ${TIMESTAMP}",
  "subject": "Your Invoice ${INVOICE_NUM}",
  "templateName": "invoice",
  "invoiceNumber": "${INVOICE_NUM}",
  "recipientEmail": "buyer@example.com",
  "recipientName": "Test Buyer",
  "recipientCompany": "Test Company (Pvt) Ltd",
  "invoiceDate": "$(date +%Y-%m-%d)",
  "dueDate": "$(date -v+30d +%Y-%m-%d 2>/dev/null || date -d '+30 days' +%Y-%m-%d 2>/dev/null || echo $(date +%Y-%m-%d))",
  "totalAmount": 2400.00,
  "vatAmount": 360.00,
  "currency": "USD",
  "fiscalDeviceSerialNumber": "FD-TEST-001",
  "fiscalDayNumber": "1",
  "globalInvoiceCounter": "1",
  "verificationCode": "TEST-AAAA-BBBB-1111",
  "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=TEST-AAAA-BBBB-1111",
  "templateVariables": {
    "companyName": "Test Company",
    "accountsEmail": "accounts@testcompany.co.zw"
  }
}
SIDECAR_EOF

echo "Created test invoice:"
echo "  PDF:     ${PDF_FILE} ($(wc -c < "$PDF_FILE") bytes)"
echo "  Sidecar: ${SIDECAR_FILE}"
echo ""
echo "Drop these into the Docker inbox at: ${INBOX_DIR}"
echo "The in-process watcher will pick them up automatically."
echo ""
echo "To poll for the dispatched campaign:"
echo "  curl -s http://localhost:9199/api/v1/organizations/me/campaigns | jq '.[0]'"
