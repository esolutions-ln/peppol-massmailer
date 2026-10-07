#!/usr/bin/env bash
# Smoke-test a running InvoiceDirect stack.
#   bash .claude/skills/smoke/smoke.sh            read-only checks
#   bash .claude/skills/smoke/smoke.sh --peppol   also POST a sample UBL invoice (writes to inbox)
# Override targets with BASE=... FRONT=... (defaults: backend :9199, nginx frontend :8199).
BASE=${BASE:-http://127.0.0.1:9199}
FRONT=${FRONT:-http://127.0.0.1:8199}
fail=0

check() { # label url expected-codes [extra curl args...]
  local label=$1 url=$2 want=$3; shift 3
  local code
  code=$(curl -sS -o /dev/null -m 10 -w '%{http_code}' "$@" "$url" 2>/dev/null)
  if [[ " $want " == *" $code "* ]]; then
    printf '  ok    %-34s %s\n' "$label" "$code"
  else
    printf '  FAIL  %-34s got %s, want %s\n' "$label" "${code:-000}" "$want"
    fail=1
  fi
}

echo "Backend  $BASE"
check "actuator health"               "$BASE/actuator/health"      "200"
check "peppol as4 health"             "$BASE/peppol/as4/health"    "200"
check "openapi spec"                  "$BASE/v3/api-docs"          "200"
check "swagger ui"                    "$BASE/swagger-ui.html"      "200 302"
check "organizations (no key)"        "$BASE/api/v1/organizations" "401 403"
check "organizations (bad key)"       "$BASE/api/v1/organizations" "401 403" -H "X-API-Key: deadbeef"

echo "Frontend $FRONT"
check "index"                         "$FRONT/"                    "200"
check "proxy -> organizations"        "$FRONT/api/v1/organizations" "401 403"

if [ "$1" = "--peppol" ]; then
  echo "PEPPOL receive"
  inv="SMOKE-INV-$(date +%s)"
  check "as4 receive ($inv)" "$BASE/peppol/as4/receive" "200 201 202" -X POST \
    -H 'Content-Type: application/xml' \
    -H 'X-PEPPOL-Sender-ID: 0190:ZW000000001' \
    -H 'X-PEPPOL-Receiver-ID: 0190:ZW999999999' \
    -H "X-Invoice-Number: $inv" \
    --data "<?xml version=\"1.0\"?><Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"><cbc:ID xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">$inv</cbc:ID></Invoice>"
fi

[ $fail -eq 0 ] && echo "All checks passed." || echo "Some checks failed."
exit $fail
