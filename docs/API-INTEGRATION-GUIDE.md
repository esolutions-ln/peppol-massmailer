# API Integration Guide

A step-by-step guide for developers integrating with the eSolutions Fiscalised Invoice Mass Mailer API.

## Base URLs

| Environment | URL |
|-------------|-----|
| Local Development | `http://localhost:8080` |
| Production | `https://mailer.invoicedirect.biz` |

## Interactive API Docs

| Resource | URL |
|----------|-----|
| Swagger UI | `{base}/swagger-ui.html` |
| OpenAPI JSON | `{base}/v3/api-docs` |
| OpenAPI YAML | `{base}/v3/api-docs.yaml` |

---

## Quick Start: Your First API Call in 5 Minutes

### Step 1 -- Register Your Organisation

```bash
curl -X POST https://mailer.invoicedirect.biz/api/v1/organizations \
  -H "Content-Type: application/json" \
  -d '{
    "user": {
      "firstName": "John",
      "lastName": "Doe",
      "emailAddress": "john@yourcompany.co.zw"
    },
    "name": "Your Company",
    "slug": "your-company",
    "senderEmail": "invoices@yourcompany.co.zw",
    "senderDisplayName": "Your Company Invoices",
    "accountsEmail": "accounts@yourcompany.co.zw",
    "deliveryMode": "EMAIL"
  }'
```

**Response** (201 Created):

```json
{
  "id": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "name": "Your Company",
  "slug": "your-company",
  "apiKey": "mm_live_abc123def456...",
  "peppolParticipantId": "0192:your-company",
  "deliveryMode": "EMAIL",
  "status": "ACTIVE"
}
```

> **Save your API key immediately.** It is shown only once on registration.

### Step 2 -- Send Your First Invoice

```bash
curl -X POST https://mailer.invoicedirect.biz/api/v1/mail/invoice \
  -H "Content-Type: application/json" \
  -H "X-API-Key: mm_live_abc123def456..." \
  -d '{
    "to": "customer@acmecorp.co.zw",
    "recipientName": "Acme Corporation",
    "subject": "Invoice INV-2026-0001 from Your Company",
    "templateName": "invoice",
    "invoiceNumber": "INV-2026-0001",
    "invoiceDate": "2026-04-14",
    "dueDate": "2026-05-14",
    "totalAmount": 1250.00,
    "vatAmount": 187.50,
    "currency": "USD",
    "pdfBase64": "<base64-encoded-pdf>",
    "pdfFileName": "INV-2026-0001.pdf",
    "variables": {
      "companyName": "Your Company",
      "accountsEmail": "accounts@yourcompany.co.zw"
    }
  }'
```

**Response** (200 OK):

```json
{
  "status": "delivered",
  "recipient": "customer@acmecorp.co.zw",
  "invoiceNumber": "INV-2026-0001",
  "messageId": "<abc123@smtp.gmail.com>",
  "retryable": false
}
```

### Step 3 -- Check Delivery

If you used a campaign instead of a single send:

```bash
curl https://mailer.invoicedirect.biz/api/v1/campaigns/{campaignId} \
  -H "X-API-Key: mm_live_abc123def456..."
```

---

## Authentication

All authenticated endpoints require the `X-API-Key` header.

```
X-API-Key: mm_live_abc123def456...
```

There are two types of API keys:

| Type | Obtained via | Used for |
|------|-------------|----------|
| **Organisation API Key** | `POST /api/v1/organizations` (on creation) | Organisation-scoped endpoints (`/api/v1/my/*`, campaigns, mail) |
| **Admin Session Token** | `POST /api/v1/admin/login` | Admin endpoints (`/api/v1/admin/*`, billing, org management) |

---

## PDF Attachment Modes

Every invoice endpoint supports three ways to attach the PDF:

| Mode | Field | When to use |
|------|-------|-------------|
| **File path** | `pdfFilePath` | PDF is on a shared filesystem accessible to the mailer |
| **Base64** | `pdfBase64` | PDF is embedded in the request (max ~15MB after encoding) |
| **Multipart upload** | `/invoice/upload` or `/erp/dispatch/upload` | PDF uploaded as a binary file part |

> Supply exactly **one** of `pdfFilePath` or `pdfBase64` per recipient. If using multipart, use the upload endpoint instead.

---

## Sending Patterns

### Single Invoice (Synchronous)

**Endpoint:** `POST /api/v1/mail/invoice`

Best for: real-time invoice dispatch from your app (e.g., user clicks "Send Invoice").

- Blocks until the email is sent (or fails).
- Returns delivery status immediately.

### Campaign (Asynchronous, Batch)

**Endpoint:** `POST /api/v1/campaigns`

Best for: batch dispatch of many invoices (e.g., end-of-month billing run).

- Returns `202 Accepted` immediately with a `campaignId`.
- Processes recipients in batches of 50 on virtual threads.
- Poll `GET /api/v1/campaigns/{id}` for progress.
- Retry failed recipients with `POST /api/v1/campaigns/{id}/retry`.

### ERP-Driven Dispatch

**Endpoint:** `POST /api/v1/erp/dispatch`

Best for: pulling invoices directly from your ERP system.

- Fetches invoices and PDFs from Sage Intacct, QuickBooks, Dynamics 365, or Odoo.
- Automatically registers customer contacts.
- Routes via email or PEPPOL AS4 based on customer preferences.

---

## Campaign Lifecycle

```
CREATED --> QUEUED --> IN_PROGRESS --> COMPLETED
                                  \-> PARTIALLY_FAILED
                                  \-> FAILED
                                  \-> CANCELLED
```

**Polling example:**

```bash
# Poll every 5 seconds until complete
while true; do
  STATUS=$(curl -s https://mailer.invoicedirect.biz/api/v1/campaigns/$CAMPAIGN_ID \
    -H "X-API-Key: $API_KEY" | jq -r '.status')
  echo "Status: $STATUS"
  case $STATUS in
    COMPLETED|PARTIALLY_FAILED|FAILED|CANCELLED) break ;;
  esac
  sleep 5
done
```

---

## Recipient Delivery Statuses

Each invoice recipient moves through these statuses:

| Status | Meaning |
|--------|---------|
| `PENDING` | Queued, not yet attempted |
| `SENT` / `DELIVERED` | Email delivered successfully |
| `FAILED` | Delivery failed (check `errorMessage`) |
| `SKIPPED` | Skipped (e.g., unsubscribed, duplicate, invalid email) |
| `BOUNCED` | Bounced after initial delivery |

---

## Rate Limiting

| Endpoint | Limit | Scope |
|----------|-------|-------|
| `POST /api/v1/organizations` | 10 requests per hour | Per IP address |
| All other endpoints | No hard rate limit | SMTP sending is internally throttled via semaphore |

When rate limited, you receive:

```json
HTTP/1.1 429 Too Many Requests

{
  "error": "Rate limit exceeded. Maximum 10 registrations per IP per hour."
}
```

**Recommended backoff strategy:** If you receive a `429`, wait 60 seconds before retrying. Use exponential backoff for repeated failures (60s, 120s, 240s).

**SMTP throttling:** The mailer internally limits concurrent SMTP connections using a `Semaphore` to avoid provider blacklisting. You do not need to throttle your API calls -- the system handles this automatically and retries failed sends up to 3 times with exponential backoff (2s, 4s, 8s).

---

## Webhook Events (Inbound Only)

The platform currently supports **inbound webhooks** for receiving status updates from external systems:

### Sage Network E-Invoice Status

```
POST /webhooks/sage/einvoice-status/{orgId}
```

Receives push notifications from Sage Network about e-invoice delivery status changes.

### Campaign Events (Internal)

Campaign lifecycle events are emitted internally via Spring's `ApplicationEventPublisher`:

| Event | Description |
|-------|-------------|
| `Started` | Campaign dispatch has begun |
| `BatchCompleted` | A batch of recipients has been processed |
| `Completed` | All recipients have been processed |
| `Failed` | Campaign failed entirely |

> These events are consumed internally for logging. **Outbound webhook delivery** (pushing events to your system) is not yet implemented. Poll `GET /api/v1/campaigns/{id}` for status updates.

---

## Error Handling

All errors follow a standard format:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Recipient email is required",
  "path": "/api/v1/mail/invoice"
}
```

### Error Codes Reference

| HTTP Status | Meaning | Action |
|-------------|---------|--------|
| `400` | Invalid request | Fix the request body and retry |
| `403` | Forbidden | Check your API key or PEPPOL participant ID |
| `404` | Not found | Verify the resource ID/path |
| `409` | Conflict | Resource already exists (e.g., duplicate slug) |
| `429` | Rate limited | Back off and retry after 60 seconds |
| `502` | Bad Gateway | SMTP or ERP is unreachable; retry with backoff |
| `503` | Service Unavailable | ERP system is down; retry later |

### Retryable vs Non-Retryable Errors

The `SingleMailResponse` includes a `retryable` boolean:

```json
{
  "status": "failed",
  "error": "Connection refused: smtp.gmail.com:587",
  "retryable": true
}
```

- **retryable: true** -- transient failure (SMTP timeout, ERP unavailable). Retry with exponential backoff.
- **retryable: false** -- permanent failure (invalid email, PDF not found). Fix the input before retrying.

---

## ZIMRA Fiscal Fields

If your invoices are fiscalised under Zimbabwe's ZIMRA regulations, include these fields:

| Field | Description | Example |
|-------|-------------|---------|
| `fiscalDeviceSerialNumber` | Fiscal device serial number | `FD-SN-12345` |
| `fiscalDayNumber` | Fiscal day number | `60` |
| `globalInvoiceCounter` | Global invoice counter | `10001` |
| `verificationCode` | ZIMRA verification code | `AAAA-BBBB-1111` |
| `qrCodeUrl` | ZIMRA QR code verification URL | `https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111` |

These fields are optional but recommended for compliance. When `fiscal-validation-enabled: true` is set on the server, missing fiscal fields on invoices will be logged as warnings.

---

## ERP Adapter Matrix

| ERP Source | Identifier | Invoice Fetch | PDF Resolution |
|------------|-----------|---------------|----------------|
| Sage Intacct | `SAGE_INTACCT` | XML API (ARINVOICE) | Export directory |
| QuickBooks Online | `QUICKBOOKS_ONLINE` | REST API | `/invoice/{id}/pdf` |
| Dynamics 365 | `DYNAMICS_365` | OData (SalesInvoice) | Document Management |
| Odoo | `ODOO` | JSON-RPC (account.move) | `/report/pdf/account.report_invoice/{id}` |
| Generic | `GENERIC_API` | N/A (use campaigns endpoint) | N/A |

**Check adapter availability:**

```bash
curl https://mailer.invoicedirect.biz/api/v1/erp/adapters \
  -H "X-API-Key: $API_KEY"
```

**Check ERP health:**

```bash
curl "https://mailer.invoicedirect.biz/api/v1/erp/health/SAGE_INTACCT?tenantId=MY_TENANT" \
  -H "X-API-Key: $API_KEY"
```

---

## Billing Integration

### Understanding Your Bill

Billing is usage-based with tiered pricing:

1. Each delivered invoice counts as one billable unit.
2. Failed and skipped invoices are **not** billed.
3. Costs are calculated against your assigned rate profile.

### Checking Your Current Bill

```bash
# Current period billing summary
curl https://mailer.invoicedirect.biz/api/v1/my/billing \
  -H "X-API-Key: $API_KEY"

# Specific period
curl "https://mailer.invoicedirect.biz/api/v1/my/billing?period=2026-03" \
  -H "X-API-Key: $API_KEY"

# Full billing history
curl https://mailer.invoicedirect.biz/api/v1/my/billing/history \
  -H "X-API-Key: $API_KEY"
```

### Billing Period Lifecycle

```
OPEN --> CLOSED --> INVOICED --> PAID
```

| Status | Meaning |
|--------|---------|
| `OPEN` | Current period, still accepting usage |
| `CLOSED` | Period ended, tally finalised |
| `INVOICED` | Platform invoice dispatched |
| `PAID` | Payment received |

---

## Pagination

> **Note:** List endpoints currently return **all records** without pagination. For organisations with large volumes, consider:
>
> - Using the `status` filter on `GET /api/v1/my/campaigns?status=FAILED` and `GET /api/v1/my/invoices?status=PENDING` to narrow results.
> - Polling individual campaigns via `GET /api/v1/campaigns/{id}` instead of listing all.
>
> Pagination (page/size query parameters) is planned for a future release.

---

## Multipart File Upload

For endpoints that accept PDF uploads, use `multipart/form-data`:

### Single Invoice Upload

```bash
curl -X POST https://mailer.invoicedirect.biz/api/v1/mail/invoice/upload \
  -H "X-API-Key: $API_KEY" \
  -F "pdf=@/path/to/invoice.pdf" \
  -F 'metadata={
    "to": "customer@acme.co.zw",
    "subject": "Your Invoice",
    "templateName": "invoice",
    "invoiceNumber": "INV-2026-0042",
    "totalAmount": 1250.00,
    "currency": "USD"
  }'
```

### Batch Upload (Multiple PDFs)

```bash
curl -X POST https://mailer.invoicedirect.biz/api/v1/erp/dispatch/upload \
  -H "X-API-Key: $API_KEY" \
  -F 'metadata={
    "campaignName": "March 2026",
    "subject": "Your Invoice",
    "templateName": "invoice",
    "organizationId": "d4f7a2c1-...",
    "invoices": [
      {"invoiceNumber": "INV-001", "recipientEmail": "a@acme.co.zw", "totalAmount": 100, "currency": "USD"},
      {"invoiceNumber": "INV-002", "recipientEmail": "b@acme.co.zw", "totalAmount": 200, "currency": "USD"}
    ]
  }' \
  -F "INV-001=@/path/to/INV-001.pdf" \
  -F "INV-002=@/path/to/INV-002.pdf"
```

**Limits:**
- Max file size: 20 MB per file
- Max request size: 25 MB total

---

## PEPPOL E-Invoicing

For organisations with `deliveryMode: "AS4"` or `"BOTH"`, invoices are delivered over the PEPPOL network using AS4 protocol.

### How It Works

1. Register your organisation with a `vatNumber` or `tinNumber`.
2. The system derives a PEPPOL participant ID automatically.
3. Register your customers with their PEPPOL participant IDs.
4. When dispatching, the system routes to PEPPOL or email based on the customer's `deliveryMode`.

### Inviting Customers to PEPPOL

```bash
# Send invitation email
curl -X POST https://mailer.invoicedirect.biz/api/v1/my/invitations \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"customerEmail": "buyer@customer.co.zw"}'

# List pending invitations
curl https://mailer.invoicedirect.biz/api/v1/my/invitations \
  -H "X-API-Key: $API_KEY"
```

---

## SDK / Client Libraries

No official SDKs are provided yet. You can generate client libraries from the OpenAPI spec:

```bash
# Download the spec
curl http://localhost:8080/v3/api-docs -o openapi.json

# Generate a Python client
npx @openapitools/openapi-generator-cli generate \
  -i openapi.json -g python -o ./sdk/python

# Generate a TypeScript client
npx @openapitools/openapi-generator-cli generate \
  -i openapi.json -g typescript-axios -o ./sdk/typescript

# Generate a Java client
npx @openapitools/openapi-generator-cli generate \
  -i openapi.json -g java -o ./sdk/java
```

---

## Support

- **Swagger UI:** `https://mailer.invoicedirect.biz/swagger-ui.html`
- **API Status:** `GET /peppol/as4/health`
- **Contact:** See your organisation's registered contact details
