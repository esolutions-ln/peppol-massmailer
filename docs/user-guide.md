# InvoiceDirect User Guide

This guide walks you through registering your organisation on InvoiceDirect, configuring your account, and dispatching fiscalised invoices to your customers — whether from an ERP system or directly as PDFs.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Step 1 — Register Your Organisation](#2-step-1--register-your-organisation)
3. [Step 2 — Save Your API Key](#3-step-2--save-your-api-key)
4. [Step 3 — Add Your Customers (Optional)](#4-step-3--add-your-customers-optional)
5. [Step 4 — Send Invoices](#5-step-4--send-invoices)
   - [Option A: Batch PDF Upload (Recommended)](#option-a-batch-pdf-upload-recommended)
   - [Option B: Single Invoice (Real-Time)](#option-b-single-invoice-real-time)
   - [Option C: ERP Pull Integration](#option-c-erp-pull-integration)
6. [Step 5 — Track Delivery Status](#6-step-5--track-delivery-status)
7. [Step 6 — Retry Failed Deliveries](#7-step-6--retry-failed-deliveries)
8. [Step 7 — Billing & Usage](#8-step-7--billing--usage)
9. [Step 8 — PEPPOL e-Invoice Delivery (Optional)](#9-step-8--peppol-e-invoice-delivery-optional)
10. [ERP Integration Reference](#10-erp-integration-reference)
11. [API Authentication Reference](#11-api-authentication-reference)
12. [Email Template Variables Reference](#12-email-template-variables-reference)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Overview

InvoiceDirect is a multi-tenant invoice delivery platform for businesses operating under Zimbabwe's ZIMRA fiscal regulations. It lets you:

- Send ZIMRA-compliant fiscalised invoice PDFs to customers by email
- Integrate with your ERP (Odoo, Sage Intacct, QuickBooks Online, Dynamics 365) to pull and dispatch invoices automatically
- Deliver invoices directly via the PEPPOL e-invoice network (BIS 3.0)
- Track delivery status and retry failures per campaign

**Base URL:** `https://ap.invoicedirect.biz`

**API documentation:** `https://ap.invoicedirect.biz/swagger-ui.html`

---

## 2. Step 1 — Register Your Organisation

You can register via the web portal or directly via the API.

### Via the web portal

1. Go to `https://ap.invoicedirect.biz/register`
2. Complete the 3-step registration wizard:
   - **Step 1 — Organisation details:** Company name, slug (URL-safe identifier), sender email address, VAT/TIN number
   - **Step 2 — PEPPOL setup:** Confirm participant ID (auto-derived from your VAT/TIN) and preferred delivery mode
   - **Step 3 — Done:** Your API key is displayed once — copy it immediately

### Via the API

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/organizations \
  -H "Content-Type: application/json" \
  -d '{
    "user": {
      "firstName": "Jane",
      "lastName": "Moyo",
      "jobTitle": "Finance Manager",
      "emailAddress": "jane.moyo@acmeholdings.co.zw"
    },
    "name": "Acme Holdings (Pvt) Ltd",
    "slug": "acme-holdings",
    "senderEmail": "noreply@acmeholdings.co.zw",
    "senderDisplayName": "Acme Holdings Accounts",
    "accountsEmail": "accounts@acmeholdings.co.zw",
    "companyAddress": "45 Borrowdale Road, Harare, Zimbabwe",
    "primaryErpSource": "ODOO",
    "vatNumber": "V123456789",
    "tinNumber": "2001234567",
    "deliveryMode": "EMAIL"
  }'
```

**Successful response (HTTP 201):**

```json
{
  "id": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "name": "Acme Holdings (Pvt) Ltd",
  "slug": "acme-holdings",
  "apiKey": "9ca22fce40834c6c897bcf32c89b369c",
  "peppolParticipantId": "0190:ZWV123456789",
  "deliveryMode": "EMAIL",
  "status": "ACTIVE"
}
```

**`primaryErpSource` values:** `ODOO`, `SAGE_INTACCT`, `QUICKBOOKS_ONLINE`, `DYNAMICS_365`, `GENERIC_API`

**`deliveryMode` values:**

| Value | Behaviour |
|-------|-----------|
| `EMAIL` | Invoices delivered as PDF email attachments |
| `AS4` | Invoices delivered via PEPPOL BIS 3.0 only |
| `BOTH` | Invoices delivered via both email and PEPPOL |

---

## 3. Step 2 — Save Your API Key

> **Your API key is shown only once and cannot be retrieved again.** Store it securely (e.g. in a secrets manager, a `.env` file, or your ERP's credential store). If lost, contact your platform administrator to issue a new key.

All authenticated API calls require this header:

```
X-API-Key: 9ca22fce40834c6c897bcf32c89b369c
```

---

## 4. Step 3 — Add Your Customers (Optional)

Customers are automatically registered the first time you dispatch an invoice to their email address. Pre-registration is optional but useful if you want to configure delivery preferences (e.g. PEPPOL) before the first dispatch.

### Pre-register a customer

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/organizations/{orgId}/customers \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "finance@clientcompany.co.zw",
    "name": "Tinashe Dube",
    "companyName": "Client Company Ltd",
    "erpSource": "ODOO"
  }'
```

### Look up an existing customer

```bash
GET /api/v1/organizations/{orgId}/customers/by-email?email=finance@clientcompany.co.zw
```

### List all customers

```bash
GET /api/v1/organizations/{orgId}/customers
```

---

## 5. Step 4 — Send Invoices

There are three ways to dispatch invoices. Choose the one that matches how your organisation generates invoices.

---

### Option A: Batch PDF Upload (Recommended)

Use this when your ERP or accounting system can export invoice PDFs to a folder or your application can provide them directly. This is the most flexible option and works with any system.

**Endpoint:** `POST /api/v1/erp/dispatch/upload`
**Content-Type:** `multipart/form-data`
**Authentication:** `X-API-Key`

The request has two parts:
- `metadata` — a JSON string describing the campaign and all invoices
- One file part per invoice, where the part name matches the `invoiceNumber`

**Example:**

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/erp/dispatch/upload \
  -H "X-API-Key: your-api-key" \
  -F 'metadata={
    "campaignName": "March 2026 Invoices",
    "subject": "Your Invoice from Acme Holdings",
    "templateName": "invoice",
    "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
    "templateVariables": {
      "companyName": "Acme Holdings (Pvt) Ltd",
      "accountsEmail": "accounts@acmeholdings.co.zw",
      "companyAddress": "45 Borrowdale Road, Harare, Zimbabwe"
    },
    "invoices": [
      {
        "invoiceNumber": "INV-2026-0100",
        "recipientEmail": "finance@clientcompany.co.zw",
        "recipientName": "Tinashe Dube",
        "recipientCompany": "Client Company Ltd",
        "invoiceDate": "2026-03-01",
        "dueDate": "2026-03-31",
        "totalAmount": 2400.00,
        "vatAmount": 360.00,
        "currency": "USD",
        "fiscalDeviceSerialNumber": "FD-SN-12345",
        "fiscalDayNumber": "60",
        "globalInvoiceCounter": "10001",
        "verificationCode": "AAAA-BBBB-1111",
        "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111"
      },
      {
        "invoiceNumber": "INV-2026-0101",
        "recipientEmail": "accounts@anotherco.co.zw",
        "recipientName": "Rudo Chikwanda",
        "recipientCompany": "Another Co Ltd",
        "invoiceDate": "2026-03-01",
        "dueDate": "2026-03-31",
        "totalAmount": 800.00,
        "vatAmount": 120.00,
        "currency": "USD",
        "fiscalDeviceSerialNumber": "FD-SN-12345",
        "fiscalDayNumber": "61",
        "globalInvoiceCounter": "10002",
        "verificationCode": "CCCC-DDDD-2222",
        "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=CCCC-DDDD-2222"
      }
    ]
  }' \
  -F "INV-2026-0100=@/path/to/INV-2026-0100.pdf" \
  -F "INV-2026-0101=@/path/to/INV-2026-0101.pdf"
```

**Successful response (HTTP 202):**

```json
{
  "totalInvoices": 2,
  "peppolDispatched": 0,
  "emailDispatched": 2,
  "emailCampaignId": "a0879a37-d4ce-40a5-aec5-38c64b087678",
  "message": "0 via PEPPOL BIS 3.0, 2 via email PDF"
}
```

Save the `emailCampaignId` — you will use it to poll status (see Step 5).

**What happens after submission:**

1. All PDF parts are validated against the invoice list in `metadata`
2. Customers are auto-registered (or updated) in your customer registry
3. Each invoice is routed to the correct delivery channel (email or PEPPOL)
4. A campaign is created and dispatching begins asynchronously on the server
5. Invoices are sent in batches of 50, with automatic rate limiting and retry on transient SMTP failures

---

### Option B: Single Invoice (Real-Time)

Use this for point-of-sale or real-time dispatch immediately after posting an invoice. The call is synchronous — it waits for delivery and returns the result.

**Endpoint:** `POST /api/v1/mail/invoice`
**Content-Type:** `application/json`
**Authentication:** Not required

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/mail/invoice \
  -H "Content-Type: application/json" \
  -d '{
    "to": "customer@acmecorp.co.zw",
    "recipientName": "Acme Corporation",
    "subject": "Invoice INV-2026-0042 from eSolutions",
    "templateName": "invoice",
    "invoiceNumber": "INV-2026-0042",
    "invoiceDate": "2026-03-23",
    "dueDate": "2026-04-22",
    "totalAmount": 1250.00,
    "vatAmount": 187.50,
    "currency": "USD",
    "fiscalDeviceSerialNumber": "FD-SN-12345",
    "fiscalDayNumber": "42",
    "globalInvoiceCounter": "0001234",
    "verificationCode": "ABCD-EFGH-1234",
    "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=ABCD-EFGH-1234",
    "pdfFilePath": "/var/lib/odoo/invoices/INV-2026-0042.pdf",
    "variables": {
      "companyName": "eSolutions",
      "accountsEmail": "accounts@esolutions.co.zw"
    }
  }'
```

If the PDF is not accessible via a file path on the server, upload it directly:

**Endpoint:** `POST /api/v1/mail/invoice/upload`
**Content-Type:** `multipart/form-data`

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/mail/invoice/upload \
  -F "pdf=@/path/to/INV-2026-0042.pdf" \
  -F 'metadata={
    "to": "customer@acme.co.zw",
    "recipientName": "Acme Corporation",
    "subject": "Your Invoice",
    "templateName": "invoice",
    "invoiceNumber": "INV-2026-0042",
    "totalAmount": 1250.00,
    "currency": "USD",
    "variables": { "companyName": "eSolutions" }
  }'
```

**Successful response (HTTP 200):**

```json
{
  "status": "delivered",
  "recipient": "customer@acmecorp.co.zw",
  "invoiceNumber": "INV-2026-0042",
  "messageId": "<abc123@smtp.gmail.com>",
  "error": null,
  "retryable": false
}
```

> **Note:** Single invoice dispatches are not tracked in the campaign dashboard. Use Option A for batch tracking and retry capabilities.

---

### Option C: ERP Pull Integration

Use this when InvoiceDirect should fetch invoice data and PDFs directly from your ERP. The platform uses your ERP's API to pull each invoice by its native ID, then dispatches it.

**Endpoint:** `POST /api/v1/erp/dispatch`
**Content-Type:** `application/json`
**Authentication:** `X-API-Key`

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/erp/dispatch \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "campaignName": "March 2026 Invoices",
    "subject": "Your Invoice from Acme Holdings",
    "templateName": "invoice",
    "erpSource": "ODOO",
    "tenantId": "acme-holdings",
    "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
    "invoiceIds": ["INV/2026/0042", "INV/2026/0043", "INV/2026/0044"],
    "templateVariables": {
      "companyName": "Acme Holdings (Pvt) Ltd",
      "accountsEmail": "accounts@acmeholdings.co.zw"
    }
  }'
```

**Supported ERP systems and their invoice ID formats:**

| `erpSource` | System | Invoice ID format | Example |
|-------------|--------|-------------------|---------|
| `ODOO` | Odoo 14+ | Odoo document name | `INV/2026/0042` |
| `SAGE_INTACCT` | Sage Intacct | Document number | `INV-2026-0042` |
| `QUICKBOOKS_ONLINE` | QuickBooks Online | Numeric ID | `1045` |
| `DYNAMICS_365` | Dynamics 365 F&O / BC | Invoice number | `FML-INV-001` |
| `GENERIC_API` | Not applicable | Use Option A instead | — |

For ERP-specific configuration requirements (credentials, base URLs, shared PDF directories), see [Section 10 — ERP Integration Reference](#10-erp-integration-reference).

---

## 6. Step 5 — Track Delivery Status

### Get campaign status

```bash
GET /api/v1/campaigns/{campaignId}
```

**Response:**

```json
{
  "id": "a0879a37-d4ce-40a5-aec5-38c64b087678",
  "name": "March 2026 Invoices",
  "status": "COMPLETED",
  "totalRecipients": 45,
  "sent": 44,
  "failed": 1,
  "skipped": 0,
  "createdAt": "2026-03-26T08:00:00Z",
  "startedAt": "2026-03-26T08:00:01Z",
  "completedAt": "2026-03-26T08:02:30Z"
}
```

**Campaign status values:**

| Status | Meaning |
|--------|---------|
| `CREATED` | Campaign created, not yet queued |
| `QUEUED` | Awaiting dispatch |
| `IN_PROGRESS` | Actively sending |
| `COMPLETED` | All invoices delivered successfully |
| `PARTIALLY_FAILED` | Some invoices failed — retry is available |
| `FAILED` | All invoices failed |

**Recommended polling interval:** every 5–10 seconds while `IN_PROGRESS`, then once when status changes.

### List all campaigns

```bash
GET /api/v1/campaigns
```

Returns all campaigns for your organisation, ordered newest first.

---

## 7. Step 6 — Retry Failed Deliveries

If a campaign ends in `PARTIALLY_FAILED` or `FAILED`, trigger a retry for all failed recipients:

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/campaigns/{campaignId}/retry \
  -H "X-API-Key: your-api-key"
```

Each recipient is retried up to 3 times with exponential backoff. Transient failures (SMTP timeout, DNS resolution errors, temporary provider rejection) will usually succeed on retry. Permanent failures (invalid email address, corrupt or missing PDF) will not recover and require manual correction.

---

## 8. Step 7 — Billing & Usage

### View current month summary

```bash
GET /api/v1/billing/summary/{orgId}/2026-03
```

**Response:**

```json
{
  "billingPeriod": "2026-03",
  "totalInvoicesSubmitted": 450,
  "deliveredCount": 448,
  "failedCount": 2,
  "skippedCount": 0,
  "billableCount": 450,
  "rateProfileName": "Standard",
  "baseFee": 25.00,
  "usageCharges": 50.00,
  "totalAmount": 75.00,
  "currency": "USD",
  "status": "OPEN"
}
```

### View per-invoice usage log

```bash
GET /api/v1/billing/usage/{orgId}/2026-03
```

### View full billing history

```bash
GET /api/v1/billing/history/{orgId}
```

### Estimate your monthly cost

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/billing/estimate \
  -H "Content-Type: application/json" \
  -d '{
    "rateProfileId": "rate-profile-uuid",
    "invoiceCount": 500
  }'
```

To view available rate profiles: `GET /api/v1/billing/rate-profiles`

---

## 9. Step 8 — PEPPOL e-Invoice Delivery (Optional)

PEPPOL allows direct ERP-to-ERP delivery of structured invoices without email. This is required when your customers use a PEPPOL-connected ERP (e.g. SAP, D365, Sage) and prefer electronic invoices over email PDFs.

### 9.1 Register your access point

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/eregistry/access-points \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
    "participantId": "0190:ZWV123456789",
    "participantName": "Acme Holdings AP Gateway",
    "role": "GATEWAY",
    "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
    "simplifiedHttpDelivery": true
  }'
```

**`role` values:** `GATEWAY` (send and receive), `SENDER` (outbound only), `RECEIVER` (inbound only)

Set `simplifiedHttpDelivery: false` and provide an X.509 certificate for production AS4 deployments.

### 9.2 Register a customer's access point

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/eregistry/access-points \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "participantId": "0190:ZW987654321",
    "participantName": "Client Company Ltd",
    "role": "RECEIVER",
    "endpointUrl": "https://erp.clientcompany.co.zw/peppol/receive",
    "simplifiedHttpDelivery": true
  }'
```

### 9.3 Link a customer to PEPPOL

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/eregistry/participant-links \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
    "customerContactId": "customer-uuid",
    "participantId": "0190:ZW987654321",
    "receiverAccessPointId": "receiver-ap-uuid",
    "preferredChannel": "PEPPOL"
  }'
```

Once linked, all future dispatches to that customer route through PEPPOL automatically.

### 9.4 Switch your organisation to PEPPOL delivery

```bash
curl -X PATCH https://ap.invoicedirect.biz/api/v1/organizations/{orgId}/delivery-mode \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{ "deliveryMode": "AS4" }'
```

### 9.5 Check PEPPOL delivery history

```bash
GET /api/v1/eregistry/deliveries?organizationId={orgId}
```

---

## 10. ERP Integration Reference

This section covers the additional server-side configuration required for Option C (ERP Pull). Contact your platform administrator to set these environment variables.

### Odoo

| Environment variable | Description |
|----------------------|-------------|
| `ODOO_BASE_URL` | Your Odoo instance URL, e.g. `https://erp.acmeholdings.co.zw` |
| `ODOO_DATABASE` | Odoo database name |
| `ODOO_API_KEY` | Odoo API key (generated in Settings → Technical → API Keys) |

The adapter fetches invoices via the Odoo JSON-RPC API (`account.move` model) and downloads PDFs from the `/report/pdf/account.report_invoice/{id}` endpoint.

**Invoice ID format:** The Odoo document name, e.g. `INV/2026/0042`

### Sage Intacct

| Environment variable | Description |
|----------------------|-------------|
| `SAGE_BASE_URL` | Sage XML API endpoint (usually `https://api.intacct.com/ia/xml/xmlgw.phtml`) |
| `SAGE_COMPANY_ID` | Sage company ID |
| `SAGE_PDF_DIR` | File system path to the directory where Sage exports PDF invoices |

The adapter queries the Sage XML API for `ARINVOICE` records, then resolves PDFs from the configured export directory by matching the document number (e.g. `INV-2026-0042.pdf`).

**Invoice ID format:** Sage document number, e.g. `INV-2026-0042`

### QuickBooks Online

| Environment variable | Description |
|----------------------|-------------|
| `QB_CLIENT_ID` | QuickBooks OAuth2 client ID |
| `QB_CLIENT_SECRET` | QuickBooks OAuth2 client secret |
| `QB_REALM_ID` | QuickBooks company (realm) ID |
| `QB_REFRESH_TOKEN` | OAuth2 refresh token |

The adapter queries the QuickBooks REST API and downloads PDFs via the `/invoice/{id}?requestid=pdf` endpoint.

**Invoice ID format:** Numeric QuickBooks invoice ID, e.g. `1045`

### Dynamics 365

| Environment variable | Description |
|----------------------|-------------|
| `D365_BASE_URL` | OData endpoint, e.g. `https://your-org.crm.dynamics.com/api/data/v9.2` |
| `D365_CLIENT_ID` | Azure app registration client ID |
| `D365_CLIENT_SECRET` | Azure app registration client secret |
| `D365_TENANT_ID` | Azure Active Directory tenant ID |

The adapter queries `salesinvoiceheaders` via OData and retrieves PDF attachments from the Document Management API.

**Invoice ID format:** D365 invoice number, e.g. `FML-INV-001`

---

## 11. API Authentication Reference

All API calls except organisation registration and single invoice dispatch require the `X-API-Key` header.

```
X-API-Key: your-organisation-api-key
```

**Admin users** authenticate via `POST /api/v1/admin/login` with username and password, then use the returned session token as the `X-API-Key` value for subsequent admin requests.

```bash
# Admin login
curl -X POST https://ap.invoicedirect.biz/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{ "username": "admin", "password": "your-password" }'

# Response
{
  "token": "session-token-uuid",
  "username": "admin",
  "expiresAt": "2026-03-31T10:30:00Z"
}

# Use the token
curl -H "X-API-Key: session-token-uuid" https://ap.invoicedirect.biz/api/v1/admin/organizations
```

---

## 12. Email Template Variables Reference

The `invoice` template supports the following variables. Pass them in the `templateVariables` (batch) or `variables` (single) field of your request.

### Organisation-level variables (same for all recipients in a campaign)

| Variable | Description | Example |
|----------|-------------|---------|
| `companyName` | Your company's display name | `Acme Holdings (Pvt) Ltd` |
| `accountsEmail` | Email address for invoice queries | `accounts@acmeholdings.co.zw` |
| `companyAddress` | Footer address line | `45 Borrowdale Road, Harare` |
| `paymentInstructions` | HTML block with payment details | `<p>Pay via RTGS to...</p>` |

### Per-invoice variables (set per recipient in the `invoices` array)

| Field | Description | Required |
|-------|-------------|----------|
| `invoiceNumber` | Invoice reference number | Yes |
| `recipientName` | Customer contact name | Yes |
| `recipientCompany` | Customer company name | Recommended |
| `invoiceDate` | Date of invoice (`YYYY-MM-DD`) | Recommended |
| `dueDate` | Payment due date (`YYYY-MM-DD`) | Recommended |
| `totalAmount` | Invoice total (numeric) | Yes |
| `vatAmount` | VAT portion (numeric) | Recommended |
| `currency` | ISO currency code | Yes |
| `fiscalDeviceSerialNumber` | ZIMRA fiscal device serial | Required for ZIMRA compliance |
| `fiscalDayNumber` | Fiscal day counter | Required for ZIMRA compliance |
| `globalInvoiceCounter` | Global invoice sequence number | Required for ZIMRA compliance |
| `verificationCode` | FDMS verification code | Required for ZIMRA compliance |
| `qrCodeUrl` | FDMS QR verification URL | Required for ZIMRA compliance |

The fiscal verification block is rendered automatically when `fiscalDeviceSerialNumber` is present.

Currency symbols are derived automatically: `USD` → `$`, `ZWG` → `ZiG`.

---

## 13. Troubleshooting

### Campaign stuck in `IN_PROGRESS` for a long time

- Large campaigns (hundreds of recipients) can take several minutes. The default batch size is 50 recipients per batch.
- Check `/api/v1/campaigns/{id}` for progress — `sent` + `failed` + `skipped` should be incrementing.
- If the campaign has not progressed after 15 minutes, contact your administrator to check server logs.

### Campaign ends in `PARTIALLY_FAILED`

- Trigger a retry: `POST /api/v1/campaigns/{campaignId}/retry`
- Common causes: temporary SMTP provider rejection, DNS resolution failure, recipient inbox full.
- Permanent failures (invalid email address, corrupt PDF file) will not recover on retry.

### Invoice PDF not attached or corrupt

- Verify the PDF file path is accessible from the server, or check that the Base64-encoded content is a valid PDF.
- ZIMRA-fiscalised PDFs must contain valid fiscal markers. If validation is enabled, an invalid PDF will be skipped with a `SKIPPED` status.

### `400 Bad Request` — missing PDF part

When using Option A (batch upload), every `invoiceNumber` in `metadata.invoices` must have a corresponding multipart file part with the same name. Check that the `-F` part names exactly match the invoice numbers.

### ERP adapter returns no invoices

- Verify the ERP environment variables are set correctly.
- Check that the invoice IDs match the format expected by the adapter (see [Section 10](#10-erp-integration-reference)).
- For Odoo: confirm the API key has access to `account.move` records.
- For Sage: confirm the PDF export directory is mounted and readable.
- For QuickBooks: the OAuth2 refresh token may have expired — re-authorise and update `QB_REFRESH_TOKEN`.

### API key rejected (`401 Unauthorized`)

- Confirm you are passing the header as `X-API-Key` (not `Authorization`).
- API keys are case-sensitive. Verify no trailing whitespace.
- If you have lost your key, contact your platform administrator to issue a replacement.
