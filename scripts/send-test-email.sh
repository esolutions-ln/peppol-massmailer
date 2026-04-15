#!/bin/bash
#
# Test Script: Send a Test Email with PDF Invoice Attachment
# Usage: ./scripts/send-test-email.sh
#

set -e

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-your-org-api-key-here}"  # Replace with actual API key from your organization

echo "=========================================="
echo "Mass Mailer - Test Email Sender"
echo "=========================================="
echo "Base URL: $BASE_URL"
echo "API Key: $API_KEY"
echo ""

# Create a sample PDF file for testing
# In production, this would be your actual invoice PDF
SAMPLE_PDF_PATH="/tmp/test-invoice.pdf"
echo "Creating test PDF..."

# Create a minimal valid PDF for testing
cat > "$SAMPLE_PDF_PATH" << 'PDFFILE'
%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
endobj
4 0 obj
<< /Length 44 >>
stream
BT
/F1 24 Tf
50 700 Td
(Test Invoice) Tj
ET
endstream
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
xref
0 6
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
0000000288 00000 n
0000000383 00000 n
trailer
<< /Size 6 /Root 1 0 R >>
startxref
461
%%EOF
PDFFILE

echo "Test PDF created at: $SAMPLE_PDF_PATH"
echo ""

# Test 1: Send single invoice email
echo "=========================================="
echo "Test 1: Send Single Invoice Email"
echo "=========================================="
echo "Destination: test.recipient@example.com"
echo "Invoice: INV-TEST-001"
echo ""

curl -X POST "$BASE_URL/api/v1/mail/invoice" \
  -H "Content-Type: application/json" \
  -H "x-api-key: $API_KEY" \
  -d "{
    \"to\": \"test.recipient@example.com\",
    \"recipientName\": \"Test Recipient\",
    \"subject\": \"Your Test Invoice INV-TEST-001\",
    \"templateName\": \"invoice\",
    \"invoiceNumber\": \"INV-TEST-001\",
    \"invoiceDate\": \"$(date +%Y-%m-%d)\",
    \"dueDate\": \"$(date -d '+30 days' +%Y-%m-%d 2>/dev/null || date -v+30d +%Y-%m-%d)\",
    \"totalAmount\": 1250.00,
    \"vatAmount\": 187.50,
    \"currency\": \"USD\",
    \"fiscalDeviceSerialNumber\": \"FD-TEST-12345\",
    \"fiscalDayNumber\": \"42\",
    \"globalInvoiceCounter\": \"0001234\",
    \"verificationCode\": \"TEST-ABCD-1234\",
    \"pdfFilePath\": \"$SAMPLE_PDF_PATH\",
    \"pdfFileName\": \"INV-TEST-001.pdf\",
    \"variables\": {
      \"companyName\": \"eSolutions Test\",
      \"accountsEmail\": \"accounts@esolutions.co.zw\",
      \"companyAddress\": \"123 Test Avenue, Harare\"
    }
  }" | jq '.'

echo ""
echo "=========================================="
echo "Test 2: Send Campaign Email (Multiple Recipients)"
echo "=========================================="
echo "Recipients:"
echo "  - alice@example.com"
echo "  - bob@example.com"
echo ""

curl -X POST "$BASE_URL/api/v1/campaigns" \
  -H "Content-Type: application/json" \
  -H "x-api-key: $API_KEY" \
  -d "{
    \"name\": \"Test Campaign $(date +%Y%m%d-%H%M%S)\",
    \"subject\": \"Your Test Invoice from eSolutions\",
    \"templateName\": \"invoice\",
    \"templateVariables\": {
      \"companyName\": \"eSolutions Test\",
      \"accountsEmail\": \"accounts@esolutions.co.zw\",
      \"companyAddress\": \"123 Test Avenue, Harare\"
    },
    \"recipients\": [
      {
        \"email\": \"alice@example.com\",
        \"name\": \"Alice Test\",
        \"invoiceNumber\": \"INV-TEST-002\",
        \"invoiceDate\": \"$(date +%Y-%m-%d)\",
        \"dueDate\": \"$(date -d '+30 days' +%Y-%m-%d 2>/dev/null || date -v+30d +%Y-%m-%d)\",
        \"totalAmount\": 2400.00,
        \"vatAmount\": 360.00,
        \"currency\": \"USD\",
        \"fiscalDeviceSerialNumber\": \"FD-TEST-12345\",
        \"fiscalDayNumber\": \"43\",
        \"globalInvoiceCounter\": \"0001235\",
        \"verificationCode\": \"TEST-EFGH-5678\",
        \"pdfFilePath\": \"$SAMPLE_PDF_PATH\",
        \"pdfFileName\": \"INV-TEST-002.pdf\"
      },
      {
        \"email\": \"bob@example.com\",
        \"name\": \"Bob Test\",
        \"invoiceNumber\": \"INV-TEST-003\",
        \"invoiceDate\": \"$(date +%Y-%m-%d)\",
        \"dueDate\": \"$(date -d '+30 days' +%Y-%m-%d 2>/dev/null || date -v+30d +%Y-%m-%d)\",
        \"totalAmount\": 850.00,
        \"vatAmount\": 127.50,
        \"currency\": \"USD\",
        \"fiscalDeviceSerialNumber\": \"FD-TEST-12345\",
        \"fiscalDayNumber\": \"44\",
        \"globalInvoiceCounter\": \"0001236\",
        \"verificationCode\": \"TEST-IJKL-9012\",
        \"pdfFilePath\": \"$SAMPLE_PDF_PATH\",
        \"pdfFileName\": \"INV-TEST-003.pdf\"
      }
    ]
  }" | jq '.'

echo ""
echo "=========================================="
echo "Cleanup"
echo "=========================================="
rm -f "$SAMPLE_PDF_PATH"
echo "Test PDF removed."
echo ""
echo "=========================================="
echo "Tests Complete!"
echo "=========================================="
echo ""
echo "Check your email inbox at:"
echo "  - test.recipient@example.com"
echo "  - alice@example.com"
echo "  - bob@example.com"
echo ""
echo "Expected: Email with invoice PDF attachment"
echo "Invoice details should include:"
echo "  - Invoice number and dates"
echo "  - Amount breakdown (total, VAT)"
echo "  - Fiscal verification details"
echo "  - PDF attachment"
echo ""
