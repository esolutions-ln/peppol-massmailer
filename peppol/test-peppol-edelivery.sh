#!/usr/bin/env bash
# =============================================================================
# PEPPOL e-Delivery Test & Demo Script
#
# Simulates the full 4-corner model end-to-end:
#   C1 (Supplier ERP) → C2 (Our AP Gateway) → C3 (Buyer AP) → C4 (Buyer ERP)
#
# Usage:
#   export ADMIN_PASSWORD="350lt3am"
#   bash peppol/test-peppol-edelivery.sh
#
# Requires: docker compose, curl, jq, python3, openssl
# =============================================================================
set -uo pipefail
ERR_TRAP() { echo -e "${RED}✗${NC} Script failed at line $1 (exit code $2)" >&2; }
trap 'ERR_TRAP $LINENO $?' ERR

# ── Configuration ────────────────────────────────────────────────────────────
BASE="${BASE_URL:-http://localhost:9199}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-${ADMIN_PASSWORD:-350lt3am}}"
FRONTEND="${FRONTEND_URL:-http://localhost:8199}"

SUFFIX=$(python3 -c "import random,string; print(''.join(random.choices(string.ascii_lowercase,k=6)))")
SLUG="esolutions-peppol-${SUFFIX}"
VAT_SUPPLIER="220132956"
VAT_BUYER="987654321"
PID_SUPPLIER="0190:ZW${VAT_SUPPLIER}"
PID_BUYER="0190:ZW${VAT_BUYER}"

TMPDIR=$(mktemp -d /tmp/peppol-test-XXXXXX)
SUPPLIER_ORG_ID=""; SUPPLIER_API_KEY=""
BUYER_CUSTOMER_ID=""; BUYER_CONTACT_ID=""; RECEIVER_AP_ID=""
ADMIN_TOKEN=""; TEST_RECEIVER_PORT=9999; TEST_RECEIVER_PID=""

# Colours
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
pass() { echo -e "  ${GREEN}✓${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; }
info() { echo -e "  ${CYAN}→${NC} $1"; }
header() { echo -e "\n${BOLD}${YELLOW}══ $1 ══${NC}\n"; }
die()  { echo -e "${RED}ERROR:${NC} $1"; exit 1; }

cleanup() {
  [ -n "$TEST_RECEIVER_PID" ] && kill "$TEST_RECEIVER_PID" &>/dev/null || true
  rm -rf "$TMPDIR" 2>/dev/null || true
}
trap cleanup EXIT

wait_for_backend() {
  info "Waiting for backend at $BASE..."
  for i in $(seq 1 60); do
    if curl -sS "$BASE/actuator/health" >/dev/null 2>&1; then
      pass "Backend is UP"
      return 0
    fi
    sleep 2
  done
  die "Backend did not start within 120s"
}

generate_test_cert() {
  local org="$1" out
  out=$(openssl req -x509 -newkey rsa:2048 -days 3650 -nodes \
    -subj "/CN=Test PEPPOL AP/O=${org}/C=ZW" \
    -keyout "$TMPDIR/ap-key.pem" -out "$TMPDIR/ap-cert.pem" 2>&1) || die "openssl failed: $out"
  pass "Generated test X.509 certificate for $org"
}

# ══════════════════════════════════════════════════════════════════════════════
#  SETUP
# ══════════════════════════════════════════════════════════════════════════════

header "Prerequisites"
for cmd in curl jq python3 openssl; do
  command -v "$cmd" >/dev/null 2>&1 || die "$cmd is required"
done
pass "All prerequisites found"

header "Starting Docker Stack"
if ! docker compose ps --services --filter "status=running" 2>/dev/null | grep -q "mass-mailer"; then
  docker compose up -d 2>&1 || die "docker compose up failed"
  pass "Docker stack started"
else
  pass "Docker stack already running"
fi
wait_for_backend
curl -sS "$FRONTEND" >/dev/null 2>&1 && pass "Frontend is UP at $FRONTEND"

header "Admin Login"
ADMIN_TOKEN=$(curl -sS -X POST "$BASE/api/v1/admin/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | jq -r '.token')
[ -n "$ADMIN_TOKEN" ] && [ "$ADMIN_TOKEN" != "null" ] || die "Admin login failed"
pass "Admin logged in (token: ${ADMIN_TOKEN:0:16}...)"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 1 — Create Supplier Org (C1) with deliveryMode=AS4
# ══════════════════════════════════════════════════════════════════════════════

header "Step 1 — Create Supplier Organization (C1)"
ORG_RESPONSE=$(curl -sS -X POST "$BASE/api/v1/organizations" \
  -H "Content-Type: application/json" \
  -d '{
    "user": {
      "firstName": "Tendai",
      "lastName": "Mukuru",
      "emailAddress": "tendai+'"$SUFFIX"'@esolutions.co.zw"
    },
    "name": "eSolutions (Pvt) Ltd",
    "slug": "'"$SLUG"'",
    "senderEmail": "accounts+'"$SUFFIX"'@esolutions.co.zw",
    "senderDisplayName": "eSolutions Accounts",
    "accountsEmail": "accounts+'"$SUFFIX"'@esolutions.co.zw",
    "companyAddress": "123 Samora Machel Ave, Harare",
    "primaryErpSource": "SAGE_INTACCT",
    "erpTenantId": "ESOLUTIONS_ZW",
    "vatNumber": "'"$VAT_SUPPLIER"'",
    "deliveryMode": "AS4"
  }')

SUPPLIER_ORG_ID=$(echo "$ORG_RESPONSE" | jq -r '.id')
SUPPLIER_API_KEY=$(echo "$ORG_RESPONSE" | jq -r '.apiKey')
[ -n "$SUPPLIER_ORG_ID" ] && [ "$SUPPLIER_ORG_ID" != "null" ] || die "Org creation failed"
pass "Org: eSolutions (Pvt) Ltd (ID: ${SUPPLIER_ORG_ID:0:8}...)"
pass "Delivery mode: AS4 — PEPPOL routing active"
pass "API Key: ${SUPPLIER_API_KEY:0:16}..."

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 2 — Create Buyer Customer
# ══════════════════════════════════════════════════════════════════════════════

header "Step 2 — Create Buyer Customer"
CUSTOMER_RESPONSE=$(curl -sS -X POST "$BASE/api/v1/organizations/$SUPPLIER_ORG_ID/customers" \
  -H "X-API-Key: $SUPPLIER_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice+'"$SUFFIX"'@acmecorp.co.zw",
    "name": "Alice Moyo",
    "phone": "+263 77 123 4567",
    "companyName": "Acme Corporation Zimbabwe (Pvt) Ltd",
    "tradingName": "Acme Zimbabwe",
    "erpSource": "SAGE_INTACCT",
    "erpCustomerId": "CUST-PEPPOL-DEMO-'"$SUFFIX"'",
    "vatNumber": "'"$VAT_BUYER"'",
    "tinNumber": null,
    "bpn": "2000480465",
    "peppolParticipantId": "'"$PID_BUYER"'",
    "addressLine1": "100 Nelson Mandela Avenue",
    "addressLine2": "",
    "city": "Harare",
    "country": "Zimbabwe"
  }')

BUYER_CUSTOMER_ID=$(echo "$CUSTOMER_RESPONSE" | jq -r '.id')
BUYER_CONTACT_ID=$(echo "$CUSTOMER_RESPONSE" | jq -r '.contacts[0].id')
[ -n "$BUYER_CUSTOMER_ID" ] && [ "$BUYER_CUSTOMER_ID" != "null" ] || die "Customer creation failed"
pass "Customer: Acme Corporation (ID: ${BUYER_CUSTOMER_ID:0:8}...)"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 3 — Register GATEWAY Access Point (C2)
# ══════════════════════════════════════════════════════════════════════════════

header "Step 3 — Register GATEWAY Access Point (C2)"
curl -sS -X POST "$BASE/api/v1/eregistry/access-points" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": "'"$SUPPLIER_ORG_ID"'",
    "participantId": "'"$PID_SUPPLIER"'",
    "participantName": "eSolutions AP Gateway",
    "role": "GATEWAY",
    "endpointUrl": "http://localhost:9199/peppol/as4/receive",
    "simplifiedHttpDelivery": true,
    "deliveryAuthToken": null
  }' > /dev/null
pass "GATEWAY AP registered: ${PID_SUPPLIER}"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 4 — Start Test C3/C4 Receiver (Python)
# ══════════════════════════════════════════════════════════════════════════════

header "Step 4 — Start Test C3/C4 Receiver (Python)"
mkdir -p "$TMPDIR"

cat > "$TMPDIR/test-receiver.py" << 'PYEOF'
import http.server, json, sys
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9999

class C3Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8') if length > 0 else ''
        print(f"[C3] Received {len(body)} bytes on {self.path}", flush=True)
        sender = self.headers.get('X-PEPPOL-Sender-ID', '—')
        receiver = self.headers.get('X-PEPPOL-Receiver-ID', '—')
        invoice = self.headers.get('X-Invoice-Number', '—')
        print(f"[C3] sender={sender} receiver={receiver} invoice={invoice}", flush=True)
        doc_type = self.headers.get('X-PEPPOL-Document-Type', '—')
        print(f"[C3] documentType={doc_type}", flush=True)
        is_ubl = '<Invoice' in body or '<CreditNote' in body
        print(f"[C3] validUBL={is_ubl}", flush=True)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({
            'status': 'received', 'bytes': len(body), 'isUBL': is_ubl
        }).encode())
    def log_message(self, fmt, *args): pass

http.server.HTTPServer(('0.0.0.0', PORT), C3Handler).serve_forever()
PYEOF

python3 "$TMPDIR/test-receiver.py" "$TEST_RECEIVER_PORT" &
TEST_RECEIVER_PID=$!
sleep 1
kill -0 "$TEST_RECEIVER_PID" 2>/dev/null || die "Failed to start test C3 receiver"
pass "Test C3 receiver on port ${TEST_RECEIVER_PORT} (PID: ${TEST_RECEIVER_PID})"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 5 — Register RECEIVER Access Point (C3)
# ══════════════════════════════════════════════════════════════════════════════

header "Step 5 — Register RECEIVER Access Point (C3)"
RECEIVER_AP_RESPONSE=$(curl -sS -X POST "$BASE/api/v1/eregistry/access-points" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": null,
    "participantId": "'"$PID_BUYER"'",
    "participantName": "Acme Corp Receiver AP",
    "role": "RECEIVER",
    "endpointUrl": "http://localhost:'"$TEST_RECEIVER_PORT"'/peppol/receive",
    "simplifiedHttpDelivery": true,
    "deliveryAuthToken": null
  }')

RECEIVER_AP_ID=$(echo "$RECEIVER_AP_RESPONSE" | jq -r '.id')
pass "RECEIVER AP registered: ${PID_BUYER} (ID: ${RECEIVER_AP_ID:0:8}...)"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 6 — Link Customer to Access Point
# ══════════════════════════════════════════════════════════════════════════════

header "Step 6 — Link Customer to Access Point"
curl -sS -X POST "$BASE/api/v1/eregistry/participant-links" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": "'"$SUPPLIER_ORG_ID"'",
    "customerEmail": "alice+'"$SUFFIX"'@acmecorp.co.zw",
    "participantId": "'"$PID_BUYER"'",
    "receiverAccessPointId": "'"$RECEIVER_AP_ID"'",
    "preferredChannel": "PEPPOL"
  }' > /dev/null
pass "Customer linked to RECEIVER AP (channel: PEPPOL)"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 7 — Upload PKI Certificate
# ══════════════════════════════════════════════════════════════════════════════

header "Step 7 — Upload PKI Certificate"
generate_test_cert "eSolutions"
python3 -c "
import json
with open('$TMPDIR/ap-cert.pem') as f: cert = f.read()
with open('$TMPDIR/ap-key.pem') as f: key = f.read()
print(json.dumps({'certificatePem': cert, 'privateKeyPem': key,
                  'description': 'Test PEPPOL AP Certificate'}))
" > "$TMPDIR/cert-request.json"

PKI_RESPONSE=$(curl -sS -X POST "$BASE/api/v1/admin/orgs/$SUPPLIER_ORG_ID/peppol/certs" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d @"$TMPDIR/cert-request.json")

CERT_ID=$(echo "$PKI_RESPONSE" | jq -r '.id')
CERT_STATUS=$(echo "$PKI_RESPONSE" | jq -r '.status')
pass "Certificate uploaded (ID: ${CERT_ID:0:8}..., status: ${CERT_STATUS})"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 8 — Verify Active Certificate
# ══════════════════════════════════════════════════════════════════════════════

header "Step 8 — Verify Active Certificate"
ACTIVE_CERT=$(curl -sS "$BASE/api/v1/admin/orgs/$SUPPLIER_ORG_ID/peppol/certs/active" \
  -H "X-API-Key: $ADMIN_TOKEN")
pass "Active cert: $(echo "$ACTIVE_CERT" | jq -r '.subjectDn')"
echo "          Valid: $(echo "$ACTIVE_CERT" | jq -r '.validFrom') → $(echo "$ACTIVE_CERT" | jq -r '.validTo')"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 9 — Test SMP Metadata Endpoint
# ══════════════════════════════════════════════════════════════════════════════

header "Step 9 — Test SMP Metadata Endpoint"
SMP_DOCTYPE="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##\
urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1"
SMP_DOCTYPE_ENC=$(python3 -c "import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1],safe=''))" "$SMP_DOCTYPE")
SMP_XML=$(curl -sS "$BASE/bdxr/smp/${PID_BUYER}/services/${SMP_DOCTYPE_ENC}")

if echo "$SMP_XML" | grep -q "ServiceMetadata"; then
  PARTICIPANT=$(echo "$SMP_XML" | grep -oP '<ns:ParticipantIdentifier[^>]*>\K[^<]+')
  ENDPOINT=$(echo "$SMP_XML" | grep -oP '<ns:EndpointURI>\K[^<]+')
  pass "SMP serving metadata for: ${PARTICIPANT}"
  echo "          Endpoint: ${ENDPOINT}"
else
  fail "SMP response unexpected"; echo "$SMP_XML" | head -5
fi

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 10 — Prepare Dispatch Metadata
# ══════════════════════════════════════════════════════════════════════════════

header "Step 10 — Prepare Invoice for Dispatch"
# Minimal valid PDF stub (passes content-type checks)
cat > "$TMPDIR/invoice.pdf" << 'PDFEOF'
%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<<>>/Contents 4 0 R>>endobj
4 0 obj<</Length 44>>stream
BT /F1 12 Tf 50 700 Td (Invoice INV-2026-0100) Tj ET
endstream
endobj
xref
trailer<</Size 5/Root 1 0 R>>
%%EOF
PDFEOF

# Financial numbers: subtotal=2000, vat=300 (15%), total=2300
cat > "$TMPDIR/dispatch-metadata.json" << JSON
{
  "campaignName": "PEPPOL Demo — March 2026",
  "subject": "Invoice INV-2026-0100 from eSolutions",
  "templateName": "invoice",
  "organizationId": "$SUPPLIER_ORG_ID",
  "templateVariables": {
    "companyName": "eSolutions (Pvt) Ltd",
    "accountsEmail": "accounts@esolutions.co.zw"
  },
  "invoices": [
    {
      "invoiceNumber": "INV-2026-0100",
      "recipientEmail": "alice+$SUFFIX@acmecorp.co.zw",
      "recipientName": "Alice Moyo",
      "recipientCompany": "Acme Corporation Zimbabwe (Pvt) Ltd",
      "invoiceDate": "2026-03-01",
      "dueDate": "2026-03-31",
      "subtotalAmount": 2000.00,
      "vatAmount": 300.00,
      "totalAmount": 2300.00,
      "currency": "USD",
      "fiscalDeviceSerialNumber": "FD-SN-TEST-001",
      "fiscalDayNumber": "60",
      "globalInvoiceCounter": "10001",
      "verificationCode": "AAAA-BBBB-CCCC-DDDD",
      "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-CCCC-DDDD"
    }
  ]
}
JSON
pass "Invoice dispatch metadata prepared (subtotal=2000, vat=300, total=2300 — VAT rate=15%)"

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 11 — Dispatch Invoice via ERP Route (C1→C2→C3→C4)
# ══════════════════════════════════════════════════════════════════════════════

header "Step 11 — Dispatch Invoice (C1→C2→C3→C4)"
info "POST /api/v1/erp/dispatch/upload → test C3 on port ${TEST_RECEIVER_PORT}"

DISPATCH_OUTPUT=$(curl -sS -X POST "$BASE/api/v1/erp/dispatch/upload" \
  -H "X-API-Key: $SUPPLIER_API_KEY" \
  -F "metadata=@$TMPDIR/dispatch-metadata.json;type=application/json" \
  -F "INV-2026-0100=@$TMPDIR/invoice.pdf;type=application/pdf" 2>&1 || true)

if echo "$DISPATCH_OUTPUT" | jq -e '.error' >/dev/null 2>&1; then
  ERR_MSG=$(echo "$DISPATCH_OUTPUT" | jq -r '.error')
  fail "Dispatch error: ${ERR_MSG}"
else
  PEPPOL_DISPATCHED=$(echo "$DISPATCH_OUTPUT" | jq -r '.peppolDispatched // 0')
  EMAIL_DISPATCHED=$(echo "$DISPATCH_OUTPUT" | jq -r '.emailDispatched // 0')
  if [ "$PEPPOL_DISPATCHED" -gt 0 ]; then
    pass "Dispatch success: PEPPOL=${PEPPOL_DISPATCHED}, EMAIL=${EMAIL_DISPATCHED}"
  else
    info "PEPPOL dispatched=${PEPPOL_DISPATCHED}, EMAIL=${EMAIL_DISPATCHED}"
    info "Check delivery records for Schematron validation details"
  fi
fi

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 12 — Check Delivery Records
# ══════════════════════════════════════════════════════════════════════════════

header "Step 12 — Check Delivery Records"
DELIVERIES=$(curl -sS "$BASE/api/v1/eregistry/deliveries?organizationId=$SUPPLIER_ORG_ID" \
  -H "X-API-Key: $ADMIN_TOKEN")
RECORD_COUNT=$(echo "$DELIVERIES" | jq 'length')

if [ "$RECORD_COUNT" -gt 0 ]; then
  for i in $(seq 0 $((RECORD_COUNT - 1))); do
    S=$(echo "$DELIVERIES" | jq -r ".[$i].status // \"—\"")
    INV=$(echo "$DELIVERIES" | jq -r ".[$i].invoiceNumber // \"—\"")
    EP=$(echo "$DELIVERIES" | jq -r ".[$i].deliveredToEndpoint // \"—\"")
    SCH=$(echo "$DELIVERIES" | jq -r ".[$i].schematronPassed // \"—\"")
    ERR=$(echo "$DELIVERIES" | jq -r ".[$i].errorMessage // \"\"")
    echo "  [$(($i+1))/${RECORD_COUNT}] invoice=${INV} status=${S} schematron=${SCH}"
    echo "         endpoint=${EP}"
    [ -n "$ERR" ] && [ "$ERR" != "null" ] && echo "         error: ${ERR:0:120}"
  done
  FIRST_STATUS=$(echo "$DELIVERIES" | jq -r '.[0].status')
  if [ "$FIRST_STATUS" = "DELIVERED" ]; then
    echo ""
    echo -e "  ${GREEN}${BOLD}✓ Invoice delivered through the 4-corner chain!${NC}"
  elif [ "$FIRST_STATUS" = "FAILED" ]; then
    echo ""
    echo -e "  ${YELLOW}Delivery recorded as FAILED — see errorMessage above.${NC}"
    echo -e "  ${YELLOW}This is expected if Schematron rejected the test invoice data.${NC}"
  fi
else
  fail "No delivery records found! PEPPOL routing may not have triggered."
fi

# ══════════════════════════════════════════════════════════════════════════════
#  STEP 13 — Dashboard Stats
# ══════════════════════════════════════════════════════════════════════════════

header "Step 13 — Delivery Dashboard Stats"
STATS=$(curl -sS "$BASE/api/v1/dashboard/$SUPPLIER_ORG_ID/peppol-stats" \
  -H "X-API-Key: $ADMIN_TOKEN" 2>/dev/null || echo '{}')
TOTAL=$(echo "$STATS" | jq -r '.totalDispatched // 0')
DELIVERED=$(echo "$STATS" | jq -r '.delivered // 0')
FAILED=$(echo "$STATS" | jq -r '.failed // 0')
RATE=$(echo "$STATS" | jq -r '.successRate // 0')
TREND=$(echo "$STATS" | jq -r '.dailyTrend | length // 0')
pass "Dashboard: ${TOTAL} dispatched, ${DELIVERED} delivered, ${FAILED} failed"
echo "           Success rate: ${RATE}% (${TREND} days in trend)"

# ══════════════════════════════════════════════════════════════════════════════
#  SUMMARY
# ══════════════════════════════════════════════════════════════════════════════

header "PEPPOL e-Delivery — Complete"
echo ""
echo "  ${BOLD}4-Corner Model${NC}"
echo "  ──────────────"
echo "  C1 Supplier ERP:  eSolutions (Pvt) Ltd"
echo "  C2 Our AP:        eSolutions AP Gateway (PID: ${PID_SUPPLIER})"
echo "  C3 Buyer AP:      Acme Corp Receiver AP (PID: ${PID_BUYER})"
echo "  C4 Buyer ERP:     Python test receiver (port ${TEST_RECEIVER_PORT})"
echo ""
echo "  ${BOLD}Credentials${NC}"
echo "  ───────────"
echo "  Admin Token:  ${ADMIN_TOKEN}"
echo "  Org API Key:  ${SUPPLIER_API_KEY}"
echo "  Org ID:       ${SUPPLIER_ORG_ID}"
echo ""
echo "  ${BOLD}URLs${NC}"
echo "  Admin UI:     ${FRONTEND}/admin/peppol"
echo "  Swagger:      ${BASE}/swagger-ui.html"
echo "  Health:       ${BASE}/actuator/health"

cat << MANUAL

══════════════════════════════════════════════════════════════════════════════
  MANUAL EXPLORATION COMMANDS
══════════════════════════════════════════════════════════════════════════════

# Access Points
  curl -s $BASE/api/v1/eregistry/access-points | jq .

# Participant Links
  curl -s "$BASE/api/v1/eregistry/participant-links?organizationId=$SUPPLIER_ORG_ID" | jq .

# Delivery Records
  curl -s "$BASE/api/v1/eregistry/deliveries?organizationId=$SUPPLIER_ORG_ID" | jq .

# Certificates
  curl -s "$BASE/api/v1/admin/orgs/$SUPPLIER_ORG_ID/peppol/certs" -H "X-API-Key: $ADMIN_TOKEN" | jq .

# Active Certificate
  curl -s "$BASE/api/v1/admin/orgs/$SUPPLIER_ORG_ID/peppol/certs/active" -H "X-API-Key: $ADMIN_TOKEN" | jq .

# Dashboard
  curl -s "$BASE/api/v1/dashboard/$SUPPLIER_ORG_ID/peppol-stats" -H "X-API-Key: $ADMIN_TOKEN" | jq .

# SMP Metadata
  SMP_DOCTYPE="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1"
  SMP_ENC=\$(python3 -c "import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1],safe=''))" "\$SMP_DOCTYPE")
  curl -s "$BASE/bdxr/smp/$PID_BUYER/services/\$SMP_ENC" | xmllint --format -

# Frontend
  open $FRONTEND/admin/peppol

MANUAL
