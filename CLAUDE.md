# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Backend (Maven — Java 25 with preview features)

```bash
mvn clean compile                                                      # Compile
mvn clean test                                                         # Run all tests
mvn test -Dtest=CampaignPropertyTest                                   # Run a single test class
mvn spring-boot:run -Dspring-boot.run.jvmArguments="--enable-preview" # Run locally (H2 in-memory DB)
mvn clean package -DskipTests                                          # Build production JAR
```

### Frontend (npm — Vite/React/TypeScript)

```bash
cd frontend
npm install          # Install dependencies
npm run dev          # Dev server on port 3000 (proxies /api to localhost:8080)
npm run build        # Production build (TypeScript check + Vite bundle)
npm run typecheck    # Type-check only (no emit)
npm run preview      # Preview production build
```

### Docker (recommended for full-stack with PostgreSQL)

```bash
docker compose up --build   # Start postgres + backend + frontend
```

## Architecture

**eSolutions Mass Mailer** is a SaaS platform for sending fiscalised invoice PDFs via email, compliant with Zimbabwe's ZIMRA fiscal regulations. It supports batch ERP integrations (Odoo, Sage, QuickBooks, Dynamics 365) and a React admin UI.

### Stack

- **Backend**: Spring Boot 4.0, Java 25 (preview features on — virtual threads, structured concurrency, records, sealed interfaces)
- **Frontend**: React 18 + TypeScript, Vite, React Router, Axios
- **Database**: PostgreSQL (production) / H2 (dev/test)
- **Email**: JavaMail SMTP with optional Gmail OAuth2
- **Templates**: Thymeleaf for HTML email rendering

### Request Flow

1. ERP or admin sends `POST /api/v1/campaigns` with recipient list and PDF attachments (file paths or Base64).
2. `CampaignController` delegates to `CampaignOrchestrator`, which persists a `MailCampaign` + `MailRecipient` records.
3. Orchestrator partitions recipients into batches (default: 50) and dispatches via Java 25 `StructuredTaskScope` on virtual threads.
4. Per recipient:
   - `PdfAttachmentResolver` reads the PDF (file path or Base64 decode — Strategy pattern)
   - `TemplateRenderService` merges the Thymeleaf template with invoice fields
   - `SmtpSendService` sends multipart MIME (retries 3×, exponential backoff, `Semaphore` rate limiting)
   - `MailRecipient.status` updated to DELIVERED / FAILED / SKIPPED
5. ERP polls `GET /api/v1/campaigns/{id}` for progress; failed recipients can be retried via `POST /api/v1/campaigns/{id}/retry`.
6. Single synchronous sends go through `POST /api/v1/mail/invoice` → `SingleMailController`.

### Key Packages (`src/main/java/com/esolutions/massmailer/`)

| Package | Responsibility |
|---------|---------------|
| `controller/` | REST endpoints — campaigns, single mail, PEPPOL, organisations, admin auth |
| `service/` | Core logic — `CampaignOrchestrator`, `SmtpSendService`, `TemplateRenderService`, `PdfAttachmentResolver` |
| `erp/` | Anti-corruption adapters for Odoo, Sage, QuickBooks, Dynamics 365 → `CanonicalInvoice` |
| `peppol/` | PEPPOL/AS4 e-invoice network — schematron validation (Saxon-HE), inbound/outbound |
| `organization/` | Multi-tenant org management, billing metering, usage records |
| `security/` | Admin session token auth, API key security |
| `model/` | JPA entities (`MailCampaign`, `MailRecipient`, `Organization`, etc.) |
| `dto/` | Request/response records (Java records as value objects) |
| `event/` | `CampaignEvent` sealed interface + `@EventListener` lifecycle hooks |

### Domain Patterns

- **`DeliveryResult`** — sealed interface ADT (Delivered / Failed / Skipped) enabling exhaustive pattern matching
- **All DTOs** are Java records
- **`@Retryable`** on SMTP send with `Semaphore`-based rate limiting to avoid provider blacklisting
- **Hibernate DDL**: `ddl-auto: update` — schema evolves automatically; no manual migrations

### Frontend (`frontend/src/`)

- `pages/` — Dashboard, Campaigns, Admin login, PEPPOL, Billing
- `api/client.ts` — Axios instance (proxied to backend in dev via Vite config)
- `context/AuthContext.tsx` — admin session state
- `types.ts` — shared TypeScript types matching backend DTOs

### Testing

- **Property-based tests** (jqwik): `*PropertyTest.java` — campaign logic, PDF resolution, fiscal validation, ERP adapters
- **Integration tests**: `*IntegrationTest.java` — full send cycle using GreenMail embedded SMTP (no external dependencies needed)
- Test database: H2 in-memory (`src/test/resources/application.yml`)

### Configuration

All secrets are injected via environment variables (see `.env`). Key vars: `DB_URL`, `DB_USER`, `DB_PASS`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `GOOGLE_OAUTH2_REFRESH_TOKEN`.

API docs available at runtime: `http://localhost:8080/swagger-ui.html`
