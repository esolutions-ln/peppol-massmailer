# Email Sending Quick Reference

## Single Invoice Email

Send one invoice with PDF attachment:

```bash
curl -X POST http://localhost:8080/api/v1/mail/invoice \
   -H "Content-Type: application/json" \
   -H "x-api-key: YOUR_ORG_API_KEY" \
   -d '{
     "to": "recipient@example.com",
     "recipientName": "John Doe",
     "subject": "Your Invoice INV-2026-0001",
     "templateName": "invoice",
     "invoiceNumber": "INV-2026-0001",
     "invoiceDate": "2026-03-25",
     "dueDate": "2026-04-24",
     "totalAmount": 1250.00,
     "vatAmount": 187.50,
     "currency": "USD",
     "fiscalDeviceSerialNumber": "FD-SN-12345",
     "fiscalDayNumber": "42",
     "globalInvoiceCounter": "0001234",
     "verificationCode": "ABCD-EFGH-1234",
     "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=ABCD-EFGH-1234",
     "pdfFilePath": "/path/to/invoice.pdf",
     "pdfFileName": "INV-2026-0001.pdf",
     "variables": {
       "companyName": "eSolutions",
       "accountsEmail": "accounts@esolutions.co.zw"
     }
   }'
```

## Campaign Email (Multiple Recipients)

Send invoices to multiple recipients:

```bash
curl -X POST http://localhost:8080/api/v1/campaigns \
   -H "Content-Type: application/json" \
   -H "x-api-key: YOUR_ORG_API_KEY" \
   -d '{
     "name": "March 2026 Invoices",
     "subject": "Your Invoice from eSolutions",
     "templateName": "invoice",
     "templateVariables": {
       "companyName": "eSolutions",
       "accountsEmail": "accounts@esolutions.co.zw"
     },
     "recipients": [
       {
         "email": "alice@example.com",
         "name": "Alice Moyo",
         "invoiceNumber": "INV-2026-0100",
         "invoiceDate": "2026-03-01",
         "dueDate": "2026-03-31",
         "totalAmount": 2400.00,
         "vatAmount": 360.00,
         "currency": "USD",
         "fiscalDeviceSerialNumber": "FD-SN-12345",
         "fiscalDayNumber": "60",
         "globalInvoiceCounter": "10001",
         "verificationCode": "AAAA-BBBB-1111",
         "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111",
         "pdfFilePath": "/path/to/INV-2026-0100.pdf",
         "pdfFileName": "INV-2026-0100.pdf"
       },
       {
         "email": "bob@example.com",
         "name": "Bob Chirwa",
         "invoiceNumber": "INV-2026-0101",
         "invoiceDate": "2026-03-01",
         "dueDate": "2026-03-31",
         "totalAmount": 850.00,
         "vatAmount": 127.50,
         "currency": "USD",
         "fiscalDeviceSerialNumber": "FD-SN-12345",
         "fiscalDayNumber": "60",
         "globalInvoiceCounter": "10002",
         "verificationCode": "CCCC-DDDD-2222",
         "pdfFilePath": "/path/to/INV-2026-0101.pdf",
         "pdfFileName": "INV-2026-0101.pdf"
       }
     ]
   }'
```

## Check Campaign Status

```bash
curl -X GET http://localhost:8080/api/v1/campaigns/{campaign-id} \
   -H "Content-Type: application/json" \
   -H "x-api-key: YOUR_ORG_API_KEY"
```

## Retry Failed Campaign

```bash
curl -X POST http://localhost:8080/api/v1/campaigns/{campaign-id}/retry \
   -H "Content-Type: application/json" \
   -H "x-api-key: YOUR_ORG_API_KEY"
```

## Response Examples

### Single Invoice Success
```json
{
   "status": "delivered",
   "recipient": "recipient@example.com",
   "invoiceNumber": "INV-2026-0001",
   "messageId": "<abc123@smtp.gmail.com>",
   "error": null,
   "retryable": false
}
```

### Campaign Success
```json
{
   "campaignId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
   "message": "Invoice campaign queued for dispatch",
   "recipientCount": 2
}
```

### Failure Response
```json
{
   "status": "failed",
   "recipient": "recipient@example.com",
   "invoiceNumber": "INV-2026-0001",
   "messageId": null,
   "error": "Could not connect to SMTP host",
   "retryable": true
}
```

## Required Fields

### Single Invoice
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `to` | string | Yes | Recipient email address |
| `subject` | string | Yes | Email subject line |
| `templateName` | string | Yes | Template name (e.g., "invoice") |
| `invoiceNumber` | string | Yes | Invoice identifier |
| `totalAmount` | number | Yes | Total invoice amount |
| `currency` | string | Yes | Currency code (USD, ZWG, etc.) |
| `pdfFilePath` | string | Either | Path to PDF file OR `pdfBase64` |
| `pdfBase64` | string | Either | Base64-encoded PDF OR `pdfFilePath` |

### Campaign
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Campaign name |
| `subject` | string | Yes | Email subject line |
| `templateName` | string | Yes | Template name |
| `recipients` | array | Yes | List of invoice recipients |
| `recipients[].email` | string | Yes | Recipient email |
| `recipients[].invoiceNumber` | string | Yes | Invoice number |
| `recipients[].pdfFilePath` | string | Either | PDF file path or `pdfBase64` |

## Optional Fiscal Fields (ZIMRA Compliance)

| Field | Description |
|-------|-------------|
| `fiscalDeviceSerialNumber` | Fiscal device serial number |
| `fiscalDayNumber` | Fiscal day counter |
| `globalInvoiceCounter` | Global invoice counter |
| `verificationCode` | Verification code |
| `qrCodeUrl` | QR code URL for fiscal verification |

## PDF Attachment Methods

### Method 1: File Path (Shared Filesystem)
```json
"pdfFilePath": "/var/lib/odoo/invoices/INV-2026-0001.pdf",
"pdfFileName": "INV-2026-0001.pdf"
```

### Method 2: Base64 (Remote Upload)
```json
"pdfBase64": "JVBERi0xLjQK...",
"pdfFileName": "INV-2026-0001.pdf"
```

## Running the Test Script

```bash
# Set required environment variables
export API_KEY="your-org-api-key"
export BASE_URL="http://localhost:8080"

# Run test
./scripts/send-test-email.sh
```

## Check Email Delivery

1. Check recipient inbox for email
2. Verify PDF attachment is present
3. Verify invoice details in email body
4. Check application logs for delivery confirmation:
   ```bash
   tail -f logs/spring.log | grep "Invoice"
   ```

Expected log:
```
✓ Invoice INV-2026-0001 sent to recipient@example.com [msgId=<abc123@smtp.gmail.com>, pdf=12345bytes]
```
