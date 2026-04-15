# InvoiceDirect Access Point
## Organisation Registration & Onboarding Guide

**Platform:** `https://ap.invoicedirect.biz`
**API Docs:** `https://ap.invoicedirect.biz/swagger-ui/index.html`
**Support:** `support@invoicedirect.biz`

---

## What is InvoiceDirect?

InvoiceDirect is a PEPPOL-compliant invoice delivery Access Point for Zimbabwe.
It allows organisations to send fiscalised invoices to their customers through
two channels:

- **Email channel** — PDF invoice attached to a branded HTML email, sent via
  your organisation's no-reply address
- **PEPPOL channel** — UBL BIS 3.0 XML delivered directly to the customer's
  ERP system over the PEPPOL network (ERP-to-ERP, no email required)

The platform connects to your ERP (Odoo, Sage Intacct, QuickBooks Online,
Dynamics 365) or accepts direct PDF uploads. Every delivery is tracked,
audited, and metered for billing.

---

## Before You Start

Have the following ready:

| Item | Example |
|---|---|
| Company legal name | Acme Holdings (Pvt) Ltd |
| Short slug (URL-safe, unique) | `acme-holdings` |
| No-reply sender email | `noreply@acmeholdings.co.zw` |
| Accounts / billing contact email | `accounts@acmeholdings.co.zw` |
| Company physical address | 45 Borrowdale Road, Harare |
| Your ERP system | Odoo / Sage Intacct / QuickBooks / Dynamics 365 / None |
| Invoice currencies used | USD, ZWG, ZAR (Zimbabwe supports multi-currency) |

---

## Step 1 — Register Your Organisation

Send a `POST` request to create your account. An API key is generated
automatically and returned in the response.

```http
POST https://ap.invoicedirect.biz/api/v1/organizations
Content-Type: application/json
```

```json
{
  "name": "Acme Holdings (Pvt) Ltd",
  "slug": "acme-holdings",
  "senderEmail": "noreply@acmeholdings.co.zw",
  "senderDisplayName": "Acme Holdings Accounts",
  "accountsEmail": "accounts@acmeholdings.co.zw",
  "companyAddress": "45 Borrowdale Road, Harare, Zimbabwe",
  "primaryErpSource": "ODOO",
  "erpTenantId": "acme-holdings"
}
```

**Field reference:**

| Field | Required | Notes |
|---|---|---|
| `name` | Yes | Full legal company name |
| `slug` | Yes | Lowercase, hyphens only, globally unique (e.g. `acme-holdings`) |
| `senderEmail` | Yes | The `From:` address on all invoice emails. Must be a real mailbox on your domain. |
| `senderDisplayName` | Yes | What recipients see in their inbox (e.g. `Acme Holdings Accounts`) |
| `accountsEmail` | No | Shown in the email body for invoice queries |
| `companyAddress` | No | Shown in email footer and PEPPOL UBL documents |
| `primaryErpSource` | No | `ODOO`, `SAGE_INTACCT`, `QUICKBOOKS_ONLINE`, `DYNAMICS_365`, or `GENERIC_API` |
| `erpTenantId` | No | Your ERP company/database identifier — used when the platform pulls invoices from your ERP |

**Successful response (HTTP 201):**

```json
{
  "id": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "name": "Acme Holdings (Pvt) Ltd",
  "slug": "acme-holdings",
  "senderEmail": "noreply@acmeholdings.co.zw",
  "senderDisplayName": "Acme Holdings Accounts",
  "accountsEmail": "accounts@acmeholdings.co.zw",
  "primaryErpSource": "ODOO",
  "erpTenantId": "acme-holdings",
  "rateProfileId": null,
  "status": "ACTIVE",
  "apiKey": "9ca22fce40834c6c897bcf32c89b369c"
}
```

> **Save your `id` and `apiKey` immediately.** The API key is shown only once.
> It authenticates all subsequent API calls from your ERP or integration system.
> Treat it like a password — do not commit it to source control.

---

## Step 2 — Retrieve Your Organisation Details

Verify your registration was successful and retrieve your `id` at any time:

```http
GET https://ap.invoicedirect.biz/api/v1/organizations/by-slug/acme-holdings
```

Or by ID:

```http
GET https://ap.invoicedirect.biz/api/v1/organizations/d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f
```

---

## Step 3 — Understand Your Billing Plan

The platform meters every invoice dispatched through your account and bills
monthly based on a tiered rate profile.

**View available rate profiles:**

```http
GET https://ap.invoicedirect.biz/api/v1/billing/rate-profiles
```

**Estimate your monthly cost before committing:**

```http
POST https://ap.invoicedirect.biz/api/v1/billing/estimate
Content-Type: application/json

{
  "rateProfileId": "rate-profile-uuid",
  "invoiceCount": 500
}
```

Response:

```json
{
  "profileName": "Standard",
  "invoiceCount": 500,
  "baseFee": 25.00,
  "usageCharges": 50.00,
  "totalAmount": 75.00,
  "currency": "USD"
}
```

**Assign a rate profile to your account** (done by the InvoiceDirect team
or via admin API):

```http
PATCH https://ap.invoicedirect.biz/api/v1/organizations/{your-org-id}/rate-profile
Content-Type: application/json

{ "rateProfileId": "rate-profile-uuid" }
```

---

## Step 4 — Register Your Customers

Every invoice recipient must exist in the customer registry before invoices
can be dispatched to them.

**Option A — Pre-register customers (recommended):**

Register your customer list before running any campaigns. This is ideal for
bulk onboarding from your CRM or ERP customer master.

```http
POST https://ap.invoicedirect.biz/api/v1/organizations/{your-org-id}/customers
Content-Type: application/json

{
  "email": "finance@clientcompany.co.zw",
  "name": "Jane Moyo",
  "companyName": "Client Company Ltd",
  "erpSource": "ODOO"
}
```

**Option B — Auto-registration on first dispatch:**

If you skip pre-registration, customers are automatically created in the
registry the first time you dispatch an invoice to them. Their record is
built from the invoice metadata you provide.

**Look up a registered customer:**

```http
GET https://ap.invoicedirect.biz/api/v1/organizations/{your-org-id}/customers/by-email
    ?email=finance@clientcompany.co.zw
```

**List all your registered customers:**

```http
GET https://ap.invoicedirect.biz/api/v1/organizations/{your-org-id}/customers
```

---

## Step 5 — Send Your First Invoice

Choose the dispatch method that fits your technical setup.

---

### Method A — PDF Upload (recommended for most organisations)

Upload one or more invoice PDFs in a single request. Each PDF is matched to
its invoice metadata by `invoiceNumber`. Customers are auto-registered if
not already in the registry.

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/erp/dispatch/upload \
  -F 'metadata={
    "campaignName": "March 2026 Invoices",
    "subject": "Your Invoice from Acme Holdings",
    "templateName": "invoice",
    "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
    "templateVariables": {
      "companyName": "Acme Holdings (Pvt) Ltd",
      "accountsEmail": "accounts@acmeholdings.co.zw",
      "companyAddress": "45 Borrowdale Road, Harare"
    },
    "invoices": [
      {
        "invoiceNumber": "INV-2026-0042",
        "recipientEmail": "finance@clientcompany.co.zw",
        "recipientName": "Jane Moyo",
        "recipientCompany": "Client Company Ltd",
        "invoiceDate": "2026-03-01",
        "dueDate": "2026-03-31",
        "totalAmount": 1250.00,
        "vatAmount": 187.50,
        "currency": "USD",
        "fiscalDeviceSerialNumber": "FD-SN-12345",
        "verificationCode": "ABCD-EFGH-1234",
        "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=ABCD-EFGH-1234"
      },
      {
        "invoiceNumber": "INV-2026-0043",
        "recipientEmail": "cfo@anotherco.co.zw",
        "recipientName": "Tendai Mhaka",
        "recipientCompany": "Another Co Ltd",
        "invoiceDate": "2026-03-01",
        "dueDate": "2026-03-31",
        "totalAmount": 3400.00,
        "vatAmount": 510.00,
        "currency": "ZWG",
        "fiscalDeviceSerialNumber": "FD-SN-12345",
        "verificationCode": "WXYZ-5678-ABCD"
      }
    ]
  }' \
  -F "INV-2026-0042=@/path/to/INV-2026-0042.pdf" \
  -F "INV-2026-0043=@/path/to/INV-2026-0043.pdf"
```

> Each PDF file part name must exactly match the `invoiceNumber` in the
> metadata. Multiple invoices = multiple `-F` parts.

**Supported currencies:** `USD`, `ZWG` (Zimbabwe Gold), `ZAR`, `GBP`, `EUR`,
`CNY`, `BWP`. Always specify the currency — no default is applied.

**Response (HTTP 202):**

```json
{
  "totalInvoices": 2,
  "peppolDispatched": 0,
  "emailDispatched": 2,
  "emailCampaignId": "a0879a37-d4ce-40a5-aec5-38c64b087678",
  "message": "0 via PEPPOL BIS 3.0, 2 via email PDF"
}
```

---

### Method B — Single Invoice (real-time, synchronous)

Send one invoice and receive an immediate delivery confirmation. Ideal for
triggering directly from your ERP when an invoice is posted.

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/mail/invoice/upload \
  -F "pdf=@/path/to/INV-2026-0042.pdf" \
  -F 'metadata={
    "to": "finance@clientcompany.co.zw",
    "recipientName": "Jane Moyo",
    "subject": "Invoice INV-2026-0042 from Acme Holdings",
    "templateName": "invoice",
    "invoiceNumber": "INV-2026-0042",
    "invoiceDate": "2026-03-01",
    "dueDate": "2026-03-31",
    "totalAmount": 1250.00,
    "vatAmount": 187.50,
    "currency": "USD",
    "verificationCode": "ABCD-EFGH-1234",
    "variables": {
      "companyName": "Acme Holdings (Pvt) Ltd",
      "accountsEmail": "accounts@acmeholdings.co.zw"
    }
  }'
```

**Response (HTTP 200):**

```json
{
  "status": "delivered",
  "recipient": "finance@clientcompany.co.zw",
  "invoiceNumber": "INV-2026-0042",
  "messageId": "<abc123@smtp.gmail.com>",
  "error": null,
  "retryable": false
}
```

---

### Method C — ERP Pull (platform fetches from your ERP)

The platform connects directly to your ERP, fetches invoice data and PDFs
by invoice ID, and dispatches automatically. Requires your ERP credentials
to be configured on the platform — contact the InvoiceDirect team to set
this up.

**Supported ERPs:** Odoo, Sage Intacct, QuickBooks Online, Dynamics 365

```http
POST https://ap.invoicedirect.biz/api/v1/erp/dispatch
Content-Type: application/json

{
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
}
```

**ERP source values:**

| Value | ERP System | Invoice ID format |
|---|---|---|
| `ODOO` | Odoo 14+ | `INV/2026/0042` |
| `SAGE_INTACCT` | Sage Intacct | `INV-2026-0042` |
| `QUICKBOOKS_ONLINE` | QuickBooks Online | `1045` (numeric ID) |
| `DYNAMICS_365` | Microsoft Dynamics 365 | `FML-INV-001` |
| `GENERIC_API` | No ERP pull — use Method A instead | n/a |

---

## Step 6 — Track Delivery Status

After dispatching a campaign, poll the status endpoint to track progress:

```http
GET https://ap.invoicedirect.biz/api/v1/campaigns/{campaignId}
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
|---|---|
| `QUEUED` | Created, waiting to start dispatching |
| `IN_PROGRESS` | Actively sending |
| `COMPLETED` | All invoices delivered successfully |
| `PARTIALLY_FAILED` | Some invoices failed — retry available |
| `FAILED` | All invoices failed |

**Retry failed invoices:**

```http
POST https://ap.invoicedirect.biz/api/v1/campaigns/{campaignId}/retry
```

The platform retries all failed recipients that have not exceeded the maximum
retry limit (3 attempts). Transient failures (SMTP timeout, connection error)
are retried automatically. Permanent failures (invalid email address, corrupt
PDF) will not succeed on retry.

**List all your campaigns:**

```http
GET https://ap.invoicedirect.biz/api/v1/campaigns
```

---

## Step 7 — Monitor Your Billing Usage

**Current month summary:**

```http
GET https://ap.invoicedirect.biz/api/v1/billing/summary/{your-org-id}/2026-03
```

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

> Delivered and failed invoices are both billable — the platform processed
> them. Skipped invoices (e.g. missing PDF) are not billed.

**Detailed per-invoice usage log:**

```http
GET https://ap.invoicedirect.biz/api/v1/billing/usage/{your-org-id}/2026-03
```

**Full billing history:**

```http
GET https://ap.invoicedirect.biz/api/v1/billing/history/{your-org-id}
```

---

## Step 8 — PEPPOL ERP-to-ERP Delivery (optional)

If your customers' ERP systems support PEPPOL, invoices can be delivered
directly as UBL BIS 3.0 XML to their ERP — no email involved. The platform
automatically routes to PEPPOL for customers who have a registered Access
Point, and falls back to email for those who don't.

### 8a — Register your Access Point (C2 gateway)

```http
POST https://ap.invoicedirect.biz/api/v1/eregistry/access-points
Content-Type: application/json

{
  "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "participantId": "0190:ZW123456789",
  "participantName": "Acme Holdings AP Gateway",
  "role": "GATEWAY",
  "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
  "simplifiedHttpDelivery": true
}
```

> The `participantId` format is `{scheme}:{identifier}`.
> For Zimbabwe, use scheme `0190` with your VAT registration number.

### 8b — Register each PEPPOL-enabled customer's Access Point (C3)

Obtain the endpoint URL and participant ID from your customer or their AP
provider, then register it:

```http
POST https://ap.invoicedirect.biz/api/v1/eregistry/access-points
Content-Type: application/json

{
  "participantId": "0190:ZW987654321",
  "participantName": "Client Company Ltd",
  "role": "RECEIVER",
  "endpointUrl": "https://erp.clientcompany.co.zw/peppol/receive",
  "simplifiedHttpDelivery": true,
  "deliveryAuthToken": "their-bearer-token-if-required"
}
```

### 8c — Link the customer to their Access Point

```http
POST https://ap.invoicedirect.biz/api/v1/eregistry/participant-links
Content-Type: application/json

{
  "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "customerContactId": "customer-uuid-from-registry",
  "participantId": "0190:ZW987654321",
  "receiverAccessPointId": "receiver-ap-uuid",
  "preferredChannel": "PEPPOL"
}
```

Once linked, all future dispatches to that customer automatically use PEPPOL
BIS 3.0. Customers without a PEPPOL link continue to receive PDF emails.

**Check PEPPOL delivery history:**

```http
GET https://ap.invoicedirect.biz/api/v1/eregistry/deliveries?organizationId={your-org-id}
```

---

## Invoice Fields Reference

### Required fields for every invoice

| Field | Type | Description |
|---|---|---|
| `invoiceNumber` | string | Your unique invoice number (e.g. `INV-2026-0042`) |
| `recipientEmail` | string | Customer's email address |
| `currency` | string | ISO 4217 code — **must be specified** |

### Financial fields

| Field | Type | Description |
|---|---|---|
| `totalAmount` | decimal | Total invoice amount inclusive of VAT |
| `vatAmount` | decimal | VAT portion of the total |
| `invoiceDate` | date | Invoice issue date (`YYYY-MM-DD`) |
| `dueDate` | date | Payment due date (`YYYY-MM-DD`) |

### ZIMRA fiscal compliance fields

These fields are required for fiscalised invoices under the Zimbabwe Revenue
Authority (ZIMRA) regulations. They are rendered in the email body and
embedded in the PEPPOL UBL document.

| Field | Description |
|---|---|
| `fiscalDeviceSerialNumber` | Serial number of the fiscal device that signed the invoice |
| `fiscalDayNumber` | Fiscal day counter on the device |
| `globalInvoiceCounter` | Sequential invoice counter from the fiscal device |
| `verificationCode` | ZIMRA verification code (e.g. `ABCD-EFGH-1234`) |
| `qrCodeUrl` | URL to the ZIMRA QR verification image |

### Supported currencies (Zimbabwe multi-currency environment)

| Code | Currency | Symbol |
|---|---|---|
| `USD` | US Dollar | $ |
| `ZWG` | Zimbabwe Gold (introduced April 2024) | ZiG |
| `ZAR` | South African Rand | R |
| `GBP` | British Pound | £ |
| `EUR` | Euro | € |
| `CNY` | Chinese Yuan | ¥ |
| `BWP` | Botswana Pula | P |

---

## Quick Reference

| Action | Endpoint |
|---|---|
| Register your organisation | `POST /api/v1/organizations` |
| Get your org by slug | `GET /api/v1/organizations/by-slug/{slug}` |
| View rate profiles | `GET /api/v1/billing/rate-profiles` |
| Estimate monthly cost | `POST /api/v1/billing/estimate` |
| Register a customer | `POST /api/v1/organizations/{orgId}/customers` |
| Send multiple invoices (PDF upload) | `POST /api/v1/erp/dispatch/upload` |
| Send single invoice (real-time) | `POST /api/v1/mail/invoice/upload` |
| ERP pull dispatch | `POST /api/v1/erp/dispatch` |
| Track campaign status | `GET /api/v1/campaigns/{id}` |
| Retry failed invoices | `POST /api/v1/campaigns/{id}/retry` |
| Monthly billing summary | `GET /api/v1/billing/summary/{orgId}/{period}` |
| Per-invoice usage log | `GET /api/v1/billing/usage/{orgId}/{period}` |
| Register PEPPOL AP | `POST /api/v1/eregistry/access-points` |
| Link customer to PEPPOL | `POST /api/v1/eregistry/participant-links` |
| PEPPOL delivery history | `GET /api/v1/eregistry/deliveries` |
| Full API documentation | `GET /swagger-ui/index.html` |

---

## Checklist

Use this to confirm your onboarding is complete:

- [ ] Organisation registered — `id` and `apiKey` saved securely
- [ ] Rate profile assigned — billing is active
- [ ] Customer list registered (or auto-registration confirmed)
- [ ] Test invoice dispatched and delivery confirmed
- [ ] Campaign status checked — `COMPLETED` or `PARTIALLY_FAILED`
- [ ] Billing summary reviewed for the current period
- [ ] PEPPOL Access Points registered (if applicable)
- [ ] ERP integration configured (if using Method C)

---

## Support

| Channel | Contact |
|---|---|
| Technical support | `support@invoicedirect.biz` |
| Billing queries | `billing@invoicedirect.biz` |
| API documentation | `https://ap.invoicedirect.biz/swagger-ui/index.html` |
| PEPPOL AP health | `https://ap.invoicedirect.biz/peppol/as4/health` |
