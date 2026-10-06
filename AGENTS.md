# AGENTS.md — InvoiceDirect Mass Mailer / PEPPOL Access Point

Guidance for coding agents working in this repo. Keep it current: update this
file in the same PR whenever you change mail transport, sender resolution,
build/test commands, or deployment.

## What this is

InvoiceDirect (`https://ap.invoicedirect.biz`) delivers fiscalised invoices for
multiple organisations (multi-tenant): by **email** (bulk campaigns, single
sends, ERP-driven dispatch) and by **PEPPOL e-delivery** (UBL 2.1, AS4, SMP/SML).

## Repo layout

| Path | What |
|---|---|
| `massmailer/` | Main Spring Boot 4 service (Java 25) + `frontend/` (React 18 + Vite + TS) |
| `pdf-watcher-common/` | Shared lib — **sibling** of `massmailer/`, installed before building it |
| `pdf-watcher-agent/` | Client-side folder watcher that pushes PDFs to the API |
| `invoice-forwarder/` | Separate forwarder service using `MassMailerClient` |
| `peppol/`, `postman/`, `test-client/`, `uat-documents/` | Docs, API collection, test harnesses |

Main packages under `massmailer/src/main/java/com/esolutions/massmailer/`:
`controller` (REST), `service` (orchestration + mail), `brevo` (Brevo API client +
sender resolution), `organization`, `customer`, `peppol`, `invitation`,
`billing`, `security`, `infrastructure/adapters` (ERP adapters: Sage, QuickBooks,
D365, Odoo).

## Build & test

- **JDK 25 is required** (`--enable-preview`; `StructuredTaskScope`). A newer
  default JDK fails with *"invalid source release 25 with --enable-preview"*.
  On macOS: `JAVA_HOME=$(/usr/libexec/java_home -v 25)`.
- `mvn install -f pdf-watcher-common/pom.xml` once, then from `massmailer/`:
  - `mvn test` — full suite (H2, no network; ~180 tests)
  - `mvn test -Dtest=ClassName` — single class
- Tests use `src/test/resources/application.yml` (H2, SMTP on localhost, Brevo off).

## Outbound email — Brevo transactional API (production)

Production sends **all** mail over HTTPS to Brevo, not SMTP:
`POST https://api.brevo.com/v3/smtp/email` with headers `api-key`,
`content-type: application/json`, `accept: application/json`.
Docs: https://developers.brevo.com/reference/sendtransacemail

- Switch: `massmailer.brevo.enabled` ← `BREVO_ENABLED` (**must be `true` in prod**;
  `deploy.sh`/`deploy-native.sh` refuse to deploy otherwise), key ← `BREVO_API_KEY`.
  When disabled (local/tests) mail falls back to Spring `JavaMailSender` (SMTP);
  `SPRING_MAIL_PASSWORD` is only needed then.
- `brevo/BrevoEmailClient` — the only Brevo HTTP code. Payload rules (pinned by
  `BrevoSendRequestJsonTest`):
  - `sender`, `to[]`, `cc[]`, **`replyTo` are `{email, name}` objects** (not strings)
  - body is `htmlContent`; attachments are `attachment[] {content: base64, name}`
  - custom `headers` map; `tags` (`invoice`, `system`) for webhook correlation
  - null fields are omitted; response returns `messageId`
  - non-2xx → `BrevoApiException`; only 429/5xx are retryable
- Two entry points — always send through one of them, never `JavaMailSender` directly:
  - `service/SmtpSendService` — invoice emails (PDF attachment, CC contacts,
    org-specific sender). `@Retryable` on transient failures.
  - `service/SystemMailSender` — platform mail (admin invites, password resets,
    PEPPOL invitations, platform billing invoices) from `MAIL_FROM`/`MAIL_FROM_NAME`.

### Sender (From / Reply-To) resolution — `brevo/BrevoSenderResolver`

First hit wins:
1. explicit `organizationId` passed by the caller
2. authenticated `OrgPrincipal` on the current thread
3. customer lookup by account number (`erpCustomerId`), then TIN
4. platform default `MAIL_FROM` / `MAIL_FROM_NAME`

From = org `senderEmail` + `senderDisplayName`.
Reply-To = `replyToEmail` → `accountsEmail` → `senderEmail`.

**Async paths have no SecurityContext.** `CampaignOrchestrator.dispatchCampaign`
(used by `POST /api/v1/erp/dispatch`, `/erp/dispatch/upload`, campaigns, retries,
PDF watcher) runs on `@Async("mailExecutor")` virtual threads, so it must pass
`campaign.getOrganizationId()`; PEPPOL buyer notifications pass the supplier org.
Any new background send must pass the org id explicitly.

Every From address (each org's `senderEmail` and `MAIL_FROM`) must be a
**verified sender or on an authenticated domain in Brevo** (Reply-To needs no
verification). Beware: with an unverified sender Brevo still returns **2xx + a
messageId**, then drops the mail asynchronously with an `error` event
("sender … is not valid"). The send path records it as `SENT`; delivery events
(below) later correct it to `FAILED`. Brevo also enforces an **authorised-IP allowlist**:
the calling server's egress IP must be added under Security → Authorised IPs,
or every API call returns `401 unauthorized`.

Live smoke test (sends one real email; skipped unless `BREVO_LIVE_TEST_TO` is set):
`BrevoLiveSendTest` — see its Javadoc for the env vars.

### Delivery events (bounces / blocks / rejected senders)

`brevo/BrevoDeliveryEventService` applies Brevo events to campaign recipients,
matched by the `messageId` stored at send time (`MailRecipient.messageId`). Two
sources feed it, and handling is idempotent:

- **Webhook** `POST /webhooks/brevo/transactional` (`BrevoWebhookController`) —
  auth is `Authorization: Bearer $BREVO_WEBHOOK_TOKEN` (or `?token=`); with no token
  configured it returns 503 for everything. Register it once per environment with
  `scripts/register-brevo-webhook.sh` (reads `massmailer/.env`). Accepts single or
  batched (array) payloads.
- **Reconciler** `BrevoEventReconciler` — every `BREVO_RECONCILE_INTERVAL` (default
  15 min) pulls `error`, `blocked`, `hardBounces`, `invalid` from
  `GET /v3/smtp/statistics/events` (last 2 days). Required because Brevo **cannot**
  push `error` events via webhook (subscribable: sent, request, delivered,
  hardBounce, softBounce, blocked, spam, invalid, deferred, click, opened,
  uniqueOpened, unsubscribed).

Effects: permanent failures (`hard_bounce`/`hardBounces`, `invalid`/`invalid_email`,
`blocked`, `error`) move a `SENT` recipient to `FAILED` with `errorMessage =
"Brevo <event>: <reason>"`, set `retryCount = maxRetries` (so campaign retry skips
it), and call `MailCampaign.recordLateFailure()` (sent−1, failed+1, COMPLETED →
PARTIALLY_FAILED). `delivered`, soft bounces/deferred and spam/unsubscribed don't
change status (complaints are logged at WARN). Single sends and platform mail
aren't persisted, so their events are only logged.

Campaign recipients are committed only when the whole dispatch transaction ends,
so a webhook failure for an unknown messageId is retried in-memory (15 s → 20 min).
Usage already metered as delivered is **not** reversed on a late failure.
No schema changes: there is no migration tool and prod runs
`HIBERNATE_DDL_AUTO=validate`, so new tables/columns need manual SQL first — and
don't add `RecipientStatus` values (Hibernate 6 may have created a CHECK constraint).

## Deployment

- Domain `ap.invoicedirect.biz`; backend on `:9199`, frontend on `:8199`, bound to
  `10.218.0.5`, behind a host-level nginx that terminates TLS.
- Primary: on the server checkout, `massmailer/deploy.sh` (Docker Compose:
  `docker-compose.yml` + `docker-compose.prod.yml`, host Postgres). Alternative:
  `massmailer/deploy-native.sh` (systemd + nginx, run as root).
- Secrets live in `massmailer/.env` (git-ignored; template `.env.example`).
  Required: `ADMIN_PASSWORD`, `WEBHOOK_SECRET` (≥32 chars), DB creds,
  `BREVO_API_KEY`, `MAIL_FROM`. Recommended: `BREVO_WEBHOOK_TOKEN`
  (`openssl rand -hex 32`), then run `scripts/register-brevo-webhook.sh` once.
  Values containing spaces (e.g. `MAIL_FROM_NAME`) must be quoted — the deploy
  scripts `source .env`.

## Conventions

- Match surrounding style: records for DTOs/config, constructor injection,
  sealed `DeliveryResult` (`Delivered`/`Failed`/`Skipped`) handled with `switch`.
- Don't commit `.env`, credentials, or `target/`.
- PRs are squash-merged into `main`.
