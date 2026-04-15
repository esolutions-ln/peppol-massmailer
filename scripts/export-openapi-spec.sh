#!/usr/bin/env bash
# ============================================================
# export-openapi-spec.sh
#
# Exports the OpenAPI specification from the running Mass Mailer
# service and saves it to the repository for offline reference.
#
# Usage:
#   ./scripts/export-openapi-spec.sh              # defaults to localhost:8080
#   ./scripts/export-openapi-spec.sh https://mailer.invoicedirect.biz
# ============================================================

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
OUTPUT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "Exporting OpenAPI spec from ${BASE_URL}..."

# JSON spec
curl -sf "${BASE_URL}/v3/api-docs" | python3 -m json.tool > "${OUTPUT_DIR}/openapi.json"
echo "  Saved: openapi.json"

# YAML spec
curl -sf "${BASE_URL}/v3/api-docs.yaml" > "${OUTPUT_DIR}/openapi.yaml"
echo "  Saved: openapi.yaml"

echo ""
echo "Done. You can now:"
echo "  - Import openapi.json into Postman (File > Import)"
echo "  - Generate client SDKs with openapi-generator-cli"
echo "  - View offline with: npx @redocly/cli preview-docs openapi.json"
