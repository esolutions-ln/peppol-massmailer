#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# InvoiceDirect PDF Watcher Agent — Local End-to-End Test
# Uses pdf-watcher-agent (standalone) against the Docker-deployed mass-mailer.
#
# Prerequisites:
#   1. Docker + Docker Compose installed
#   2. .env file configured (copy from .env.example)
#   3. An organization registered on the API (create via Admin UI or curl)
#   4. The org's API key (32-char hex) available
# ─────────────────────────────────────────────────────────────────────────────

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
AGENT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE="${COMPOSE:-docker compose}"

echo "═══════════════════════════════════════════════════════════════"
echo "  InvoiceDirect PDF Watcher Agent — Local E2E Test"
echo "═══════════════════════════════════════════════════════════════"

# ── Config (override via env) ────────────────────────────────────────────────
API_BASE="${API_BASE:-http://localhost:9199}"
API_KEY="${API_KEY:-}"  # must be set by user or .env
ORG_ID="${ORG_ID:-}"    # must be set by user or .env
INBOX_DIR="${INBOX_DIR:-/tmp/invoicedirect-test/inbox}"
EMAILED_DIR="${EMAILED_DIR:-/tmp/invoicedirect-test/emailed}"
FAILED_DIR="${FAILED_DIR:-/tmp/invoicedirect-test/failed}"
LEDGER_FILE="${LEDGER_FILE:-/tmp/invoicedirect-test/ledger.json}"

# ── Step 1: Build the agent JAR ─────────────────────────────────────────────
echo ""
echo "[1/5] Building pdf-watcher-agent..."
cd "$AGENT_DIR"
mvn clean package -q -DskipTests
cd "$ROOT_DIR"
echo "  -> Agent JAR ready"

# ── Step 2: Build the main service ──────────────────────────────────────────
echo ""
echo "[2/5] Building mass-mailer service for Docker..."
echo "  -> Installing pdf-watcher-common..."
mvn install -f pdf-watcher-common/pom.xml -q
echo "  -> Packaging main service..."
mvn clean package -DskipTests -q
echo "  -> Main service JAR ready"

# ── Step 3: Start Docker services ───────────────────────────────────────────
echo ""
echo "[3/5] Starting Docker services..."
$COMPOSE up -d --build
echo "  -> Waiting for mass-mailer to become healthy..."

for i in $(seq 1 60); do
  if curl -sf "$API_BASE/actuator/health" > /dev/null 2>&1; then
    echo "  -> mass-mailer healthy (after ${i}s)"
    break
  fi
  sleep 2
done

# ── Step 4: Create test invoice files ───────────────────────────────────────
echo ""
echo "[4/5] Creating test invoice files in $INBOX_DIR..."
mkdir -p "$INBOX_DIR" "$EMAILED_DIR" "$FAILED_DIR"

TIMESTAMP=$(date +%s)
INVOICE_NUM="TEST-${TIMESTAMP}"
PDF_FILE="$INBOX_DIR/${INVOICE_NUM}.pdf"
SIDECAR_FILE="$INBOX_DIR/${INVOICE_NUM}.json"

# Minimal valid PDF (starts with %PDF-
echo -n -e '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[]/Count 0>>endobj\nxref\n0 3\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \ntrailer<</Size 3/Root 1 0 R>>\nstartxref\n131\n%%EOF' > "$PDF_FILE"
echo "  -> Created $PDF_FILE ($(wc -c < "$PDF_FILE") bytes)"

cat > "$SIDECAR_FILE" <<SIDECAR_EOF
{
  "organizationId": "${ORG_ID}",
  "campaignName": "Local E2E Test ${TIMESTAMP}",
  "subject": "Your Test Invoice ${INVOICE_NUM}",
  "templateName": "invoice",
  "invoiceNumber": "${INVOICE_NUM}",
  "recipientEmail": "test-buyer@example.com",
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
echo "  -> Created $SIDECAR_FILE"

# ── Step 5: Run the agent ───────────────────────────────────────────────────
echo ""
echo "[5/5] Starting pdf-watcher-agent..."
echo ""
echo "  Agent config:"
echo "    API:       ${API_BASE}"
echo "    API Key:   ${API_KEY:0:8}...${API_KEY: -8}"
echo "    Org ID:    ${ORG_ID}"
echo "    Inbox:     ${INBOX_DIR}"
echo "    Emailed:   ${EMAILED_DIR}"
echo "    Failed:    ${FAILED_DIR}"
echo "    Ledger:    ${LEDGER_FILE}"
echo ""

if [ -z "${API_KEY}" ] || [ -z "${ORG_ID}" ]; then
  echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  echo "  ERROR: API_KEY and ORG_ID must be set."
  echo ""
  echo "  1. Create an organization via the admin API:"
  echo "     curl -X POST ${API_BASE}/api/v1/organizations \\"
  echo "       -H 'Content-Type: application/json' \\"
  echo "       -d '{\"name\":\"Test Org\",\"slug\":\"test-org-${TIMESTAMP}\",\"senderEmail\":\"noreply@example.com\"}'"
  echo ""
  echo "  2. Or use an existing org API key from the database."
  echo "     Export them and re-run:"
  echo ""
  echo "     export API_KEY=<org-api-key>"
  echo "     export ORG_ID=<org-uuid>"
  echo "     $0"
  echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  exit 1
fi

# Create a temp watcher.properties for this run
WATCHER_PROPS=$(mktemp)
cat > "$WATCHER_PROPS" <<PROPS
api.base.url=${API_BASE}
api.key=${API_KEY}
organization.id=${ORG_ID}
inbox.directory=${INBOX_DIR}
emailed.directory=${EMAILED_DIR}
failed.directory=${FAILED_DIR}
ledger.file=${LEDGER_FILE}
sidecar.wait.ms=3000
api.connect.timeout.seconds=10
api.read.timeout.seconds=60
PROPS

echo "  Watching inbox for PDF files..."
echo "  Drop invoices into: ${INBOX_DIR}"
echo "  Press Ctrl+C to stop."
echo ""

AGENT_JAR=$(ls -t "$AGENT_DIR/target/pdf-watcher-agent-"*.jar | head -1)
java -Dwatcher.config="$WATCHER_PROPS" -jar "$AGENT_JAR"
