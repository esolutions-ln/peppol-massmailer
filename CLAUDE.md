# InvoiceDirect / mass-mailer

Bulk invoice delivery platform: email campaigns (SMTP / Gmail OAuth2 / Brevo), PEPPOL AS4 e-invoicing (UBL + Schematron), ERP adapters (Sage, QuickBooks, D365, Odoo), multi-tenant orgs, billing.

## Stack
- **Backend**: Spring Boot 4, **Java 25 with `--enable-preview`** (already wired into surefire and `spring-boot:run` in `pom.xml`). Lombok, JPA, Spring Security, springdoc.
- **Frontend**: `frontend/` - React 18 + Vite + TypeScript + Tailwind 4. API client in `frontend/src/api`.
- **DB**: Postgres in Docker; H2 in-memory for local runs and tests.

## Layout
- `src/main/java/com/esolutions/massmailer/` - the main app (root `pom.xml`). Packages by feature: `peppol/`, `billing/`, `organization/`, `customer/`, `invitation/`, `brevo/`, `security/`, plus `controller/ service/ repository/ domain/ dto/`.
- `pdf-watcher-common/`, `pdf-watcher-agent/`, `invoice-forwarder/` - separate Maven projects (own `pom.xml`, not a reactor). The root app depends on `pdf-watcher-common:1.0.0`; run `mvn -q install` in `pdf-watcher-common/` after changing it.
- `massmailer/` - **untracked full copy of the project**. Do not edit it unless asked.
- `frontend/dist/`, `target/`, `node_modules/` - build output; never edit.
- `peppol/`, `uat-documents/`, `postman/` - specs, UAT material, Postman collections.

## Ports
| Service | Port |
|---|---|
| Backend | 9199 (`/actuator/health`, `/swagger-ui.html`, `/v3/api-docs`) |
| Frontend (Docker nginx) | 8199, proxies `/api`, `/peppol` to backend |
| Frontend (`npm run dev`) | 3000, Vite proxy to 9199 |
| Postgres (Docker) | 5432 |

## Commands
```bash
# Build / test (backend)
mvn -q -o compile -DskipTests                 # fast compile (offline)
mvn -q test -Dtest='SomeTest,OtherPropertyTest'  # targeted tests - prefer these
mvn test                                      # full suite (slow: 27 jqwik property tests)

# Frontend (OneDrive breaks node_modules/.bin symlinks - call tools directly)
cd frontend && node node_modules/typescript/bin/tsc --noEmit -p .
cd frontend && npm run build

# Full stack
docker compose up -d --build                  # services: postgres, mass-mailer, frontend
docker compose logs --tail=100 mass-mailer
```

Local backend without Docker (H2 + fake SMTP on 3025, never sends real mail):
```bash
DB_URL='jdbc:h2:mem:devdb;DB_CLOSE_DELAY=-1' DB_USER=sa DB_PASS='' \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.H2Dialect \
SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop SPRING_MAIL_HOST=localhost SPRING_MAIL_PORT=3025 \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=false ADMIN_USERNAME=admin ADMIN_PASSWORD=admin \
WEBHOOK_SECRET=dev-secret APP_BASE_URL=http://localhost:3000 \
mvn spring-boot:run -DskipTests
```

After the app is up, run `/smoke` (or `bash .claude/skills/smoke/smoke.sh`).

## Tests
- `*PropertyTest` = jqwik property tests (most of the suite); `*IntegrationTest` = Spring context + GreenMail; `ErpPdfDispatchUatTest` = UAT flow.
- Email tests must use GreenMail - never a real SMTP server or Brevo.

## Rules
- **This app sends real email to real customers.** Never run `deploy*.sh`, `docker-compose.prod.yml`, `scripts/send-test-email.sh`, or anything hitting Brevo/production without explicit confirmation (a hook enforces a prompt).
- Never read or edit `.env`, `google-oauth-credentials.json`, or `*.key.json`. Add new config keys to `.env.example` and `application.yml` as `${VAR:default}`.
- Files named `*-Lucky’s MacBook Pro.*` are OneDrive conflict copies - ignore them.
- API routes live under `/api/v1/...`, secured by `X-API-Key` (org) or admin session; `/peppol/as4/*` is the AS4 endpoint. Keep new endpoints consistent with that.
- Schema is managed by Hibernate `ddl-auto` (no Flyway/Liquibase) - call out entity changes that need a manual prod migration.

## Claude Code hooks (`.claude/settings.json`)
Edits to secrets/build output are blocked; deploy/prod/email/push commands prompt; on Stop, edited Java modules are `test-compile`d and edited frontend TS is typechecked, and failures are fed back.
