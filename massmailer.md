# InvoiceDirect — Mass Mailer Platform

**Production URL:** `https://ap.invoicedirect.biz`
**Stack:** Spring Boot 4.0.0 · Java 25 (preview) · React 18 + TypeScript · PostgreSQL 16

SaaS platform for fiscalised invoice delivery via **EMAIL** (PDF attachment) and **PEPPOL AS4** (UBL BIS 3.0). Multi-tenant, usage-based billing with tiered rate profiles. ERP integration via pluggable adapters (Hexagonal Architecture).

---

## Table of Contents

1. [Architecture](#1-architecture)
2. [Quick Start](#2-quick-start)
3. [API Reference](#3-api-reference)
4. [Backend Components](#4-backend-components)
5. [ERP Adapters](#5-erp-adapters)
6. [PEPPOL / AS4](#6-peppol--as4)
7. [Billing](#7-billing)
8. [Frontend](#8-frontend)
9. [Security](#9-security)
10. [Deployment & Operations](#10-deployment--operations)
11. [Sub-modules](#11-sub-modules)
12. [Testing](#12-testing)
13. [QA Audit Findings (June 2026)](#13-qa-audit-findings-june-2026)
14. [Configuration Reference](#14-configuration-reference)

---

## 1. Architecture

### Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 4.0.0, Java 25 (virtual threads, structured concurrency, records, sealed interfaces) |
| Frontend | React 18 + TypeScript, Vite 8, React Router 6, Axios |
| Database | PostgreSQL 16 (prod), H2 (dev/test) |
| Mail | JavaMail SMTP (Gmail) with optional OAuth2 XOAUTH2 |
| Templates | Thymeleaf (HTML email), Saxon-HE (Schematron validation) |
| Container | Docker + Docker Compose, multi-stage builds |

### Package Map

```
com.esolutions.massmailer/
├── controller/          # REST endpoints
│   ├── CampaignController
│   ├── SingleMailController
│   ├── ErpCampaignController
│   └── OrgInvoiceDashboardController
├── service/             # Core logic
│   ├── CampaignOrchestrator
│   ├── SmtpSendService
│   ├── TemplateRenderService
│   ├── PdfAttachmentResolver
│   ├── PdfFolderWatcherService
│   ├── DeliveryModeRouter
│   ├── WebhookDeliveryService
│   └── ZimraFiscalValidator
├── model/               # JPA entities (MailCampaign, MailRecipient, etc.)
├── dto/                 # MailDtos (all request/response records)
├── domain/              # CanonicalInvoice, ZimbabweCurrency, ports/ErpInvoicePort
├── config/              # AsyncMailConfig, MailerProperties, OpenApiConfig, GmailOAuth2*
├── security/            # SecurityConfig, ApiKeyAuthFilter, OrgPrincipal, AdminAuthController
├── organization/        # controller/, service/, model/, repository/, dto/, exception/
├── billing/             # controller/, service/, model/ (RateProfile, UsageRecord), repository/
├── customer/            # controller/, service/, model/, repository/, migration/
├── peppol/              # AS4 transport, eRegistry, UBL builder, Schematron validator
├── invitation/          # PEPPOL self-registration invitation flow
├── infrastructure/
│   └── adapters/        # ERP adapters (Odoo, Sage, QB, D365, Generic)
├── listener/            # CampaignEvent sealed interface + listener
└── exception/           # GlobalExceptionHandler
```

### Request Flow

1. Client sends `POST /api/v1/campaigns` (or `/mail/invoice`, `/erp/dispatch`)
2. `CampaignController` delegates to `CampaignOrchestrator` — persists `MailCampaign` + `MailRecipient` records
3. Orchestrator partitions recipients into batches (configurable, default 50)
4. Per recipient via `StructuredTaskScope` on virtual threads:
   - `PdfAttachmentResolver` resolves PDF (file path, Base64, or multipart bytes)
   - `TemplateRenderService` merges Thymeleaf template with invoice fields
   - `SmtpSendService` sends multipart MIME (3x retry with exponential backoff, `Semaphore` rate limiting)
   - `MailRecipient.status` updated to DELIVERED / FAILED / SKIPPED
5. Client polls `GET /api/v1/campaigns/{id}` for progress; failed recipients retried via `POST .../{id}/retry`

### Key Design Patterns

| Pattern | Usage |
|---|---|
| **Hexagonal Architecture** | `ErpInvoicePort` interface; adapters in `infrastructure/adapters/` |
| **Sealed Interface (ADT)** | `DeliveryResult` — exhaustive switch on Delivered/Failed/Skipped |
| **Records (Value Objects)** | All DTOs, config properties, events |
| **Structured Concurrency** | `StructuredTaskScope` for batch dispatch lifecycle |
| **Virtual Threads** | Entire async executor via `SimpleAsyncTaskExecutor` |
| **Strategy** | `PdfAttachmentResolver` — file path / Base64 / bytes |
| **Observer / Event** | `CampaignEvent` sealed hierarchy + `@EventListener` |
| **Retry + Backoff** | `@Retryable` on SMTP sends |
| **Rate Limiting** | `Semaphore`-based throttle; Bucket4j for org registration (10/IP/hr) |

---

## 2. Quick Start

### Prerequisites

- Java 25 (`--enable-preview`), Maven 3.9+, Docker + Docker Compose

### Development (H2 in-memory)

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="--enable-preview"
# API at http://localhost:9199
# Swagger at http://localhost:9199/swagger-ui.html (if SWAGGER_ENABLED=true)
```

### Full-stack with PostgreSQL

```bash
docker compose up --build
# Backend: http://localhost:9199
# Frontend: http://localhost:8199
```

### Send a Test Email

```bash
# Using the test script
./scripts/send-test-email.sh

# Or via curl (replace API_KEY)
curl -X POST http://localhost:9199/api/v1/mail/invoice \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "to": "test@example.com",
    "subject": "Test Invoice",
    "templateName": "invoice",
    "invoiceNumber": "TEST-001",
    "totalAmount": 100.00,
    "currency": "USD"
  }'
```

---

## 3. API Reference

### Campaigns (`/api/v1/campaigns`)

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/campaigns` | Create + dispatch mass invoice campaign (async) | ORG/ADMIN |
| GET | `/api/v1/campaigns` | List all campaigns (newest first, org-scoped) | ORG/ADMIN |
| GET | `/api/v1/campaigns/{id}` | Get campaign delivery status | ORG/ADMIN |
| POST | `/api/v1/campaigns/{id}/retry` | Retry failed recipients (async) | ORG/ADMIN |

### Single Mail (`/api/v1/mail`)

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/mail/invoice` | Send single fiscalised invoice (sync, JSON) | ORG/ADMIN |
| POST | `/api/v1/mail/invoice/upload` | Send single invoice with PDF upload | ORG/ADMIN |

### ERP Dispatch (`/api/v1/erp`)

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/erp/dispatch` | Fetch invoices from ERP + dispatch campaign | ORG/ADMIN |
| POST | `/api/v1/erp/dispatch/upload` | Upload PDFs + metadata, dispatch as campaign | ORG/ADMIN |
| GET | `/api/v1/erp/adapters` | List registered ERP adapters | ORG/ADMIN |
| GET | `/api/v1/erp/health/{erpSource}` | Check ERP adapter connectivity | ORG/ADMIN |

### Org Dashboard (`/api/v1/my`)

| Method | Path | Description | Auth |
|---|---|---|---|
| GET | `/api/v1/my/profile` | Org profile (name, slug, sender, ERP, status) | ORG |
| GET | `/api/v1/my/stats` | Dashboard summary stats | ORG |
| GET | `/api/v1/my/campaigns` | Org campaigns (optional `?status=` filter) | ORG |
| GET | `/api/v1/my/campaigns/{id}` | Campaign detail with invoice records | ORG |
| GET | `/api/v1/my/invoices` | All submitted invoices (`?status=` filter) | ORG |
| GET | `/api/v1/my/invoices/{invoiceNumber}` | Lookup invoice by number | ORG |
| GET | `/api/v1/my/billing` | Current billing summary (`?period=YYYY-MM`) | ORG |
| GET | `/api/v1/my/billing/history` | Full billing history | ORG |
| POST | `/api/v1/my/rotate-api-key` | Rotate API key (5 min grace period) | ORG |
| POST | `/api/v1/my/invitations` | Send PEPPOL invitation to customer | ORG |
| GET | `/api/v1/my/invitations` | List org invitations | ORG |
| DELETE | `/api/v1/my/invitations/{id}` | Cancel pending invitation | ORG |

### Organizations (`/api/v1/organizations`)

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/organizations` | Register new org (rate-limited 10/IP/hr) | PUBLIC |
| GET | `/api/v1/organizations/by-slug/{slug}` | Lookup org by slug | PUBLIC |
| GET | `/api/v1/organizations` | List all orgs | ADMIN |
| GET | `/api/v1/organizations/{id}` | Get org by ID | ADMIN |
| PUT | `/api/v1/organizations/{id}` | Full update org | ADMIN |
| DELETE | `/api/v1/organizations/{id}` | Soft-deactivate org | ADMIN |
| PATCH | `/api/v1/organizations/{id}/rate-profile` | Assign rate profile | ORG/ADMIN |
| PATCH | `/api/v1/organizations/{id}/delivery-mode` | Update delivery mode | ORG/ADMIN |

### Customers (`/api/v1/organizations/{orgId}/customers`)

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `` | Register customer contact | ORG/ADMIN |
| PUT | `/{id}` | Update customer | ORG/ADMIN |
| GET | `` | List customers | ORG/ADMIN |
| GET | `/by-email` | Lookup by email | ORG/ADMIN |
| POST | `/import` | Bulk CSV import (multipart) | ORG/ADMIN |
| POST | `/import/preview` | Preview CSV before import | ORG/ADMIN |
| GET | `/by-tax-id` | Lookup by BPN/VAT/TIN | ORG/ADMIN |

### Billing (`/api/v1/billing`)

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/rate-profiles` | Create tiered rate profile | PUBLIC |
| GET | `/rate-profiles` | List active rate profiles | PUBLIC |
| POST | `/estimate` | Estimate cost for volume | PUBLIC |
| GET | `/summary/{orgId}/{period}` | Billing summary | ORG/ADMIN |
| GET | `/history/{orgId}` | Billing history | ORG/ADMIN |
| POST | `/close/{orgId}/{period}` | Close billing period | ORG/ADMIN |
| GET | `/usage/{orgId}/{period}` | Usage records for audit | ORG/ADMIN |
| POST | `/invoice/{orgId}/{period}` | Generate platform invoice | ORG/ADMIN |
| POST | `/payment/{orgId}/{period}` | Record payment (INVOICED→PAID) | ORG/ADMIN |
| GET | `/report/{period}` | Platform-wide revenue report | ORG/ADMIN |
| GET | `/export/{orgId}/{period}` | Export usage as CSV | ORG/ADMIN |

### Invitations (Public — `/api/v1/invitations`)

| Method | Path | Description | Auth |
|---|---|---|---|
| GET | `/{token}` | Validate invitation token | PUBLIC |
| POST | `/{token}/complete` | Complete PEPPOL self-registration | PUBLIC |

### Admin Auth (`/api/v1/admin`)

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/admin/login` | Login (returns session token) | PUBLIC |
| POST | `/admin/logout` | Invalidate session | ADMIN |
| GET | `/admin/users` | List admin users | ADMIN |
| POST | `/admin/users` | Create admin user | ADMIN |
| PATCH | `/admin/users/{id}/deactivate` | Deactivate admin | ADMIN |
| PATCH | `/admin/users/{id}/reactivate` | Reactivate admin | ADMIN |

### Webhooks (`/webhooks/sage`)

| Method | Path | Description | Auth |
|---|---|---|---|
| POST | `/einvoice-status/{orgId}` | Sage Network e-invoice status push | WEBHOOK |
| POST | `/einvoice-status/{orgId}/poll` | Manual poll Sage e-invoice status | ORG/ADMIN |

### Public / Utility

| Method | Path | Description |
|---|---|---|
| GET | `/actuator/health` | Health check |
| GET | `/actuator/info` | App info |
| GET | `/swagger-ui.html` | Swagger UI (if enabled) |
| GET | `/v3/api-docs` | OpenAPI JSON spec |
| GET | `/peppol/as4/receive` | PEPPOL AS4 inbound endpoint |
| GET | `/peppol/as4/health` | PEPPOL AS4 health check |

### PDF Attachment Modes

| Mode | Field | Endpoint |
|---|---|---|
| File path | `pdfFilePath` | Any JSON endpoint (shared filesystem) |
| Base64 | `pdfBase64` | Any JSON endpoint (max ~15MB decoded) |
| Multipart upload | — | `/mail/invoice/upload`, `/erp/dispatch/upload` |

### Campaign Status Lifecycle

```
CREATED → QUEUED → IN_PROGRESS → COMPLETED
                              \→ PARTIALLY_FAILED
                              \→ FAILED
```

### Recipient Statuses

| Status | Meaning |
|---|---|
| PENDING | Queued, not yet attempted |
| SENT / DELIVERED | Email sent successfully |
| FAILED | Delivery failed (check `errorMessage`) |
| SKIPPED | Skipped (unsubscribed, duplicate, invalid email) |
| BOUNCED | Bounced after initial delivery |

### Error Response Format

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Recipient email is required",
  "path": "/api/v1/mail/invoice"
}
```

| Code | Meaning |
|---|---|
| 400 | Invalid request |
| 403 | Forbidden (bad/missing API key) |
| 404 | Resource not found |
| 409 | Conflict (e.g., duplicate slug) |
| 429 | Rate limited |
| 502 | Bad Gateway (SMTP/ERP unreachable) |
| 503 | Service Unavailable |

---

## 4. Backend Components

### Core Services

| Service | File | Responsibility |
|---|---|---|
| `CampaignOrchestrator` | `service/CampaignOrchestrator.java` | Async batch dispatch, batch partitioning, status tracking |
| `SmtpSendService` | `service/SmtpSendService.java` | MIME email with PDF attachment, @Retryable, semaphore throttle |
| `TemplateRenderService` | `service/TemplateRenderService.java` | Thymeleaf HTML merging with invoice variables |
| `PdfAttachmentResolver` | `service/PdfAttachmentResolver.java` | Resolve PDF from file path, Base64, or raw bytes (Strategy pattern) |
| `DeliveryModeRouter` | `service/DeliveryModeRouter.java` | Routes EMAIL/AS4/BOTH per org + customer preference |
| `ZimraFiscalValidator` | `service/ZimraFiscalValidator.java` | Validates PDF FDMS fiscal markers |
| `PdfFolderWatcherService` | `service/PdfFolderWatcherService.java` | Optional filesystem watcher for auto-dispatch |
| `WebhookDeliveryService` | `service/WebhookDeliveryService.java` | Outbound webhook delivery (internal events) |

### Configuration

| Class | Prefix | Key Fields |
|---|---|---|
| `MailerProperties` | `massmailer` | fromAddress, rateLimit, batchSize, maxRetries, fiscalValidationEnabled, maxPdfBytes |
| `PdfWatcherProperties` | `massmailer.pdf-watcher` | enabled, inboxDirectory, defaultOrganizationId |
| `ErpAdapterProperties` | `erp` | SageConfig, QuickBooksConfig, Dynamics365Config, OdooConfig |
| `AdminProperties` | `admin` | username, password, tokenExpiryHours |

### Async Config

- `@EnableAsync` + `SimpleAsyncTaskExecutor` on virtual threads (Spring Boot virtual thread support enabled)
- Concurrency cap from `MailerProperties.rateLimit`
- `Semaphore` bean `smtpRateLimiter` for fine-grained throttling
- `@EnableRetry` (Spring Retry 2.0.11)
- `@EnableScheduling` (PDF watcher, billing scheduler, invitation expiry, PEPPOL C4 routing job)

### Email Templates (`src/main/resources/templates/email/`)

| Template | Variables | Purpose |
|---|---|---|
| `invoice.html` | companyName, recipientName, invoiceNumber, totalAmount, fiscal fields, paymentInstructions | Main invoice email |
| `platform-invoice.html` | companyName | Platform billing invoice |
| `generic.html` | companyName | Generic fallback |
| `welcome.html` | companyName | Welcome email |
| `peppol-invitation.html` | orgName, customerEmail, inviteUrl, expiresAt | PEPPOL invite email |
| `peppol-invitation-complete.html` | companyName | Registration confirmation |
| `peppol-invoice-notification.html` | recipientName, supplierName, invoiceNumber, totalAmount | PEPPOL receipt notification |

### Data Models

| Entity | Table | Key Fields |
|---|---|---|
| `MailCampaign` | `mail_campaign` | id, name, subject, templateName, organizationId, status, counters |
| `MailRecipient` | `mail_recipient` | campaign FK, email, invoice details, fiscal fields, deliveryStatus |
| `Organization` | `organization` | id, name, slug, apiKey, deliveryMode, peppolParticipantId |
| `CustomerContact` | `customer_contact` | email, name, company, erpCustomerId, deliveryMode, peppolParticipantId |
| `RateProfile` | `rate_profile` | name, currency, monthlyBaseFee, tiers |
| `UsageRecord` | `usage_record` | organizationId, billingPeriod, outcome, billed flag |
| `BillingPeriodSummary` | `billing_period_summary` | organizationId, period, status (OPEN/CLOSED/INVOICED/PAID) |
| `PeppolInvitation` | `peppol_invitation` | token, customerEmail, status, expiresAt |
| `AccessPoint` | `access_point` | participantId, endpointUrl, role |
| `PeppolDeliveryRecord` | `peppol_delivery_record` | documentId, sender/receiver, status |
| `InboundDocument` | `inbound_document` | ublXmlPayload, senderId, receivedAt |

---

## 5. ERP Adapters

### Architecture

All adapters implement `ErpInvoicePort` (in `domain/ports/`) and are auto-discovered by `ErpAdapterRegistry`. New adapters are automatically picked up by Spring when added as beans.

| Adapter | Class | Activation | Status |
|---|---|---|---|
| **Odoo** | `OdooAdapter` | `erp.odoo.base-url` set | **Full implementation** — JSON-RPC (`account.move` fetch, PDF generation) |
| **Sage Intacct** | `SageIntacctAdapter` | `erp.sage.base-url` set | Stub (field mappings defined, TODO implementation) |
| **QuickBooks Online** | `QuickBooksOnlineAdapter` | `erp.quickbooks.client-id` set | Stub (field mappings defined) |
| **Dynamics 365** | `Dynamics365Adapter` | `erp.dynamics365.base-url` set | Stub (PDF resolution works) |
| **Generic API** | `GenericApiAdapter` | Always active | No-op (data in request payload) |

### Port Interface (`ErpInvoicePort`)

| Method | Returns |
|---|---|
| `supports()` | `ErpSource` enum |
| `fetchInvoices(tenantId, invoiceIds)` | `List<CanonicalInvoice>` |
| `fetchInvoice(tenantId, invoiceId)` | `CanonicalInvoice` |
| `fetchInvoicePdfAsBase64(tenantId, invoiceId)` | Base64 PDF string |
| `healthCheck(tenantId)` | `boolean` |

### Sage Network Integration

Separate from `ErpInvoicePort` — `SageNetworkAdapter` polls/submits PEPPOL delivery status to Sage Network's e-invoice API. Receives push notifications via `SageNetworkWebhookController` (`POST /webhooks/sage/einvoice-status/{orgId}`).

### Canonical Invoice (`CanonicalInvoice` in `domain/`)

Normalised representation of an invoice from any ERP source. `ErpSource` enum: `ODOO`, `SAGE_INTACCT`, `QUICKBOOKS_ONLINE`, `DYNAMICS_365`, `GENERIC_API`.

---

## 6. PEPPOL / AS4

All PEPPOL code lives in `com.esolutions.massmailer.peppol` (controllers, AS4 transport, UBL builder, schematron validation, eRegistry, delivery records).

### AS4 Transport

| Component | File |
|---|---|
| `As4TransportClient` (interface) | `peppol/as4/As4TransportClient.java` |
| `As4TransportClientImpl` | `peppol/as4/As4TransportClientImpl.java` |
| `PeppolReceiveController` | `peppol/controller/PeppolReceiveController.java` |
| `PeppolC4RoutingJob` | `peppol/job/PeppolC4RoutingJob.java` |

- Supports simplified HTTP delivery (development) and full AS4 ebMS 3.0 (production)
- XML-DSIG signing implemented; XML-Enc NOT implemented (documented TODO)

### UBL Invoice Builder

| Component | File |
|---|---|
| `UblInvoiceBuilder` | `peppol/ubl/UblInvoiceBuilder.java` |

Builds UBL 2.1 / BIS Billing 3.0 XML invoices. Uses `StringBuilder` with manual escaping (noted in QA audit as MEDIUM risk).

### Schematron Validation

| Component | File |
|---|---|
| `SchematronValidator` (interface) | `peppol/schematron/SchematronValidator.java` |
| `SchematronValidatorImpl` | `peppol/schematron/SchematronValidatorImpl.java` |
| Rules file | `src/main/resources/schematron/PEPPOL-EN16931-UBL.sch` |

The `.sch` file is a **STUB** — marked "NOT FOR PRODUCTION USE", contains no assertions. Production deployment requires the real OpenPEPPOL compiled XSLT from the [OpenPEPPOL repository](https://github.com/OpenPEPPOL/peppol-bis-invoice-3).

### eRegistry (SMP-equivalent)

| Component | File |
|---|---|
| `ERegistryController` | `peppol/controller/ERegistryController.java` |

Local registry of access points, participant links, and delivery records. Endpoints for registering/managing access points, creating participant links, listing deliveries.

### PEPPOL Invitation Flow

Backed by `com.esolutions.massmailer.invitation`:

1. Org sends invitation via `POST /api/v1/my/invitations` with customer email
2. Service creates `PeppolInvitation` (status PENDING, 72h expiry) and sends invitation email with token link
3. Customer opens link, validates via `GET /api/v1/invitations/{token}` (public)
4. Customer completes registration via `POST .../{token}/complete` — creates access point, participant link, updates customer contact
5. `InvitationExpiryJob` (scheduled) cleans up expired invitations

### PEPPOL Delivery Dashboard

`OrgDeliveryDashboardController` — PEPPOL delivery stats, failed deliveries, retry capabilities for org-scoped view.

---

## 7. Billing

### Rate Profiles

Tiered pricing structure created via `POST /api/v1/billing/rate-profiles`:

```json
{
  "name": "Standard",
  "currency": "USD",
  "monthlyBaseFee": 25.00,
  "tiers": [
    { "fromInvoice": 1, "toInvoice": 500, "ratePerInvoice": 0.10 },
    { "fromInvoice": 501, "toInvoice": 2000, "ratePerInvoice": 0.07 },
    { "fromInvoice": 2001, "toInvoice": null, "ratePerInvoice": 0.04 }
  ]
}
```

### Billing Period Lifecycle

```
OPEN → CLOSED → INVOICED → PAID
```

- Periods are monthly (`YYYY-MM` format)
- Metering via `MeteringService` — records `UsageRecord` per delivery outcome
- Billable outcomes: DELIVERED and FAILED (SKIPPED is not billable)
- Scheduled via `BillingScheduler`
- CSV export via `GET /api/v1/billing/export/{orgId}/{period}`
- Platform-wide revenue reporting via `GET /api/v1/billing/report/{period}`

### Key Services

| Service | File |
|---|---|
| `BillingService` | `billing/service/BillingService.java` |
| `MeteringService` | `billing/service/MeteringService.java` |
| `BillingScheduler` | `billing/service/BillingScheduler.java` |
| `PlatformInvoiceService` | `billing/service/PlatformInvoiceService.java` |

---

## 8. Frontend

### Tech Stack

React 18 + TypeScript, Vite 8, React Router 6, Axios, Lucide React icons.

### Routes

| Route | Component | Description |
|---|---|---|
| `/login` | `LoginPage` | Org login by slug + API key |
| `/register` | `RegisterPage` | Org self-registration |
| `/dashboard` | `DashboardPage` | Stats overview, recent campaigns, failed deliveries |
| `/campaigns` | `CampaignsPage` | Campaign list with detail modal, retry |
| `/invoices` | `InvoicesPage` | Sent invoices, search by invoice number, PDF preview |
| `/send` | `SendTestPage` | Single invoice send with PDF upload |
| `/customers` | `CustomersPage` | Customer CRUD, PEPPOL invitations, CSV import |
| `/api-docs` | `ApiDocsPage` | Swagger UI iframe |
| `/billing` | `BillingPage` | Billing summary, usage, cost estimator |
| `/invite/peppol/:token` | `PeppolInvitePage` | Public PEPPOL self-registration |
| `/admin/login` | `AdminLoginPage` | Admin username/password login |
| `/admin/organizations` | `OrganizationsPage` | CRUD orgs, assign rate profiles |
| `/admin/rate-profiles` | `RateProfilesPage` | Tiered rate profile management |
| `/admin/billing` | `BillingPage` | (admin) billing summaries, usage records |
| `/admin/campaigns` | `AdminCampaignsPage` | Platform-wide campaign view |
| `/admin/peppol` | `PeppolPage` | Access points, participant links, delivery history |

### Key Components

| Component | File | Purpose |
|---|---|---|
| `Layout` | `components/Layout.tsx` | Dark sidebar with nav sections |
| `CustomerImportModal` | `pages/CustomerImportModal.tsx` | 3-step CSV import wizard |
| `AuthContext` | `context/AuthContext.tsx` | Session state management (localStorage) |

### API Client

`api/client.ts` — Axios instance with:
- Base path `/api/v1`
- Auto-attaches `X-API-Key` from localStorage
- On 401, clears session and redirects to login
- Functions organized by domain (admin, orgs, customers, campaigns, ERP, mail, billing, PEPPOL, invitations)

### Dev vs Production

| Mode | Command | Port |
|---|---|---|
| Dev | `npm run dev` (Vite) | 3000 (proxies `/api`, `/peppol` to backend 9199) |
| Production | nginx (`mailer.conf`) | 8199 (reverse-proxies to backend) |

---

## 9. Security

### Authentication

Two authentication modes, checked by `ApiKeyAuthFilter`:

1. **Organization API Key** (header `X-API-Key`) — matched against `organization.apiKey` or `organization.previousApiKey` (5 min grace period after rotation). Grants `ROLE_ORG`.
2. **Admin Session Token** (header `X-API-Key`) — SHA-256 hash matched against `admin_session_tokens` table. Grants `ROLE_ADMIN`.

### Authorization

| Role | Accessible Paths |
|---|---|
| PUBLIC | `POST /api/v1/organizations`, `GET /by-slug/*`, `/actuator/*`, `/swagger-ui/**`, `/v3/api-docs/**`, `/peppol/as4/receive`, `/peppol/as4/health`, `/webhooks/**`, `POST /api/v1/admin/login`, `/api/v1/invitations/**`, `POST /api/v1/billing/rate-profiles`, `GET .../estimate` |
| ROLE_ORG | `/api/v1/my/**`, `/api/v1/campaigns/**`, `/api/v1/mail/**`, `/api/v1/erp/**`, `/api/v1/billing/**`, `/api/v1/organizations/**` (owned) |
| ROLE_ADMIN | All `/api/v1/admin/**`, all `/api/v1/organizations/**`, all `/api/v1/billing/**` |

### Security Configuration (`SecurityConfig`)

- CSRF disabled (stateless API)
- Stateless sessions (`SessionCreationPolicy.STATELESS`)
- BCryptPasswordEncoder(12)
- `ApiKeyAuthFilter` before `UsernamePasswordAuthenticationFilter`

### Admin Session Management

| Component | File |
|---|---|
| `AdminTokens` | `security/AdminTokens.java` — 32-byte SecureRandom token generation, SHA-256 hashing |
| `AdminBootstrapService` | Seeds initial admin user from env vars on `ApplicationReadyEvent` |
| `AdminUser` / `AdminSessionToken` | JPA entities in `admin_users` / `admin_session_tokens` tables |

---

## 10. Deployment & Operations

### Production URLs

| Service | URL |
|---|---|
| Backend API | `https://ap.invoicedirect.biz` (port 9199) |
| Frontend | `https://ap.invoicedirect.biz` (port 8199 via nginx) |

### Docker Deployment

```bash
# Development (PostgreSQL container)
docker compose up --build

# Production (external PostgreSQL)
docker compose -f docker-compose.prod.yml up -d
```

Three services: `postgres` (16-alpine), `mass-mailer` (Java backend, port 9199), `frontend` (nginx React SPA, port 8199).

### Dockerfile (Backend)

- Multi-stage: Maven 3.9 + Eclipse Temurin 25 JDK → Temurin 25 JRE (alpine)
- ZGC garbage collector, 512m heap, `--enable-preview`
- Non-root `mailer` user
- Port 9199

### Dockerfile (Frontend)

- Multi-stage: Node 20-alpine build → nginx:alpine runtime
- Serves `/app/dist` via `mailer.conf` (reverse-proxies API calls)

### Bare-metal Deployment (`deploy-native.sh`)

1. Maven `mvn clean package`
2. Frontend `npm run build`
3. Create `mailer` system user + directories
4. Write env config to `/etc/massmailer.env`
5. systemd service for JAR
6. nginx SSL reverse proxy

### Health Checks

```
GET /actuator/health       # Backend liveness
GET /actuator/info          # App info
GET /actuator/metrics       # Metrics (if enabled)
GET /peppol/as4/health      # PEPPOL AS4 health
```

### Key Environment Variables

```
# Database
DB_URL=jdbc:postgresql://postgres:5432/massmailer
DB_USER=mailer
DB_PASS=<strong-password>

# SMTP (Gmail)
GMAIL_APP_PASSWORD=<16-char-app-password>

# Admin
ADMIN_USERNAME=admin
ADMIN_PASSWORD=<strong-password>
ADMIN_TOKEN_EXPIRY_HOURS=8

# Webhook
WEBHOOK_SECRET=<32+ chars>

# Throughput
RATE_LIMIT=100
BATCH_SIZE=50

# PDF Watcher (optional)
PDF_WATCHER_ENABLED=false
PDF_WATCHER_INBOX=/var/lib/invoicedirect/inbox

# Application
APP_BASE_URL=https://ap.invoicedirect.biz
SERVER_PORT=9199
HIBERNATE_DDL_AUTO=update
SWAGGER_ENABLED=false
```

### SMTP Providers

Default configuration uses Gmail SMTP (`smtp.gmail.com:587`) with App Password. Optional Gmail OAuth2 XOAUTH2 (config class `GmailOAuth2MailConfig` activated via `massmailer.gmail-oauth2.enabled=true` — token provider is a placeholder stub).

Google Workspace sending limits:

| Edition | Daily Limit |
|---|---|
| Business Starter | 500 |
| Business Standard/Plus | 2,000 |
| Enterprise | 2,000 |

### Systemd Service

Created by `deploy-native.sh`:

```ini
[Unit]
Description=InvoiceDirect Mass Mailer
After=postgresql.service

[Service]
User=mailer
EnvironmentFile=/etc/massmailer.env
ExecStart=/usr/bin/java --enable-preview -XX:+UseZGC -Xmx512m -jar /opt/massmailer/mass-mailer.jar
Restart=always

[Install]
WantedBy=multi-user.target
```

---

## 11. Sub-modules

### invoice-forwarder

Standalone Spring Boot 3.3.5 service (port 9099) that watches a local inbox directory for PDFs and forwards them to the main mailer API.

- No database — uses SQLite ledger for deduplication
- Configurable batch window (default 30s) and status poll (60s)
- Dockerfile + docker-compose.yml included
- Endpoints: `GET /status`, `GET /status/recent`, `GET /actuator/health`

### pdf-watcher-agent

Lightweight standalone agent (no Spring Boot, Java 17, fat JAR) — watches a folder for fiscalised invoice PDFs and forwards them to InvoiceDirect cloud service.

- Designed to run as Windows service or systemd
- Dependencies: Jackson, Logback

### test-client

Single-page HTML/JS test client (`test-client/index.html`) for manual API testing:
- API base URL and API key input
- Email details form with fiscal fields
- PDF drag-and-drop upload
- Batch CSV mode
- Calls `POST /api/v1/mail/invoice/upload`

### Postman Collection

`postman/mass-mailer-api.postman_collection.json` — pre-built collection with all endpoints, auto-saved variables, and examples.

### Utility Scripts

| Script | Purpose |
|---|---|
| `scripts/setup-db.sh` | Create PostgreSQL database + grant privileges |
| `scripts/export-openapi-spec.sh` | Export OpenAPI JSON + YAML from running instance |
| `scripts/send-test-email.sh` | Generate test PDF + send via API |

---

## 12. Testing

### Test Configuration

- **Database:** H2 in-memory (`src/test/resources/application.yml`)
- **SMTP:** GreenMail embedded SMTP on port 3025
- **Profile:** Relaxed limits (rate-limit=5, batch-size=10), fiscal validation disabled

### Test Types

| Type | Pattern | Framework |
|---|---|---|
| Property-based | `*PropertyTest.java` | jqwik 1.9.2 + jqwik-spring 0.12.0 |
| Integration | `*IntegrationTest.java` | Spring Boot Test + GreenMail |

### Property Test Coverage (30+ properties across domains)

| Domain | Properties |
|---|---|
| **Campaign** (P1-P12) | Completeness invariant, terminal status, delivery correlation, metering coverage, billable outcomes, PDF magic bytes, fiscal validation gate, customer registry idempotency, unsubscribe enforcement, delivery mode routing, retry idempotency, currency mapping |
| **Admin Auth** (15 properties) | Authentication, session tokens, user CRUD, deactivation, minimum admin enforcement |
| **PEPPOL Invitation** (18 properties) | Token lifecycle, invitation expiry, registration completeness |

---

## 13. QA Audit Findings (June 2026)

Audit performed 2026-06-05 by the `peppol-mail-qa-auditor` agent. Findings are tracked but **unresolved**.

### CRITICAL

1. **Schematron stub** — `PEPPOL-EN16931-UBL.sch` contains no assertions; all invoices pass validation silently. Must be replaced with real OpenPEPPOL XSLT.
2. **Path traversal** — `PdfAttachmentResolver.resolveFromFile()` has no directory allowlist.
3. **AS4 payload unencrypted** — XML-Enc not implemented; messages are signed but not encrypted.
4. **PEPPOL /as4/inbox unauthenticated** — `GET /peppol/as4/receive` returns all inbound documents (PII leak).
5. **UBL XML in plaintext** — `InboundDocument.ublXmlPayload` stored as TEXT column (PII exposure).

### HIGH

6. **Weak session token RNG** — `UUID.randomUUID()` used; should be `SecureRandom`.
7. **No Base64 size limit** — PDF decode can allocate ~18MB per virtual thread.
8. **`@Async @Transactional` fragility** — delicate pattern on `dispatchCampaign` / `retryFailed`.
9. **`application.yml` fallback defaults** — `DB_PASS=mailer_secret`, `ADMIN_PASSWORD=changeme`.
10. **Swagger UI public by default** — exposes try-it-out, should require auth in production.

### MEDIUM

11. **Null principal in access check** — `canAccessCampaign` returns true when principal is null.
12. **Race condition on counters** — `MailCampaign.incrementSent/Failed/Skipped` are plain `int` fields mutated by multiple virtual threads.
13. **Manual XML building** — `UblInvoiceBuilder` uses `StringBuilder` with string concatenation for XML.
14. **String-based invoice ID extraction** — uses `indexOf` rather than proper XML parsing.
15. **PEPPOL sender auth** — checks local eRegistry only, not AS4 WS-Security signature.

### Compliance Posture Summary

| Area | Status |
|---|---|
| PEPPOL UBL BIS 3.0 | Implemented |
| Schematron validation | Infra present, **stub ruleset** |
| AS4 message signing | Implemented |
| AS4 payload encryption | **Not implemented** |
| SMP/SML lookup | **Not implemented** |
| ZIMRA fiscal fields | Pass |
| PDF fiscalisation validation | Implemented |
| OAuth2 Gmail | **Placeholder only** |
| Org isolation | Pass |
| Rate limiting | Pass |
| Retry with backoff | Pass |

---

## 14. Configuration Reference

### `application.yml` Key Properties

| Property | Env Var | Default | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | 9199 | HTTP port |
| `app.base-url` | `APP_BASE_URL` | `https://ap.invoicedirect.biz` | Public-facing base URL |
| `spring.mail.host` | `SMTP_HOST` | `smtp.gmail.com` | SMTP host |
| `spring.mail.port` | `SMTP_PORT` | 587 | SMTP port |
| `spring.mail.username` | `SMTP_USERNAME` | `no-reply@esolutions.co.zw` | SMTP username |
| `spring.mail.password` | `GMAIL_APP_PASSWORD` | — | SMTP password |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://postgres:5432/massmailer` | Database URL |
| `spring.jpa.hibernate.ddl-auto` | `HIBERNATE_DDL_AUTO` | `update` | Schema management |
| `massmailer.from-address` | `MAIL_FROM` | — | Sender email |
| `massmailer.from-name` | `MAIL_FROM_NAME` | eSolutions Invoice Mailer | Sender display name |
| `massmailer.rate-limit` | `RATE_LIMIT` | 100 | Max concurrent SMTP sends |
| `massmailer.batch-size` | `BATCH_SIZE` | 50 | Recipients per batch |
| `massmailer.max-retries` | — | 3 | Retry attempts per recipient |
| `massmailer.retry-backoff` | — | 2000 | Initial retry backoff (ms) |
| `massmailer.fiscal-validation-enabled` | — | true | Enable ZIMRA fiscal validation |
| `massmailer.max-pdf-bytes` | — | 10MB | Max PDF attachment size |
| `massmailer.gmail-oauth2.enabled` | — | false | Enable Gmail OAuth2 |
| `webhook.secret` | `WEBHOOK_SECRET` | — | Webhook signature secret |
| `pdf-watcher.enabled` | `PDF_WATCHER_ENABLED` | false | Enable PDF folder watcher |
| `admin.username` | `ADMIN_USERNAME` | admin | Initial admin username |
| `admin.password` | `ADMIN_PASSWORD` | — | Initial admin password |
| `admin.token-expiry-hours` | `ADMIN_TOKEN_EXPIRY_HOURS` | 8 | Admin session TTL |
| `spring.servlet.multipart.max-file-size` | — | 20MB | Max upload file size |
| `spring.servlet.multipart.max-request-size` | — | 25MB | Max multipart request size |

### ERP Adapter Config (all env-variable driven)

| Property | Env Var | Adapter |
|---|---|---|
| `erp.sage.base-url` | `ERP_SAGE_BASE_URL` | Sage Intacct |
| `erp.sage.network-base-url` | `ERP_SAGE_NETWORK_BASE_URL` | Sage Network |
| `erp.quickbooks.base-url` | `ERP_QB_BASE_URL` | QuickBooks Online |
| `erp.dynamics365.base-url` | `ERP_D365_BASE_URL` | Dynamics 365 |
| `erp.odoo.base-url` | `ERP_ODOO_BASE_URL` | Odoo |
| `erp.odoo.database` | `ERP_ODOO_DATABASE` | Odoo |
| `erp.odoo.api-key` | `ERP_ODOO_API_KEY` | Odoo |

---

> Last updated: June 2026
> Maintained by eSolutions
> For support: support@esolutions.co.zw
