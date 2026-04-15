# eSolutions Fiscalised Invoice Mass Mailer

Production-grade microservice for mass-mailing fiscalised invoice PDFs to customers, built with **Spring Boot 3.4** and **Java 25**. Each recipient receives a personalised HTML email with their unique invoice PDF attached, sent from a no-reply address.

---

## API Documentation (Swagger UI)

Once the service is running, **full interactive API documentation** is available at:

| Resource | URL | Purpose |
|---|---|---|
| **Swagger UI** | `http://localhost:8080/swagger-ui.html` | Interactive API explorer with "Try it out" |
| **OpenAPI JSON** | `http://localhost:8080/v3/api-docs` | Import into Postman / Insomnia / any client |
| **OpenAPI YAML** | `http://localhost:8080/v3/api-docs.yaml` | Share spec file with integration team |

### For ERP Integration Specialists

Open the Swagger UI to find:
- Every endpoint with full request/response schemas
- Multiple example payloads (file-path mode vs Base64 mode)
- Field-level documentation for all fiscal compliance fields (ZIMRA)
- Error response codes and their meaning
- "Try it out" buttons for live testing

The OpenAPI JSON/YAML can be imported directly into Postman to generate a ready-to-use collection.

### Developer Integration Resources

| Resource | Path | Description |
|----------|------|-------------|
| **API Integration Guide** | [`docs/API-INTEGRATION-GUIDE.md`](docs/API-INTEGRATION-GUIDE.md) | Step-by-step guide: first API call in 5 minutes, auth, error handling, rate limits |
| **Postman Collection** | [`postman/mass-mailer-api.postman_collection.json`](postman/mass-mailer-api.postman_collection.json) | Pre-built collection with all endpoints, auto-saved variables, and examples |
| **Export OpenAPI Spec** | `./scripts/export-openapi-spec.sh` | Export `openapi.json` and `openapi.yaml` from a running instance for offline use or SDK generation |

**Quick import into Postman:** File > Import > select `postman/mass-mailer-api.postman_collection.json`

---

## ERP Integration Flow

```
┌──────────────────────┐     ┌──────────────────────────┐     ┌─────────────┐
│  ERP / Odoo / Sage   │     │   Mass Mailer Service    │     │   Customer  │
│                      │     │                          │     │   Inbox     │
│ 1. Generate invoice  │     │                          │     │             │
│ 2. Fiscal device     │     │                          │     │             │
│    signs it          │     │                          │     │             │
│ 3. Write PDF to disk │     │                          │     │             │
│         │            │     │                          │     │             │
│         ▼            │     │                          │     │             │
│ 4. POST /api/v1/     │────▶│ 5. Resolve PDF           │     │             │
│    campaigns         │     │ 6. Render HTML template  │     │             │
│    (or /mail/invoice │     │ 7. Attach PDF to email   │     │             │
│     for single)      │     │ 8. Send via SMTP         │────▶│ 9. Email    │
│         │            │     │         │                │     │    arrives  │
│         ▼            │     │         ▼                │     │    with PDF │
│ 10. GET /api/v1/     │────▶│ 11. Return status        │     │             │
│     campaigns/{id}   │     │     (sent/failed/skip)   │     │             │
│         │            │     │                          │     │             │
│         ▼            │     │                          │     │             │
│ 12. POST /retry      │────▶│ 13. Re-send failures     │     │             │
│     (if any failed)  │     │                          │     │             │
└──────────────────────┘     └──────────────────────────┘     └─────────────┘
```

### PDF Attachment Modes

Your ERP can supply each recipient's invoice PDF in two ways:

| Mode | Field | When to Use |
|---|---|---|
| **File Path** | `pdfFilePath` | ERP and Mass Mailer share a filesystem (Docker volume, NFS mount). ERP writes PDF to e.g. `/var/lib/odoo/invoices/INV-001.pdf` |
| **Base64** | `pdfBase64` | Cloud/remote ERP with no shared filesystem. Encode the PDF bytes as Base64 and send inline in the JSON payload |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     REST API Layer (OpenAPI 3.0 documented)                 │
│   CampaignController                    SingleMailController                │
│   POST /api/v1/campaigns                POST /api/v1/mail/invoice           │
│   GET  /api/v1/campaigns/{id}                                               │
│   POST /api/v1/campaigns/{id}/retry                                         │
└──────────────┬───────────────────────────────────┬──────────────────────────┘
               │                                   │
               ▼                                   ▼
┌──────────────────────────┐  ┌─────────────────────────────┐
│   CampaignOrchestrator   │  │   TemplateRenderService     │
│   - Batch partitioning   │  │   - Thymeleaf HTML merge    │
│   - StructuredTaskScope  │  │   - Invoice + fiscal fields │
│   - Status tracking      │  └─────────────────────────────┘
└──────────────┬───────────┘
               │
       ┌───────┴────────┐
       ▼                ▼
┌────────────────┐  ┌─────────────────────────┐  ┌─────────────────────┐
│ PdfAttachment  │  │    SmtpSendService      │  │ CampaignEvent       │
│ Resolver       │  │  - Multipart MIME       │  │ Listener            │
│ - File path    │  │  - PDF attachment       │  │ - Lifecycle logging │
│ - Base64       │  │  - No-reply headers     │  │ - Extensible        │
│ - PDF validate │  │  - @Retryable + backoff │  │   (webhooks)        │
└────────────────┘  │  - Semaphore throttle   │  └─────────────────────┘
                    └─────────────────────────┘
```

### Key Patterns

| Pattern | Usage |
|---|---|
| **Sealed Interface (ADT)** | `DeliveryResult` — exhaustive switch on Delivered/Failed/Skipped with invoice correlation |
| **Records (Value Objects)** | All DTOs, config properties, events — zero-boilerplate + OpenAPI `@Schema` |
| **Structured Concurrency** | `StructuredTaskScope` for batch dispatch lifecycle management |
| **Virtual Threads (Loom)** | Entire async executor runs on virtual threads — scales to thousands of concurrent SMTP sends |
| **Strategy Pattern** | `PdfAttachmentResolver` — file path vs Base64 resolution strategies |
| **Observer / Event** | `CampaignEvent` sealed hierarchy + `@EventListener` for lifecycle hooks |
| **Retry with Backoff** | `@Retryable` on SMTP sends — transient failure recovery |
| **Rate Limiting** | `Semaphore`-based throttle prevents SMTP blacklisting |
| **Template Method** | Thymeleaf templates with campaign + per-recipient invoice merge |

---

## Quick Start

### Prerequisites

- **Java 25** (with `--enable-preview` for structured concurrency)
- **Maven 3.9+**
- SMTP credentials (Gmail App Password, AWS SES, SendGrid, Mailgun)
- PostgreSQL 14+ (or use H2 for development)

### 1. Configure SMTP

```bash
export SMTP_HOST=smtp.gmail.com
export SMTP_PORT=587
export SMTP_USERNAME=noreply@yourdomain.com
export SMTP_PASSWORD=your-app-password
export MAIL_FROM=noreply@yourdomain.com
export MAIL_FROM_NAME="eSolutions"
```

### 2. Run (H2 in-memory — development)

```bash
cd mass-mailer
mvn spring-boot:run -Dspring-boot.run.jvmArguments="--enable-preview"
```

Then open: **http://localhost:8080/swagger-ui.html**

### 3. Run with Docker Compose (PostgreSQL — production)

```bash
docker compose up --build
```

---

## API Quick Reference

Full details and examples are in the Swagger UI. Here's a summary:

### Send Single Invoice Email

```
POST /api/v1/mail/invoice
```

Synchronous — returns delivery result immediately. Ideal for real-time dispatch from POS/ERP.

```bash
curl -X POST http://localhost:8080/api/v1/mail/invoice \
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

### Create Mass Invoice Campaign

```
POST /api/v1/campaigns
```

Asynchronous — returns immediately with campaign ID. Poll status to track progress.

### Poll Campaign Status

```
GET /api/v1/campaigns/{campaignId}
```

### Retry Failed Invoices

```
POST /api/v1/campaigns/{campaignId}/retry
```

### List All Campaigns

```
GET /api/v1/campaigns
```

---

## Fiscal Compliance Fields (ZIMRA)

The invoice email template renders these fields in a dedicated "Fiscal Verification" box:

| API Field | Template Variable | Description |
|---|---|---|
| `fiscalDeviceSerialNumber` | `${fiscalDeviceSerialNumber}` | Serial number of the fiscal device that signed the invoice |
| `fiscalDayNumber` | `${fiscalDayNumber}` | Fiscal day counter on the device |
| `globalInvoiceCounter` | `${globalInvoiceCounter}` | Sequential invoice counter across all fiscal days |
| `verificationCode` | `${verificationCode}` | Code for online verification with ZIMRA |
| `qrCodeUrl` | `${qrCodeUrl}` | URL to the QR code image — rendered as a scannable image in the email |

---

## No-Reply Email Headers

Every email includes these headers:

| Header | Value | Purpose |
|---|---|---|
| `From` | `noreply@...` | No-reply sender identity |
| `Reply-To` | `noreply@...` | Prevents reply delivery |
| `Auto-Submitted` | `auto-generated` | RFC 3834 — suppresses auto-replies |
| `X-Auto-Response-Suppress` | `All` | Microsoft/Exchange OOF suppression |
| `Precedence` | `bulk` | Signals bulk mail to MTAs |
| `X-Invoice-Number` | `INV-2026-0042` | Custom header for traceability / audit |

---

## Configuration Reference

| Property | Env Var | Default | Description |
|---|---|---|---|
| `massmailer.from-address` | `MAIL_FROM` | (required) | No-reply sender email |
| `massmailer.from-name` | `MAIL_FROM_NAME` | eSolutions | Display name in From header |
| `massmailer.rate-limit` | `RATE_LIMIT` | 10 | Max concurrent SMTP connections |
| `massmailer.batch-size` | `BATCH_SIZE` | 50 | Recipients per batch |
| `massmailer.max-retries` | — | 3 | Retry ceiling per recipient |
| `massmailer.retry-backoff` | — | 2000 | Initial backoff (ms), 2x multiply |

---

## Running Tests

```bash
mvn test
```

Tests use **GreenMail** (embedded SMTP server) — no external mail server needed. Integration tests verify PDF attachment, multipart MIME, fiscal headers, and campaign lifecycle.

---

## Production Considerations

1. **SMTP Provider**: Use a transactional email service (AWS SES, SendGrid, Mailgun) for deliverability reputation.
2. **Unsubscribe Compliance**: Add `List-Unsubscribe` header per RFC 8058 / CAN-SPAM / GDPR requirements.
3. **Bounce Handling**: Integrate SNS/webhook from your ESP to update `RecipientStatus.BOUNCED`.
4. **Database**: Switch to PostgreSQL via `DB_URL` env var for persistence.
5. **Monitoring**: Actuator endpoints at `/actuator/health` and `/actuator/metrics`.
6. **Security**: Add Spring Security + API key authentication before exposing externally.
7. **PDF Storage**: For large campaigns (1000+), consider object storage (S3/MinIO) instead of local filesystem for PDFs.
8. **Queue-Based**: For very large campaigns (100k+), replace async dispatch with a message broker (Kafka/RabbitMQ) using the Outbox pattern.
