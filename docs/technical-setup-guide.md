# InvoiceDirect Access Point — Technical Setup Guide

**Platform:** `https://ap.invoicedirect.biz`  
**API Docs:** `https://ap.invoicedirect.biz/swagger-ui/index.html`  
**Support:** `support@invoicedirect.biz`

---

## 1. Prerequisites

| Requirement | Detail |
|---|---|
| Java | 25 (Eclipse Temurin, preview features enabled) |
| Maven | 3.9+ |
| PostgreSQL | 16+ |
| Docker / Docker Compose | For containerised deployment |
| Gmail account | For OAuth2 SMTP (or any SMTP relay) |
| PEPPOL AP certificate | X.509 key pair — required for production AS4 |
| ZIMRA fiscal device | Required for Zimbabwe-origin invoices |

---

## 2. Infrastructure Setup

### 2.1 Database

The application uses PostgreSQL with Hibernate `ddl-auto: update` — schema is managed automatically on startup.

```bash
# Standalone (non-Docker)
createdb massmailer
createuser mailer --pwprompt
psql -c "GRANT ALL PRIVILEGES ON DATABASE massmailer TO mailer;"
```

Required environment variables:

```env
DB_URL=jdbc:postgresql://localhost:5432/massmailer
DB_USER=mailer
DB_PASS=<your-password>
```

### 2.2 Docker Compose (recommended)

The `docker-compose.yml` at the project root starts both PostgreSQL and the application:

```bash
# Copy and edit environment overrides
cp .env.example .env   # create if not present — see Section 2.3

docker compose up -d
```

The application binds to port `8080`. PostgreSQL binds to `5432`.

### 2.3 Environment Variables Reference

Create a `.env` file in the project root (never commit it):

```env
# ── Database ──
DB_URL=jdbc:postgresql://postgres:5432/massmailer
DB_USER=mailer
DB_PASS=<strong-password>

# ── SMTP (Gmail OAuth2 — see Section 3) ──
GOOGLE_OAUTH2_CREDENTIALS_PATH=google-oauth-credentials.json
GOOGLE_OAUTH2_REFRESH_TOKEN=<your-refresh-token>
MAIL_FROM=noreply@yourdomain.com
MAIL_FROM_NAME=YourCompany

# ── ERP Adapters (configure only the ones you use) ──
ODOO_BASE_URL=https://your-db.odoo.com
ODOO_DATABASE=your-db
ODOO_USERNAME=admin
ODOO_API_KEY=<odoo-api-key>

SAGE_BASE_URL=https://api.intacct.com/ia/xml/xmlgw.phtml
SAGE_SENDER_ID=<sender-id>
SAGE_SENDER_PASSWORD=<sender-password>
SAGE_COMPANY_ID=<company-id>
SAGE_USER_ID=<user-id>
SAGE_USER_PASSWORD=<user-password>

QB_CLIENT_ID=<qb-client-id>
QB_CLIENT_SECRET=<qb-client-secret>
QB_REALM_ID=<realm-id>
QB_REFRESH_TOKEN=<qb-refresh-token>

D365_BASE_URL=https://your-org.crm.dynamics.com
D365_TENANT_ID=<azure-tenant-id>
D365_CLIENT_ID=<app-client-id>
D365_CLIENT_SECRET=<app-client-secret>

# ── Throughput ──
RATE_LIMIT=10
BATCH_SIZE=50
```

### 2.4 Build and Run (without Docker)

```bash
mvn package -DskipTests
java --enable-preview -XX:+UseZGC -Xmx512m -jar target/*.jar
```

---

## 3. Gmail OAuth2 SMTP Configuration

The application uses Gmail's XOAUTH2 mechanism — no plain password is stored.

### 3.1 One-time OAuth2 consent flow

1. Go to [Google OAuth2 Playground](https://developers.google.com/oauthplayground)
2. In settings (gear icon), check **Use your own OAuth credentials** and enter your Google Cloud project's Client ID and Client Secret.
3. Authorise scope: `https://mail.google.com/`
4. Click **Exchange authorization code for tokens**.
5. Copy the **Refresh Token** — set it as `GOOGLE_OAUTH2_REFRESH_TOKEN`.

### 3.2 Service account (alternative)

If using a Google Workspace service account with domain-wide delegation:

```env
GOOGLE_SERVICE_ACCOUNT_KEY_PATH=/secrets/service-account.json
```

Place the downloaded JSON key file at that path. The application will use it to generate access tokens automatically.

### 3.3 Verify SMTP

```bash
curl -s https://ap.invoicedirect.biz/actuator/health | jq .
```

`mail.enabled` is set to `false` in the health check to avoid startup failures — test by dispatching a real invoice.

---

## 4. PEPPOL C1–C4 Full Configuration

The platform implements the PEPPOL 4-corner model:

```
C1 (Supplier ERP)
  → C2 (InvoiceDirect AP Gateway — outbound)
    → C3 (Buyer's AP endpoint — inbound)
      → C4 (Buyer ERP webhook)
```

### 4.1 C1 — Supplier ERP (your side)

C1 is your ERP system. Connect it to the gateway using one of three methods:

| Method | Endpoint | When to use |
|---|---|---|
| PDF upload | `POST /api/v1/erp/dispatch/upload` | You generate PDFs in your ERP |
| ERP pull | `POST /api/v1/erp/dispatch` | Platform fetches from your ERP |
| Single invoice | `POST /api/v1/mail/invoice/upload` | Real-time, one invoice at a time |

All requests require the `X-API-Key` header with your organisation's API key.

### 4.2 C2 — Sender Access Point (this gateway)

Register your organisation as a C2 gateway after self-registration:

```http
POST /api/v1/eregistry/access-points
Content-Type: application/json
X-API-Key: <your-api-key>

{
  "organizationId": "<your-org-id>",
  "participantId": "0190:ZW<your-vat-number>",
  "participantName": "Your Company AP Gateway",
  "role": "GATEWAY",
  "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
  "simplifiedHttpDelivery": false
}
```

**`role` values:**

| Role | Meaning |
|---|---|
| `GATEWAY` | Acts as both sender (C2) and receiver (C3) |
| `SENDER` | Outbound only (C2) |
| `RECEIVER` | Inbound only (C3) |

**`simplifiedHttpDelivery`:**
- `true` — plain HTTPS POST of UBL XML (for private/internal networks or development)
- `false` — full AS4 ebMS 3.0 with X.509 signing (required for production PEPPOL)

#### C2 X.509 Certificate Setup (production AS4)

The AS4 engine signs outbound messages with the sender's X.509 private key. To provision:

1. Obtain a PEPPOL-accredited AP certificate from your national PEPPOL authority.
2. Store the private key and certificate in a Java KeyStore (JKS or PKCS12).
3. Configure the key store path and credentials in `application.yml` (or environment):

```yaml
peppol:
  keystore:
    path: /secrets/peppol-keystore.p12
    password: ${PEPPOL_KEYSTORE_PASSWORD}
    alias: ${PEPPOL_KEY_ALIAS}
```

> The `As4TransportClientImpl` has a `TODO` comment marking where the sender cert/key are loaded. Wire the KeyStore here before going to production AS4.

### 4.3 C3 — Buyer Access Point (receiver endpoint)

For each buyer who has a PEPPOL-capable ERP, register their AP in the eRegistry:

```http
POST /api/v1/eregistry/access-points
Content-Type: application/json
X-API-Key: <your-api-key>

{
  "participantId": "0190:ZW<buyer-vat-number>",
  "participantName": "Buyer Company Ltd",
  "role": "RECEIVER",
  "endpointUrl": "https://erp.buyercompany.co.zw/peppol/receive",
  "simplifiedHttpDelivery": true,
  "deliveryAuthToken": "<bearer-token-if-required>",
  "certificate": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"
}
```

The `certificate` field (PEM format) is used by the AS4 engine to encrypt the payload for the receiver. Required when `simplifiedHttpDelivery=false`.

Then link the buyer's customer contact to their AP:

```http
POST /api/v1/eregistry/participant-links
Content-Type: application/json
X-API-Key: <your-api-key>

{
  "organizationId": "<your-org-id>",
  "customerContactId": "<customer-uuid>",
  "participantId": "0190:ZW<buyer-vat-number>",
  "receiverAccessPointId": "<ap-uuid-from-above>",
  "preferredChannel": "PEPPOL"
}
```

Once linked, all dispatches to that customer automatically use PEPPOL BIS 3.0. Customers without a link fall back to email.

### 4.4 C4 — Buyer ERP Webhook

C4 is the buyer's ERP webhook that receives forwarded inbound documents. Configure it on the buyer's organisation record:

```http
PATCH /api/v1/organizations/<buyer-org-id>/c4-webhook
Content-Type: application/json

{
  "c4WebhookUrl": "https://erp.buyercompany.co.zw/api/invoices/inbound",
  "c4WebhookAuthToken": "<bearer-token>"
}
```

The C4 routing job runs every 30 seconds, picks up `InboundDocument` records with `routingStatus=RECEIVED`, and POSTs the UBL XML to the webhook. It retries up to 3 times with exponential backoff (30s → 60s → 120s).

### 4.5 Delivery Mode per Organisation

Set the default delivery channel for an organisation:

```http
PATCH /api/v1/organizations/<org-id>/delivery-mode
Content-Type: application/json

{ "deliveryMode": "AS4" }
```

| Mode | Behaviour |
|---|---|
| `EMAIL` | PDF email only (default on registration) |
| `AS4` | PEPPOL BIS 3.0 UBL only |
| `BOTH` | PEPPOL + email simultaneously |

Individual customer contacts can override this at the contact level.

### 4.6 Schematron Validation

All outbound PEPPOL invoices are validated against the PEPPOL EN16931 Schematron rules before transmission. The rules file must be present at:

```
src/main/resources/schematron/PEPPOL-EN16931-UBL.sch
```

Download the latest rules from the [PEPPOL GitHub](https://github.com/OpenPEPPOL/peppol-bis-invoice-3) and place the compiled XSLT-compatible `.sch` file at that path. The validator caches compiled transforms per profile — no restart needed after the first invocation.

Fatal violations block transmission and persist a `FAILED` delivery record. Warnings are stored on the record but do not block delivery.

---

## 5. Fiscalised PDF Email Setup

This channel sends a branded HTML email with the fiscalised invoice PDF attached. ZIMRA compliance fields are rendered in the email body.

### 5.1 Required fiscal fields per invoice

| Field | Description |
|---|---|
| `fiscalDeviceSerialNumber` | Serial number of the ZIMRA-registered fiscal device |
| `fiscalDayNumber` | Fiscal day counter from the device |
| `globalInvoiceCounter` | Sequential invoice counter from the device |
| `verificationCode` | ZIMRA verification code (e.g. `ABCD-EFGH-1234`) |
| `qrCodeUrl` | URL to the ZIMRA QR verification image |

These fields are embedded in the UBL document and rendered in the email template. The `verificationCode` is mandatory for PEPPOL dispatch — email-only dispatch does not enforce this check.

### 5.2 Dispatching a fiscalised PDF by email

```bash
curl -X POST https://ap.invoicedirect.biz/api/v1/erp/dispatch/upload \
  -H "X-API-Key: <your-api-key>" \
  -F 'metadata={
    "campaignName": "March 2026 Invoices",
    "subject": "Your Invoice from Acme Holdings",
    "templateName": "invoice",
    "organizationId": "<your-org-id>",
    "templateVariables": {
      "companyName": "Acme Holdings (Pvt) Ltd",
      "accountsEmail": "accounts@acmeholdings.co.zw",
      "companyAddress": "45 Borrowdale Road, Harare"
    },
    "invoices": [
      {
        "invoiceNumber": "INV-2026-0042",
        "recipientEmail": "finance@client.co.zw",
        "recipientName": "Jane Moyo",
        "recipientCompany": "Client Company Ltd",
        "invoiceDate": "2026-03-01",
        "dueDate": "2026-03-31",
        "totalAmount": 1250.00,
        "vatAmount": 187.50,
        "currency": "USD",
        "fiscalDeviceSerialNumber": "FD-SN-12345",
        "fiscalDayNumber": 42,
        "globalInvoiceCounter": 1001,
        "verificationCode": "ABCD-EFGH-1234",
        "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=ABCD-EFGH-1234"
      }
    ]
  }' \
  -F "INV-2026-0042=@/path/to/INV-2026-0042.pdf"
```

The PDF file part name must exactly match the `invoiceNumber` in the metadata. The platform validates all PDF parts are present before writing to the database.

### 5.3 Email template customisation

Templates are Thymeleaf HTML files located at:

```
src/main/resources/templates/
```

The `templateName` field in the dispatch request selects the template (e.g. `"invoice"` → `invoice.html`). Template variables are passed via `templateVariables` in the request body.

### 5.4 Supported currencies

`USD`, `ZWG`, `ZAR`, `GBP`, `EUR`, `CNY`, `BWP` — always specify `currency` explicitly; no default is applied.

---

## 6. Customer Self-Registration for PEPPOL

This section covers how a customer organisation registers itself on the platform and configures PEPPOL delivery.

### 6.1 Register the organisation

The registration endpoint is public and rate-limited to 10 requests per IP per hour.

```http
POST https://ap.invoicedirect.biz/api/v1/organizations
Content-Type: application/json

{
  "name": "Acme Holdings (Pvt) Ltd",
  "slug": "acme-holdings",
  "senderEmail": "noreply@acmeholdings.co.zw",
  "senderDisplayName": "Acme Holdings Accounts",
  "accountsEmail": "accounts@acmeholdings.co.zw",
  "companyAddress": "45 Borrowdale Road, Harare, Zimbabwe",
  "vatNumber": "V123456789",
  "tinNumber": "2001234567",
  "primaryErpSource": "ODOO",
  "erpTenantId": "acme-holdings",
  "deliveryMode": "EMAIL"
}
```

**Response (HTTP 201):**

```json
{
  "id": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "slug": "acme-holdings",
  "apiKey": "9ca22fce40834c6c897bcf32c89b369c",
  "peppolParticipantId": "0190:ZWV123456789",
  "deliveryMode": "EMAIL",
  "status": "ACTIVE"
}
```

> Save `id` and `apiKey` immediately. The API key is shown once only and authenticates all subsequent API calls.

The `peppolParticipantId` is derived automatically:
- `vatNumber` present → `0190:ZW{vatNumber}`
- `vatNumber` absent, `tinNumber` present → `0190:ZW{tinNumber}`
- Both absent → `null` (PEPPOL not available until updated)

### 6.2 Upgrade to PEPPOL delivery

Once registered, the customer can enable PEPPOL by:

**Step 1 — Register their Access Point (C2/C3 gateway):**

```http
POST /api/v1/eregistry/access-points
X-API-Key: <customer-api-key>

{
  "organizationId": "<customer-org-id>",
  "participantId": "0190:ZWV123456789",
  "participantName": "Acme Holdings AP",
  "role": "GATEWAY",
  "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
  "simplifiedHttpDelivery": true
}
```

**Step 2 — Switch delivery mode to PEPPOL:**

```http
PATCH /api/v1/organizations/<customer-org-id>/delivery-mode
X-API-Key: <customer-api-key>

{ "deliveryMode": "AS4" }
```

**Step 3 (optional) — Configure C4 webhook for inbound documents:**

```http
PATCH /api/v1/organizations/<customer-org-id>/c4-webhook
X-API-Key: <customer-api-key>

{
  "c4WebhookUrl": "https://erp.acmeholdings.co.zw/api/invoices/inbound",
  "c4WebhookAuthToken": "<webhook-bearer-token>"
}
```

### 6.3 Admin: assign a rate profile

After registration, assign a billing rate profile via the admin API:

```http
PATCH /api/v1/organizations/<org-id>/rate-profile

{ "rateProfileId": "<rate-profile-uuid>" }
```

View available profiles:

```http
GET /api/v1/billing/rate-profiles
```

If a rate profile named `"Default"` exists and is active, it is assigned automatically on registration.

### 6.4 Verify PEPPOL registration

```http
GET /api/v1/organizations/by-slug/acme-holdings
```

Check that `peppolParticipantId` is populated and `deliveryMode` is `AS4` or `BOTH`.

Check the PEPPOL delivery dashboard:

```http
GET /api/v1/dashboard/<org-id>/peppol-stats
```

---

## 7. Monitoring and Operations

### 7.1 Health check

```http
GET /actuator/health
```

### 7.2 PEPPOL AS4 endpoint health

```http
GET /peppol/as4/health
```

### 7.3 Delivery dashboard

```http
# Aggregate stats + 30-day trend
GET /api/v1/dashboard/<org-id>/peppol-stats

# Failed deliveries
GET /api/v1/dashboard/<org-id>/failed-deliveries

# Retry a failed delivery
POST /api/v1/dashboard/<org-id>/retry/<delivery-record-id>
```

### 7.4 Billing

```http
# Current month summary
GET /api/v1/billing/summary/<org-id>/2026-03

# Per-invoice usage log
GET /api/v1/billing/usage/<org-id>/2026-03

# Full history
GET /api/v1/billing/history/<org-id>
```

### 7.5 Logging

Application logs at `DEBUG` for `com.esolutions.massmailer`. In production, set to `INFO`:

```yaml
logging:
  level:
    com.esolutions.massmailer: INFO
```

---

## 8. Security Checklist

- [ ] `DB_PASS` is a strong random password — not the default `mailer_secret`
- [ ] `GOOGLE_OAUTH2_REFRESH_TOKEN` is stored in a secrets manager, not in `.env` committed to git
- [ ] PEPPOL KeyStore password (`PEPPOL_KEYSTORE_PASSWORD`) is stored in a secrets manager
- [ ] `AccessPoint.deliveryAuthToken` and `Organization.c4WebhookAuthToken` are encrypted at rest (AES-256 — Requirement 15.5)
- [ ] The registration endpoint is behind a reverse proxy that forwards `X-Forwarded-For` correctly for rate limiting
- [ ] API keys are rotated if compromised — contact `support@invoicedirect.biz`
- [ ] Production AS4 uses `simplifiedHttpDelivery=false` with a valid PEPPOL-accredited X.509 certificate

---

## 9. Quick Reference

| Task | Endpoint |
|---|---|
| Register organisation | `POST /api/v1/organizations` |
| Get org by slug | `GET /api/v1/organizations/by-slug/{slug}` |
| Register Access Point | `POST /api/v1/eregistry/access-points` |
| Link customer to PEPPOL | `POST /api/v1/eregistry/participant-links` |
| Set delivery mode | `PATCH /api/v1/organizations/{id}/delivery-mode` |
| Assign rate profile | `PATCH /api/v1/organizations/{id}/rate-profile` |
| Dispatch PDF invoices | `POST /api/v1/erp/dispatch/upload` |
| ERP pull dispatch | `POST /api/v1/erp/dispatch` |
| Single invoice (real-time) | `POST /api/v1/mail/invoice/upload` |
| PEPPOL delivery stats | `GET /api/v1/dashboard/{orgId}/peppol-stats` |
| Retry failed delivery | `POST /api/v1/dashboard/{orgId}/retry/{recordId}` |
| Billing summary | `GET /api/v1/billing/summary/{orgId}/{period}` |
| Full API docs | `GET /swagger-ui/index.html` |
