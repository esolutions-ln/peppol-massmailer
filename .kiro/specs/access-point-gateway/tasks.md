# Implementation Plan: Access Point Gateway

## Overview

Implements the remaining gaps in the Access Point Gateway: organisation self-registration, AS4 transport engine, Schematron validation, C4 routing job, participant link management UI, delivery dashboard, PeppolDeliveryRecord field additions, and property-based tests for correctness properties.

All tasks are in Java (Spring Boot) for the backend and TypeScript/React for the frontend, matching the existing codebase.

## Tasks

- [x] 1. Add PeppolDeliveryRecord fields for AS4 MDN and Schematron tracking
  - Add `mdnMessageId` (String), `mdnStatus` (String), `schematronPassed` (boolean), and `schematronWarnings` (TEXT/JSON column) fields to `PeppolDeliveryRecord` entity
  - Update `markDelivered()` to accept an optional `mdnMessageId` parameter
  - Add a `markSchematronFailed(List<String> violations)` helper method
  - _Requirements: 6.5, 8.6, 8.7_

- [x] 2. Add c4WebhookUrl and c4WebhookAuthToken fields to Organization
  - Add `c4WebhookUrl` (String) and `c4WebhookAuthToken` (String) fields to the `Organization` entity
  - _Requirements: 10.3, 15.5_

- [x] 3. Implement Organisation self-registration API
  - [x] 3.1 Create `OrganizationService` with `register(RegisterOrgRequest)` method
    - Validate slug uniqueness against `OrganizationRepository`; throw `SlugAlreadyExistsException` on conflict
    - Generate API key as 32-char hex using `SecureRandom` (UUID hex, no dashes)
    - Derive `peppolParticipantId`: `0190:ZW{vatNumber}` if vatNumber non-null, else `0190:ZW{tinNumber}` if tinNumber non-null, else null
    - Default `deliveryMode` to `EMAIL` if not specified
    - Assign default rate profile if a platform-default `RateProfile` is configured
    - Persist `Organization` with `status=ACTIVE`
    - Return `RegisterOrgResponse` with id, slug, apiKey, peppolParticipantId, status
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 12.4_

  - [x] 3.2 Write property test for API key uniqueness (Property 5)
    - **Property 5: API Key Uniqueness**
    - Generate N organisations via `OrganizationService.register()` with distinct slugs; assert all API keys are distinct
    - **Validates: Requirements 1.3, 15.3**

  - [x] 3.3 Write property test for participant ID derivation (Property 6)
    - **Property 6: Participant ID Derivation Consistency**
    - For any non-blank vatNumber string, assert `peppolParticipantId = "0190:ZW" + vatNumber`
    - For vatNumber=null and non-blank tinNumber, assert `peppolParticipantId = "0190:ZW" + tinNumber`
    - **Validates: Requirements 1.4, 1.5**

  - [x] 3.4 Create `OrganizationController` with POST endpoint and rate limiting
    - `POST /api/v1/organizations` — calls `OrganizationService.register()`, returns HTTP 201
    - Return HTTP 409 on `SlugAlreadyExistsException` with `{ "error": "Slug already registered: {slug}" }`
    - Apply Bucket4j rate limit: 10 requests per IP per hour; return HTTP 429 on breach
    - `GET /api/v1/organizations/by-slug/{slug}` — requires authentication (X-API-Key header)
    - `GET /api/v1/organizations/{id}` — admin endpoint, no API key in response body
    - `PATCH /api/v1/organizations/{id}/rate-profile` and `PATCH /api/v1/organizations/{id}/delivery-mode`
    - _Requirements: 1.1, 1.2, 1.7, 1.9, 15.4_

- [x] 4. Implement Schematron Validator
  - [x] 4.1 Create `SchematronValidator` interface and `SchematronValidatorImpl`
    - Define `SchematronResult` record with `boolean valid` and `List<SchematronViolation> violations`
    - Define `SchematronViolation` record with `ruleId`, `severity`, `message`, `location`
    - Load EN16931 Schematron rules from classpath `/schematron/PEPPOL-EN16931-UBL.sch`
    - Compile Schematron to XSLT using Saxon-HE; cache compiled transforms in `ConcurrentHashMap<String, Templates>` keyed by profileId
    - Execute XSLT transform against UBL XML; parse SVRL `failedAssert` elements
    - Return `valid=true` iff zero fatal violations; include all violations (fatal + warning) in result
    - _Requirements: 6.1, 6.2, 6.3, 6.6_

  - [x] 4.2 Write property test for Schematron idempotency (Property 11)
    - **Property 11: Schematron Idempotency**
    - For any well-formed UBL XML input, assert `validate(xml, profileId)` called twice returns equivalent `SchematronResult` values
    - **Validates: Requirements 6.7**

  - [x] 4.3 Integrate Schematron validation into `PeppolDeliveryService.deliver()`
    - Inject `SchematronValidator`; call `validate(ublXml, PROFILE_ID)` after UBL build (step 6 in algorithm)
    - If `hasFatalViolations()`: persist `PeppolDeliveryRecord` with `status=FAILED`, `schematronPassed=false`, throw `SchematronValidationException`
    - If warnings only: set `schematronPassed=true`, store warning messages on `record.schematronWarnings`, proceed with transmission
    - _Requirements: 6.4, 6.5_

- [x] 5. Implement AS4 Transport Engine
  - [x] 5.1 Create `As4TransportClient` interface and `As4Message` / `As4DeliveryResult` records
    - Define interface `As4TransportClient { As4DeliveryResult send(As4Message message); }`
    - Define `As4Message` record: senderParticipantId, receiverParticipantId, documentTypeId, processId, ublXmlPayload, senderCert, senderPrivateKey, receiverCert, receiverEndpointUrl
    - Define `As4DeliveryResult` record: success, mdnMessageId, mdnStatus, rawMdnResponse, errorDescription
    - _Requirements: 8.1_

  - [x] 5.2 Implement `As4TransportClientImpl`
    - Wrap UBL XML in ebMS 3.0 SOAP envelope with `eb:Messaging` header (UserMessage, MessageInfo, PartyInfo, CollaborationInfo, PayloadInfo)
    - Sign SOAP message using sender's X.509 private key via Apache WSS4J / `xmlsec`
    - Encrypt payload for receiver's X.509 public certificate
    - POST to `receiverEndpointUrl` using `RestTemplate`; parse MDN response body
    - If HTTP 200 and MDN `status=processed`: return `As4DeliveryResult(success=true, mdnMessageId, "processed", ...)`
    - If non-2xx or MDN `status=failed`: return `As4DeliveryResult(success=false, ..., errorDescription)`
    - Throw `As4TransportException` on network errors
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [x] 5.3 Wire `As4TransportClientImpl` into `PeppolDeliveryService.transmitAs4()`
    - Replace `UnsupportedOperationException` placeholder with `as4Client.send(buildAs4Message(record, receiverAp, ublXml))`
    - On `result.success=true`: call `record.markDelivered(result.mdnMessageId)`, set `record.mdnStatus=result.mdnStatus`, set `record.mdnMessageId=result.mdnMessageId`
    - On `result.success=false`: throw `As4TransportException(result.errorDescription)`
    - _Requirements: 7.5, 8.6, 8.7_

- [x] 6. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement C4 Routing Job
  - [x] 7.1 Create `PeppolC4RoutingJob` scheduled component
    - Annotate with `@Component`; inject `InboundDocumentRepository`, `OrganizationRepository`, `RestTemplate`
    - `@Scheduled(fixedDelay = 30_000)` method `routePendingDocuments()`
    - Query `InboundDocument` where `routingStatus=RECEIVED` AND `routingRetryCount < 3`, limit 50
    - For each document: resolve org's `c4WebhookUrl`; if null/blank, log warning and skip (do NOT increment retryCount)
    - POST `doc.ublXmlPayload` to `c4WebhookUrl` with `Authorization: Bearer {c4WebhookAuthToken}` and `Content-Type: application/xml`
    - On HTTP 2xx: call `doc.markRoutedToC4(endpoint, responseBody)`, save
    - On failure: call `doc.markRoutingFailed(error)`, increment `routingRetryCount`, save; if `routingRetryCount >= 3`, log error (permanent failure)
    - Wrap each document's processing in `@Transactional(propagation = REQUIRES_NEW)` to isolate commits
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9_

  - [x] 7.2 Write property test for C4 retry bound (Property 4)
    - **Property 4: Retry Bound**
    - For any sequence of C4 routing failures, assert `routingRetryCount` never exceeds 3 and document is never re-queued after reaching the limit
    - **Validates: Requirements 10.6**

- [x] 8. Implement Delivery Dashboard API
  - [x] 8.1 Create `OrgDeliveryDashboardController` with stats and retry endpoints
    - `GET /api/v1/dashboard/{orgId}/peppol-stats` → returns `PeppolDeliveryStats` record: totalDispatched, delivered, failed, retrying, successRate, currentPeriod, dailyTrend (last 30 days as `List<DailyDeliveryCount>`)
    - Compute `successRate = (delivered / totalDispatched) * 100`; return 0 when `totalDispatched = 0`
    - Compute 30-day daily trend by grouping `PeppolDeliveryRecord.createdAt` by date
    - `GET /api/v1/dashboard/{orgId}/failed-deliveries` → returns all `PeppolDeliveryRecord` with `status=FAILED` for the org
    - `POST /api/v1/dashboard/{orgId}/retry/{deliveryRecordId}` → re-queues delivery using stored `ublXmlPayload`; return HTTP 400 if record is not `FAILED`
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

  - [x] 8.2 Write property test for success rate formula (Property 13)
    - **Property 13: Success Rate Formula**
    - For any (delivered, totalDispatched) pair where totalDispatched > 0, assert `successRate = (delivered / totalDispatched) * 100`; for totalDispatched = 0, assert `successRate = 0`
    - **Validates: Requirements 11.2**

- [x] 9. Add Participant Link Management UI
  - [x] 9.1 Add `ParticipantLink` type and API client functions to frontend
    - Add `ParticipantLink` interface to `frontend/src/types.ts`: id, organizationId, customerContactId, customerEmail, participantId, receiverAccessPointId, receiverApName, preferredChannel, createdAt
    - Add `listParticipantLinks(orgId: string)` to `frontend/src/api/client.ts` — `GET /api/v1/eregistry/participant-links?organizationId={orgId}`
    - Add `createParticipantLink(req)` — `POST /api/v1/eregistry/participant-links`
    - Add `deleteParticipantLink(id: string)` — `DELETE /api/v1/eregistry/participant-links/{id}`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [x] 9.2 Add `DELETE /api/v1/eregistry/participant-links/{id}` endpoint to `ERegistryController`
    - Return HTTP 204 on success, HTTP 404 if not found
    - _Requirements: 3.1_

  - [x] 9.3 Add "Participant Links" tab to `PeppolPage.tsx`
    - Add `participant-links` to the tab list alongside `access-points`, `deliveries`, `inbox`
    - Table columns: Customer Email, Participant ID, AP Name, Channel, Created At, Delete button
    - "Link Customer" modal: customer email input → participant ID input (validate format `{scheme}:{value}`) → AP selector (dropdown from `listAccessPoints()`) → channel toggle (PEPPOL / EMAIL)
    - Delete link with inline confirmation
    - Load links filtered by current org's ID from session context
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 10. Add Delivery Dashboard frontend component
  - [x] 10.1 Add `PeppolDeliveryStats` type and dashboard API client functions
    - Add `PeppolDeliveryStats` interface to `frontend/src/types.ts`: totalDispatched, delivered, failed, retrying, successRate, currentPeriod, dailyTrend
    - Add `DailyDeliveryCount` interface: date, delivered, failed
    - Add `getPeppolStats(orgId: string, apiKey?: string)` to `client.ts`
    - Add `getFailedDeliveries(orgId: string, apiKey?: string)` to `client.ts`
    - Add `retryDelivery(orgId: string, deliveryRecordId: string, apiKey?: string)` to `client.ts`
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

  - [x] 10.2 Create `DeliveryDashboard` component in `DashboardPage.tsx`
    - Add a "PEPPOL Delivery" section to the org-facing `DashboardPage.tsx`
    - Display stat cards: Total Dispatched, Delivered, Failed, Success Rate %
    - Display 30-day daily trend as a simple table or bar chart
    - List failed deliveries with invoice number, receiver participant ID, error message, and a "Retry" button
    - Retry button calls `retryDelivery()` and refreshes the failed list on success
    - _Requirements: 11.1, 11.3, 11.4, 11.5_

- [x] 11. Write property-based tests for UBL correctness properties
  - [x] 11.1 Write property test for UBL monetary total invariant (Property 9)
    - **Property 9: UBL Monetary Total Invariant**
    - For any `(subtotalAmount, vatAmount)` pair, assert the built UBL XML has `PayableAmount = subtotalAmount + vatAmount`
    - Use jqwik `@Property` with `@ForAll` BigDecimal generators (non-negative, scale 2)
    - **Validates: Requirements 5.3**

  - [x] 11.2 Write property test for delivery mode routing correctness (Property 7)
    - **Property 7: Delivery Mode Routing Correctness**
    - For any invoice with effective mode `AS4`, assert it appears in peppolInvoices only; for `EMAIL`, emailInvoices only; for `BOTH`, both lists
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4**

  - [x] 11.3 Write property test for UBL round-trip (Property 10)
    - **Property 10: UBL Round-Trip**
    - For any valid `CanonicalInvoice`, assert `ublBuilder.build(parse(ublBuilder.build(invoice)))` produces an equivalent UBL document to `ublBuilder.build(invoice)`
    - Use jqwik `@Property` with `@ForAll` CanonicalInvoice generators covering varied amounts, VAT rates, and optional fields
    - **Validates: Requirements 5.7**

- [x] 12. Write property-based tests for delivery audit and AS4 correctness
  - [x] 12.1 Write property test for delivery audit completeness (Property 1)
    - **Property 1: Delivery Audit Completeness**
    - For any invocation of `PeppolDeliveryService.deliver()` (success or failure), assert a `PeppolDeliveryRecord` is persisted with `status ∈ {DELIVERED, FAILED}`
    - Use jqwik `@Property` with mocked transport layer that randomly succeeds or throws; assert record always exists after the call
    - **Validates: Requirements 7.9**

  - [x] 12.2 Write property test for Schematron gate (Property 2)
    - **Property 2: Schematron Gate**
    - For any UBL document where `schematronResult.hasFatalViolations() = true`, assert no HTTP or AS4 request is made to the receiver endpoint
    - Mock `SchematronValidator` to return fatal violations; assert `As4TransportClient.send()` and HTTP client are never called
    - **Validates: Requirements 6.4**

  - [x] 12.3 Write property test for MDN verification (Property 8)
    - **Property 8: MDN Verification**
    - For any AS4 delivery record, assert `status=DELIVERED` implies `mdnStatus="processed"` AND `mdnMessageId ≠ null`; assert absent or failed MDN results in `status=FAILED`
    - Use jqwik `@Property` with `@ForAll` MDN response generators (success/failure variants)
    - **Validates: Requirements 8.4, 8.6**

- [x] 13. Write property-based tests for inbound document correctness
  - [x] 13.1 Write property test for C4 routing idempotency (Property 3)
    - **Property 3: C4 Routing Idempotency**
    - For any `InboundDocument` with `routingStatus=DELIVERED_TO_C4`, assert `routePendingDocuments()` never selects or re-processes it
    - Seed DB with documents in `DELIVERED_TO_C4` status; run job N times; assert no additional webhook calls and status unchanged
    - **Validates: Requirements 10.7**

  - [x] 13.2 Write property test for payload hash correctness (Property 12)
    - **Property 12: Payload Hash Correctness**
    - For any inbound UBL XML payload, assert `inboundDocument.payloadHash = sha256hex(payload)`
    - Use jqwik `@Property` with `@ForAll` arbitrary byte-array payloads; POST to C3 endpoint; assert stored hash matches `SHA-256` digest
    - **Validates: Requirements 9.5**

  - [x] 13.3 Write property test for data isolation (Property 14)
    - **Property 14: Data Isolation**
    - For any authenticated organisation, assert all query results for customers, delivery records, and campaigns have `organizationId` matching the authenticated org
    - Use jqwik `@Property` with multiple orgs seeded; assert no cross-tenant records appear in any response
    - **Validates: Requirements 15.2**

- [x] 14. Implement inbound sender authenticity validation (C3 endpoint)
  - Add sender participant ID validation to `PeppolReceiveController`: look up `X-PEPPOL-Sender-ID` header value in eRegistry; if not found, return HTTP 403 without persisting any `InboundDocument`
  - _Requirements: 9.6_

  - [x] 14.1 Write property test for inbound sender authenticity (Property 16)
    - **Property 16: Inbound Sender Authenticity**
    - For any inbound request where `X-PEPPOL-Sender-ID` is not in the eRegistry, assert no `InboundDocument` is persisted and response status is 403
    - **Validates: Requirements 9.6**

- [x] 15. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Property tests use jqwik (`net.jqwik:jqwik`) — add to `pom.xml` test scope if not present
- Bucket4j rate limiting requires `com.bucket4j:bucket4j-core` dependency
- Saxon-HE (`net.sf.saxon:Saxon-HE`) is required for Schematron XSLT compilation
- AS4 signing/encryption requires `org.apache.wss4j:wss4j-ws-security-dom`
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation