# PEPPOL e-Delivery Platform — Implementation Documentation

## Overview

This system implements a **PEPPOL Access Point** (AP) gateway following the PEPPOL 4-corner model for electronic invoice delivery. It acts as both a **C2** sender (outbound) and **C3** receiver (inbound) in the PEPPOL network, supporting the BIS Billing 3.0 profile with full AS4 ebMS 3.0 transport.

### The 4-Corner Model

```
C1 (Supplier ERP / invoicing system)
  → C2 (Our AP Gateway — this service)
    → C3 (Buyer's AP — receiver endpoint, looked up via SMP or eRegistry)
      → C4 (Buyer's ERP / Supplier Module)
```

---

## Architecture

The PEPPOL implementation is organized under `src/main/java/com/esolutions/massmailer/peppol/`:

| Package | Contents | Role |
|---------|----------|------|
| `config/` | `PeppolProperties` | `@ConfigurationProperties` for SMP/SML settings |
| `model/` | JPA entities | `AccessPoint`, `PeppolDeliveryRecord`, `InboundDocument`, `PeppolParticipantLink` |
| `repository/` | Spring Data repositories | CRUD for all PEPPOL entities |
| `controller/` | REST controllers | Admin onboarding, eRegistry, inbound C3, dashboard |
| `pki/` | Certificate management | Store, rotate, and load X.509 certs + RSA keys |
| `smp/` | Service Metadata Publisher | Outbound SMP client + inbound SMP endpoint |
| `as4/` | AS4 ebMS 3.0 transport | SOAP envelope building, XML-DSIG signing, XML-Enc encryption, MDN parsing |
| `schematron/` | ISO Schematron validation | PEPPOL EN16931 BIS 3.0 rule validation |
| `service/` | Business logic | `PeppolDeliveryService` — orchestrates C1→C2→C3 flow |
| `job/` | Scheduled tasks | `PeppolC4RoutingJob` — forwards inbound documents to C4 webhooks |
| `ubl/` | UBL builder | Builds PEPPOL BIS 3.0 compliant UBL 2.1 Invoice XML |

---

## Data Model (JPA Entities)

### `AccessPoint` — `peppol_access_points`

Represents a registered PEPPOL Access Point in the local eRegistry.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Primary key |
| `organizationId` | UUID (nullable) | Owning org (null for external APs) |
| `participantId` | String (unique) | PEPPOL participant ID, format `{scheme}:{value}` (e.g. `0190:ZW123456789`) |
| `participantName` | String | Human-readable AP name |
| `role` | Enum: `SENDER`, `RECEIVER`, `GATEWAY` | C2/C3/both classification |
| `endpointUrl` | String | AS4 or HTTP endpoint URL |
| `simplifiedHttpDelivery` | boolean | If true, uses direct HTTP POST instead of full AS4 |
| `deliveryAuthToken` | String (nullable) | Bearer token for HTTP delivery auth |
| `certificate` | Text (nullable) | X.509 certificate PEM (required for AS4) |
| `inboundSharedSecret` | String (nullable) | Pre-shared secret for HMAC inbound authentication |
| `status` | Enum: `ACTIVE`, `SUSPENDED`, `DECOMMISSIONED` | Lifecycle state |

### `PeppolDeliveryRecord` — `peppol_delivery_records`

Audit log for every outbound PEPPOL delivery attempt.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Primary key |
| `organizationId` | UUID | Sending org |
| `invoiceNumber` | String | Invoice reference |
| `senderParticipantId` | String | Our participant ID (C2) |
| `receiverParticipantId` | String | Buyer's participant ID (C3) |
| `deliveredToEndpoint` | String | Actual endpoint used |
| `documentTypeId` | String | PEPPOL document type |
| `processId` | String | PEPPOL process ID |
| `ublXmlPayload` | Text | Full UBL XML sent |
| `status` | Enum: `PENDING`, `TRANSMITTING`, `DELIVERED`, `FAILED`, `RETRYING` | Delivery lifecycle |
| `schematronPassed` | Boolean | Whether Schematron validation succeeded |
| `schematronWarnings` | Text (JSON array) | Warning-level violations |
| `schematronErrors` | Text (JSON array) | Fatal violations if validation failed |
| `mdnMessageId` / `mdnStatus` | String | AS4 MDN receipt details |
| `errorMessage` | Text | Failure reason |
| `retryCount` | int | Number of retry attempts |

### `InboundDocument` — `peppol_inbound_documents`

Persists every document received at the C3 inbound endpoint.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Primary key |
| `senderParticipantId` | String | Sender AP identity |
| `receiverParticipantId` | String (nullable) | Intended receiver |
| `receiverOrganizationId` | UUID (nullable) | Resolved from receiver participant ID |
| `invoiceNumber` | String (nullable) | Extracted invoice/case ID |
| `documentTypeId` | String (nullable) | PEPPOL document type |
| `processId` | String (nullable) | PEPPOL process ID |
| `ublXmlPayload` | Text | Full UBL XML received |
| `payloadHash` | String (SHA-256 hex) | Deduplication key |
| `routingStatus` | Enum: `RECEIVED`, `ROUTING`, `DELIVERED_TO_C4`, `ROUTING_FAILED` | C4 routing state |
| `routingRetryCount` | int | C4 webhook retry count |
| `receivedAt` | Instant | Timestamp of receipt |

### `PeppolParticipantLink` — `peppol_participant_links`

Maps a customer contact to their PEPPOL participant ID and receiver AP.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Primary key |
| `organizationId` | UUID | Sending org |
| `customerContactId` | UUID | Reference to `CustomerContact` |
| `participantId` | String | Buyer's PEPPOL participant ID |
| `receiverAccessPointId` | UUID | FK to `AccessPoint` (role=RECEIVER) |
| `preferredChannel` | Enum: `PEPPOL`, `EMAIL` | Delivery channel preference |

### `PeppolCertificate` — `peppol_certificates`

Stores X.509 certificates and RSA private keys for AS4 signing/encryption.

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Primary key |
| `organizationId` | UUID | Owning org |
| `certificatePem` | Text | X.509 certificate PEM |
| `privateKeyPem` | Text | RSA private key PEM (PKCS#8) |
| `issuerDn` | String | Certificate issuer DN |
| `subjectDn` | String | Certificate subject DN |
| `serialNumber` | String | Certificate serial number (hex) |
| `validFrom` / `validTo` | Instant | Certificate validity window |
| `status` | Enum: `ACTIVE`, `ROTATED`, `EXPIRED`, `REVOKED` | Lifecycle state |
| `description` | String | Human-readable label |

---

## API Endpoints

### Admin Onboarding

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/admin/orgs/{orgId}/peppol/onboard` | Onboard org: sets `peppolParticipantId`, `deliveryMode` (AS4/BOTH), registers GATEWAY Access Point in one call |

**Request body:**
```json
{
  "deliveryMode": "AS4",
  "participantName": "Acme Corp AP",
  "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
  "simplifiedHttpDelivery": true,
  "peppolParticipantId": "0190:ZW123456789"
}
```

If `peppolParticipantId` is omitted, it is auto-derived from the org's VAT (`0190:ZW{vat}`) or TIN number.

### eRegistry (Access Point Management)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/eregistry/access-points` | Register an AP (SENDER/RECEIVER/GATEWAY) |
| `GET` | `/api/v1/eregistry/access-points` | List APs (filter by `organizationId` or `role`) |
| `GET` | `/api/v1/eregistry/access-points/by-participant/{participantId}` | Lookup by participant ID |
| `PATCH` | `/api/v1/eregistry/access-points/{id}/status?status=SUSPENDED` | Suspend or reactivate |
| `POST` | `/api/v1/eregistry/participant-links` | Link customer to their receiver AP |
| `GET` | `/api/v1/eregistry/participant-links?organizationId=...` | List links for org |
| `DELETE` | `/api/v1/eregistry/participant-links/{id}` | Delete a link |
| `GET` | `/api/v1/eregistry/deliveries?organizationId=...` | Delivery audit log |

### PEPPOL PKI (Certificate Management)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/admin/orgs/{orgId}/peppol/certs` | Upload certificate+key |
| `POST` | `/api/v1/admin/orgs/{orgId}/peppol/certs/rotate` | Rotate (expires current, stores new) |
| `GET` | `/api/v1/admin/orgs/{orgId}/peppol/certs/active` | Get currently active cert |
| `GET` | `/api/v1/admin/orgs/{orgId}/peppol/certs` | List all certs for org |

**Upload request body:**
```json
{
  "certificatePem": "-----BEGIN CERTIFICATE-----\nMIIBmz...\n-----END CERTIFICATE-----",
  "privateKeyPem": "-----BEGIN PRIVATE KEY-----\nMIIBVA...\n-----END PRIVATE KEY-----",
  "description": "Production cert 2026"
}
```

### Inbound (C3 Receive)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/peppol/as4/receive` | Receive a PEPPOL document (HMCA-authenticated) |
| `GET` | `/peppol/as4/inbox` | Paginated inbound inbox (admin only) |
| `GET` | `/peppol/as4/health` | Health check |

**Required headers for receive:**
- `X-PEPPOL-Sender-ID` — sender's participant ID
- `X-PEPPOL-Receiver-ID` — receiver's participant ID
- `X-PEPPOL-Signature` — `Base64(HmacSHA256(sharedSecret, requestBody))`

### SMP (Service Metadata Publisher)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/bdxr/smp/{participantId}/services/{documentTypeId}` | BDXR SMP v2 XML (single path) |
| `GET` | `/bdxr/smp/{scheme}/{value}/services/{documentTypeId}` | Split path variant |

Returns BDXR SMP v2 XML with endpoint URL, AS4 transport profile, and Base64-encoded certificate.

### Delivery Dashboard

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/dashboard/{orgId}/peppol-stats` | Aggregate stats + 30-day trend |
| `GET` | `/api/v1/dashboard/{orgId}/failed-deliveries` | List failed deliveries |
| `POST` | `/api/v1/dashboard/{orgId}/retry/{deliveryRecordId}` | Re-queue failed delivery |

### Schedule Jobs

| Method | Description | Interval |
|--------|-------------|----------|
| `PeppolC4RoutingJob.routePendingDocuments()` | Forwards inbound PEPPOL docs to C4 ERP webhooks | Every 30s (fixed delay) |
| `PeppolSmpClient.clearCache()` | Clears SMP lookup cache | Configurable via `peppol.smp.cache-ttl-seconds` (default: 1h) |

---

## Delivery Flow (Outbound — C1 → C2 → C3)

The `PeppolDeliveryService.deliver()` method orchestrates:

1. **Resolve sender org** — load the `Organization` by UUID
2. **Resolve buyer contact** — find `CustomerContact` by org + email
3. **Look up participant link** — find `PeppolParticipantLink` for the buyer
   - If none: send notification email (`peppol-invoice-notification` template) and throw `PeppolRoutingException`
4. **Resolve receiver AP** — load the `AccessPoint` (role=RECEIVER) linked to the buyer
5. **Resolve sender AP** — find our GATEWAY/SENDER AP for the sender org
6. **Build UBL XML** — `UblInvoiceBuilder.build()` produces PEPPOL BIS Billing 3.0 compliant UBL 2.1
7. **Schematron validation** — validate against PEPPOL EN16931 rules (160 assertions)
   - Fatal violations → record as `FAILED`, throw `SchematronValidationException`
8. **Create delivery record** — status `TRANSMITTING`
9. **Transmit**:
   - **Simplified HTTP** — POST raw UBL XML to receiver endpoint with metadata headers
   - **Full AS4** — build SOAP 1.2 envelope with ebMS 3.0 header, sign with XML-DSIG (sender private key), encrypt body with AES-256/RSA-OAEP (receiver certificate), POST, parse MDN receipt

### AS4 Transport Details

The `As4TransportClientImpl.send()` method:

1. **Build SOAP Envelope** — S12:Envelope with eb:Messaging header containing UserMessage, PartyInfo, CollaborationInfo, PayloadInfo. Body contains Base64-encoded UBL payload.
2. **XML-DSIG Signing** — enveloped signature over the Body element using RSA-SHA256, sender's X.509 certificate embedded in KeyInfo
3. **XML-Enc Encryption** — AES-256 symmetric key encrypts the Body (via Apache Santuario), RSA-OAEP wraps the symmetric key with receiver's public key. Manual construction of `xenc:EncryptedKey` element with `ds:KeyInfo`/`ds:X509Data` containing receiver's certificate.
4. **HTTP POST** — sends SOAP+XML to receiver AS4 endpoint with `Content-Type: application/soap+xml`
5. **MDN Parsing** — extracts `eb:Receipt` (success) or `eb:Error` (failure) from response

### SMP Lookup

`PeppolSmpClient.lookupServiceMetadata()` resolves receiver endpoint and certificate:

1. **SML DNS resolution** — queries DNS for `b-{scheme}-{value}.sml.peppoltest.org`
2. **Fallback** — uses configured `peppol.smp.base-url` if DNS fails
3. **Parse BDXR response** — extracts endpoint URL, transport profile, X.509 certificate
4. **Caching** — results cached in `ConcurrentHashMap` with configurable TTL

---

## Delivery Flow (Inbound — C3 → C4)

`PeppolReceiveController.receive()`:

1. **Shape validation** — reject non-UBL documents
2. **Sender lookup** — verify sender AP exists in eRegistry
3. **HMAC verification** — constant-time comparison against `inboundSharedSecret`. Fail-closed: if no secret configured, reject.
4. **Deduplication** — SHA-256 payload hash check; duplicates return original receipt
5. **Receiver resolution** — map receiver participant ID to organization ID via AP registration
6. **Persist** — save `InboundDocument` with status `RECEIVED`

`PeppolC4RoutingJob.routePendingDocuments()` (scheduled every 30s):

1. Polls for `RECEIVED` documents with `< 3` retries
2. Forwards UBL XML to org's `c4WebhookUrl` (HTTP POST)
3. On 2xx → `DELIVERED_TO_C4`
4. On non-2xx or error → `ROUTING_FAILED`, increment retry count
5. After 3 failures → permanent failure logged

---

## Schematron Validation

`SchematronValidatorImpl` validates UBL documents against the real PEPPOL EN16931 BIS 3.0 rule set (`PEPPOL-EN16931-UBL.sch`, 1,168 lines, 160 assertions).

- Uses **ph-schematron-xslt 9.1.1** — proper ISO Schematron 3-phase pipeline (include expansion → abstract expansion → SVRL compilation)
- Compiles at startup, caches compiled XSLT
- **Fail-closed** — if rules are missing or compilation fails, all documents are rejected with `PEPPOL-RULES-NOT-INSTALLED` violation
- Parses SVRL output to extract rule ID, severity (fatal/warning), message, and XPath location

---

## Configuration

### `application.yml` — PEPPOL section

```yaml
peppol:
  smp:
    base-url: ${PEPPOL_SMP_BASE_URL:https://smp.peppoltest.org}
    cache-ttl-seconds: ${PEPPOL_SMP_CACHE_TTL:3600}
  sml:
    domain: ${PEPPOL_SML_DOMAIN:sml.peppoltest.org}
    dns-prefix: ${PEPPOL_SML_DNS_PREFIX:b}
```

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PEPPOL_SMP_BASE_URL` | `https://smp.peppoltest.org` | SMP base URL (PEPPOL test network) |
| `PEPPOL_SMP_CACHE_TTL` | `3600` | SMP lookup cache TTL in seconds |
| `PEPPOL_SML_DOMAIN` | `sml.peppoltest.org` | SML DNS zone |
| `PEPPOL_SML_DNS_PREFIX` | `b` | DNS prefix for SML resolution |

### Key Dependencies (pom.xml)

| Dependency | Purpose |
|------------|---------|
| `org.apache.santuario:xmlsec:2.3.5` | XML-Enc encryption (AES-256) |
| `com.helger.schematron:ph-schematron-xslt:9.1.1` | ISO Schematron 3-phase pipeline |
| Jakarta SOAP with XML | SOAP envelope construction (DOM-based) |
| Spring Web + RestTemplate | HTTP transport for AS4 and SMP queries |
| Spring Data JPA + PostgreSQL | Persistence |

---

## Frontend

The admin PEPPOL page at `frontend/src/pages/admin/PeppolPage.tsx` provides:

- **Access Points tab** — list, register, suspend/activate APs
- **Participant Links tab** — link customers to their receiver APs with channel selection
- **Delivery History tab** — audit log filtered by org
- **Inbound Inbox tab** — paginated inbound document list
- **Certificates tab** — upload/rotate X.509 certs, active cert display, certs table with status badges
- **Onboard Org tab** — quick onboard modal (select org → set participant ID, delivery mode, register GATEWAY AP)

Frontend API functions in `frontend/src/api/client.ts` and TypeScript types in `frontend/src/types.ts`.

---

## Deployment

### Docker Compose

The stack runs:
- Backend on port `9199`
- Frontend on port `8199`
- PostgreSQL on port `5432`

```bash
# Build and start
./deploy.sh

# Or manually:
docker compose up -d
```

### Verification

```bash
# Admin login
curl -s -X POST http://localhost:9199/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"350lt3am"}' | jq -r '.token'

# Get token
TOKEN="<token>"
ORG_ID="<org-uuid>"

# Onboard org
curl -s -X POST "http://localhost:9199/api/v1/admin/orgs/$ORG_ID/peppol/onboard" \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"deliveryMode":"AS4","participantName":"Test AP","endpointUrl":"https://ap.invoicedirect.biz/peppol/as4/receive","simplifiedHttpDelivery":true}'

# Upload certificate
curl -s -X POST "http://localhost:9199/api/v1/admin/orgs/$ORG_ID/peppol/certs" \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"certificatePem":"-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----","privateKeyPem":"-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"}'

# Check SMP
curl -s "http://localhost:9199/bdxr/smp/0190:ZW000000001/services/..." | xmllint --format -

# Health
curl -s "http://localhost:9199/peppol/as4/health"
```

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Manual RSA-OAEP wrapping (not Santuario `XMLCipher.RSA_OAEP`) | Santuario's `encryptKey()` can produce output incompatible with some PEPPOL receivers; manual `Cipher.wrap()` + DOM element building gives full control |
| `ConcurrentHashMap` cache (not Spring `@Cacheable`) | Avoids `NoSuchBeanDefinitionException: CacheManager` in integration tests |
| ph-schematron-xslt (not Saxon-only classpath compilation) | Proper 3-phase ISO Schematron pipeline; battle-tested in OXALIS/PEPPOL reference AP |
| HMAC inbound authentication (fail-closed) | No inbound secrets means no inbound traffic — avoids silently accepting unauthenticated documents |
| Separate PKI store (not reusing AP certificate field) | Supports cert rotation, expiry tracking, status lifecycle independent of AP registration |
