# Requirements Document

## Introduction

The Access Point Gateway is the core product of the InvoiceDirect platform — a multi-tenant PEPPOL-compliant invoice delivery network for Zimbabwe. It enables organisations (suppliers) to self-register, connect their ERP, and dispatch fiscalised invoices to buyers via two channels: PDF email attachment and PEPPOL BIS 3.0 UBL XML. The gateway implements the full PEPPOL 4-corner model, acting as C2 (sender AP) for outbound delivery and C3 (receiver AP) for inbound document receipt, with C4 routing forwarding received documents to buyer ERPs.

## Glossary

- **Gateway**: The InvoiceDirect Access Point Gateway system — the central platform that routes invoices between suppliers and buyers.
- **Organization**: A registered supplier tenant on the Gateway that dispatches invoices to its customers.
- **AccessPoint**: A PEPPOL Access Point endpoint registered in the eRegistry, representing either a sender (C2), receiver (C3), or gateway (both).
- **eRegistry**: The Gateway's local Service Metadata Publisher (SMP) — stores Access Points and Participant Links for routing.
- **ParticipantLink**: A mapping between a customer contact and their PEPPOL participant ID and preferred delivery channel.
- **CanonicalInvoice**: The ERP-agnostic internal invoice representation used by the dispatch pipeline.
- **UBL_Builder**: The component that constructs UBL 2.1 BIS 3.0 XML from a CanonicalInvoice.
- **Schematron_Validator**: The component that validates UBL XML against PEPPOL EN16931 Schematron rules.
- **AS4_Engine**: The component that wraps UBL XML in an ebMS 3.0 SOAP envelope, signs it, and delivers it via AS4 transport.
- **Dispatch_Router**: The component (ErpCampaignController) that routes each invoice to either the PEPPOL channel or the email channel based on delivery mode.
- **C4_Routing_Job**: The scheduled job that forwards inbound PEPPOL documents to buyer ERP webhooks.
- **InboundDocument**: A PEPPOL document received at the C3 endpoint, pending forwarding to the buyer's ERP.
- **PeppolDeliveryRecord**: The audit record persisted for every PEPPOL transmission attempt.
- **DeliveryMode**: The invoice delivery channel — `EMAIL`, `AS4`, or `BOTH`.
- **MDN**: Message Disposition Notification — the AS4 acknowledgement returned by the receiver's AP.
- **PEPPOL_BIS_3.0**: The PEPPOL Business Interoperability Specification version 3.0 for electronic invoicing.
- **EN16931**: The European standard for electronic invoicing, enforced via Schematron rules.
- **C1**: Corner 1 — the supplier's ERP system.
- **C2**: Corner 2 — the sender's Access Point (this Gateway acting as outbound AP).
- **C3**: Corner 3 — the buyer's Access Point (inbound endpoint).
- **C4**: Corner 4 — the buyer's ERP system receiving forwarded documents.
- **Metering_Service**: The component that records billable delivery events for billing purposes.
- **Delivery_Dashboard**: The per-organisation view of PEPPOL delivery statistics and retry capability.
- **ZIMRA**: Zimbabwe Revenue Authority — the fiscal authority whose e-invoicing regulations require a `verificationCode` on all invoices before PEPPOL transmission.
- **Fiscal_Metadata**: The ZIMRA-issued fiscal data attached to a `CanonicalInvoice`, including the `verificationCode` required for compliant dispatch.

---

## Requirements

### Requirement 1: Organisation Self-Registration

**User Story:** As a company, I want to self-register on the Gateway, so that I can start dispatching invoices to my customers without requiring manual admin intervention.

#### Acceptance Criteria

1. WHEN a registration request is submitted with a unique slug, valid sender email, and non-blank name, THE Gateway SHALL create an Organisation record with `status=ACTIVE` and return a 201 response containing the organisation ID, slug, API key, and derived PEPPOL participant ID.
2. WHEN a registration request is submitted with a slug that already exists, THE Gateway SHALL return HTTP 409 Conflict with an error message identifying the duplicate slug.
3. THE Gateway SHALL generate a cryptographically random API key of exactly 32 hexadecimal characters for each newly registered Organisation.
4. WHEN an Organisation is registered with a non-null `vatNumber`, THE Gateway SHALL derive the `peppolParticipantId` as `0190:ZW{vatNumber}`.
5. WHEN an Organisation is registered without a `vatNumber` but with a non-null `tinNumber`, THE Gateway SHALL derive the `peppolParticipantId` as `0190:ZW{tinNumber}`.
6. WHEN an Organisation is registered without both `vatNumber` and `tinNumber`, THE Gateway SHALL set `peppolParticipantId` to null.
7. THE Gateway SHALL return the API key in the 201 registration response body only — it SHALL NOT be returned in any subsequent read endpoints.
8. WHEN a `deliveryMode` is not specified in the registration request, THE Gateway SHALL default the Organisation's `deliveryMode` to `EMAIL`.
9. IF the self-registration endpoint receives more than 10 requests from the same IP address within one hour, THEN THE Gateway SHALL return HTTP 429 Too Many Requests.

---

### Requirement 2: Access Point Registration and Management

**User Story:** As an organisation administrator, I want to register and manage PEPPOL Access Points in the eRegistry, so that I can configure routing for outbound and inbound invoice delivery.

#### Acceptance Criteria

1. WHEN an Access Point registration request is submitted with a unique `participantId`, THE eRegistry SHALL persist the Access Point and return HTTP 201 with the Access Point record.
2. WHEN an Access Point registration request is submitted with a `participantId` that already exists in the eRegistry, THE eRegistry SHALL return HTTP 409 Conflict.
3. THE eRegistry SHALL support three Access Point roles: `SENDER` (C2 outbound), `RECEIVER` (C3 inbound), and `GATEWAY` (both sender and receiver).
4. WHEN an Access Point is created, THE eRegistry SHALL set its `status` to `ACTIVE` by default.
5. WHEN a status update request is submitted for an existing Access Point, THE eRegistry SHALL update the `status` to the requested value (`ACTIVE`, `SUSPENDED`, or `DECOMMISSIONED`) and set `updatedAt` to the current timestamp.
6. WHEN listing Access Points filtered by `organizationId`, THE eRegistry SHALL return only Access Points with `status=ACTIVE` belonging to that organisation.
7. WHEN listing Access Points filtered by `role`, THE eRegistry SHALL return only Access Points with `status=ACTIVE` matching that role.
8. WHEN an Access Point lookup by `participantId` is requested and no matching record exists, THE eRegistry SHALL return HTTP 404.

---

### Requirement 3: Participant Link Management

**User Story:** As an organisation administrator, I want to link my customers to their PEPPOL participant IDs and receiver Access Points, so that the dispatch router can automatically route invoices via PEPPOL.

#### Acceptance Criteria

1. WHEN a participant link creation request is submitted with a valid `organizationId`, `customerContactId`, `participantId`, and a `receiverAccessPointId` that exists in the eRegistry, THE eRegistry SHALL persist the link and return HTTP 201.
2. WHEN a participant link creation request references a `receiverAccessPointId` that does not exist in the eRegistry, THE eRegistry SHALL return HTTP 400 with an error message instructing the caller to register the Access Point first.
3. WHEN a participant link is created without specifying `preferredChannel`, THE eRegistry SHALL default `preferredChannel` to `PEPPOL`.
4. WHEN a participant link lookup is requested for a given `organizationId` and `customerContactId`, THE eRegistry SHALL return the matching link or HTTP 404 if none exists.
5. THE eRegistry SHALL support a `preferredChannel` of either `PEPPOL` (BIS 3.0 UBL delivery) or `EMAIL` (PDF email fallback).

---

### Requirement 4: Dual-Channel Invoice Dispatch

**User Story:** As an organisation, I want to dispatch invoices to my customers via the appropriate channel (PEPPOL or email), so that each customer receives invoices in their preferred format.

#### Acceptance Criteria

1. WHEN an invoice dispatch request is received, THE Dispatch_Router SHALL resolve the effective delivery mode by checking the customer contact's `deliveryMode` override first, then falling back to the organisation's default `deliveryMode`.
2. WHEN the effective delivery mode is `AS4`, THE Dispatch_Router SHALL route the invoice exclusively to the PEPPOL channel.
3. WHEN the effective delivery mode is `EMAIL`, THE Dispatch_Router SHALL route the invoice exclusively to the email channel as a PDF attachment.
4. WHEN the effective delivery mode is `BOTH`, THE Dispatch_Router SHALL route the invoice to both the PEPPOL channel and the email channel.
5. WHEN an invoice is dispatched, THE Dispatch_Router SHALL upsert the recipient into the customer contact registry before routing.
6. WHEN a dispatch request is accepted, THE Dispatch_Router SHALL return HTTP 202 with a summary containing `totalInvoices`, `peppolDispatched`, `emailDispatched`, and per-invoice PEPPOL results.
7. WHEN a dispatch request is received via `POST /api/v1/erp/dispatch`, THE Dispatch_Router SHALL fetch invoice data from the specified ERP adapter before routing.
8. WHEN a dispatch request is received via `POST /api/v1/erp/dispatch/upload`, THE Dispatch_Router SHALL accept multipart PDF uploads with a JSON metadata part and route each invoice using the uploaded PDF.
9. IF a multipart upload request is missing a PDF part for any invoice number listed in the metadata, THEN THE Dispatch_Router SHALL return HTTP 400 identifying the missing invoice numbers before any database writes occur.

---

### Requirement 5: UBL 2.1 BIS 3.0 Document Generation

**User Story:** As the Gateway, I want to generate standards-compliant UBL 2.1 BIS 3.0 XML from canonical invoice data, so that PEPPOL receivers can parse and process invoices correctly.

#### Acceptance Criteria

1. WHEN a `CanonicalInvoice` is provided to the UBL_Builder, THE UBL_Builder SHALL produce a well-formed UBL 2.1 XML document with `CustomizationID` set to the PEPPOL BIS 3.0 customisation identifier.
2. THE UBL_Builder SHALL set `ProfileID` to the PEPPOL billing process identifier `urn:fdc:peppol.eu:2017:poacc:billing:01:1.0`.
3. WHEN building a UBL document, THE UBL_Builder SHALL set `PayableAmount` equal to `subtotalAmount + vatAmount` from the `CanonicalInvoice`.
4. WHEN a `CanonicalInvoice` contains non-null `fiscalMetadata` with a non-blank `verificationCode`, THE UBL_Builder SHALL include the ZIMRA fiscal note in the UBL document.
5. THE UBL_Builder SHALL include the sender organisation's VAT breakdown in the UBL `TaxTotal` element.
6. THE Pretty_Printer SHALL format UBL XML documents with consistent indentation and namespace declarations.
7. FOR ALL valid `CanonicalInvoice` objects, parsing the UBL XML produced by the UBL_Builder and then re-building SHALL produce an equivalent UBL document (round-trip property).

---

### Requirement 6: Schematron Validation

**User Story:** As the Gateway, I want to validate UBL documents against PEPPOL EN16931 Schematron rules before transmission, so that invalid invoices are rejected before reaching the receiver's Access Point.

#### Acceptance Criteria

1. WHEN a UBL XML document is submitted for validation, THE Schematron_Validator SHALL execute the EN16931 Schematron rules loaded from `/schematron/PEPPOL-EN16931-UBL.sch`.
2. WHEN validation produces one or more violations with `severity=fatal`, THE Schematron_Validator SHALL return a result with `valid=false` and the full list of violations.
3. WHEN validation produces only violations with `severity=warning` (or no violations), THE Schematron_Validator SHALL return a result with `valid=true`.
4. WHEN a UBL document fails Schematron validation with a fatal violation, THE Gateway SHALL persist a `PeppolDeliveryRecord` with `status=FAILED` and SHALL NOT make any HTTP or AS4 request to the receiver endpoint.
5. WHEN a UBL document passes Schematron validation with warnings, THE Gateway SHALL store the warning violation list on the `PeppolDeliveryRecord` and proceed with transmission.
6. WHEN the Schematron_Validator is invoked for the same `profileId` more than once, THE Schematron_Validator SHALL use a cached compiled XSLT transform and SHALL NOT recompile the Schematron rules.
7. FOR ALL well-formed UBL XML inputs, invoking the Schematron_Validator twice with the same input SHALL return equivalent `SchematronResult` values (idempotency).

---

### Requirement 7: PEPPOL Outbound Delivery

**User Story:** As an organisation, I want to deliver invoices to my customers' PEPPOL Access Points, so that buyers receive structured UBL invoices directly in their ERP systems.

#### Acceptance Criteria

1. WHEN `PeppolDeliveryService.deliver()` is called, THE Gateway SHALL resolve the buyer's `PeppolParticipantLink` from the eRegistry using the organisation ID and customer contact ID.
2. IF no `PeppolParticipantLink` exists for the customer, THEN THE Gateway SHALL throw a `PeppolRoutingException` and persist a `PeppolDeliveryRecord` with `status=FAILED`.
3. WHEN the receiver Access Point has `simplifiedHttpDelivery=true`, THE Gateway SHALL POST the UBL XML to the receiver's `endpointUrl` over HTTPS with `Content-Type: application/xml` and the `Authorization: Bearer {token}` header if a `deliveryAuthToken` is set.
4. WHEN the receiver Access Point returns a non-2xx HTTP status, THE Gateway SHALL mark the `PeppolDeliveryRecord` as `FAILED` with the HTTP status code in the error message.
5. WHEN the receiver Access Point has `simplifiedHttpDelivery=false`, THE Gateway SHALL use the AS4_Engine to deliver the document via full ebMS 3.0 AS4 transport.
6. IF the receiver Access Point has `status=SUSPENDED` or `status=DECOMMISSIONED`, THEN THE Gateway SHALL throw a `PeppolRoutingException` and persist a `PeppolDeliveryRecord` with `status=FAILED`.
7. WHEN a PEPPOL delivery succeeds, THE Gateway SHALL set `PeppolDeliveryRecord.status=DELIVERED` and `acknowledgedAt` to the current timestamp.
8. WHEN a PEPPOL delivery fails for any reason, THE Gateway SHALL set `PeppolDeliveryRecord.status=FAILED` and store the error message on the record.
9. THE Gateway SHALL persist a `PeppolDeliveryRecord` for every invocation of `PeppolDeliveryService.deliver()`, regardless of whether transmission succeeds or fails.
10. WHEN building the UBL document, THE Gateway SHALL store the generated `ublXmlPayload` on the `PeppolDeliveryRecord` for audit and resend purposes.

---

### Requirement 8: AS4 Transport Engine

**User Story:** As the Gateway, I want to deliver PEPPOL documents using full AS4 ebMS 3.0 transport with X.509 signing and encryption, so that production PEPPOL compliance is achieved.

#### Acceptance Criteria

1. WHEN the AS4_Engine sends a message, THE AS4_Engine SHALL wrap the UBL XML payload in an ebMS 3.0 SOAP envelope with a correctly structured `eb:Messaging` header.
2. WHEN the AS4_Engine sends a message, THE AS4_Engine SHALL sign the SOAP message using the sender's X.509 private key.
3. WHEN the AS4_Engine sends a message, THE AS4_Engine SHALL encrypt the payload for the receiver's X.509 public certificate.
4. WHEN the receiver's AS4 endpoint returns HTTP 200 with a valid MDN containing `status=processed`, THE AS4_Engine SHALL return a result with `success=true` and the MDN message ID.
5. WHEN the receiver's AS4 endpoint returns a non-2xx response or an MDN with `status=failed`, THE AS4_Engine SHALL return a result with `success=false` and a descriptive error.
6. WHEN an AS4 delivery is marked `DELIVERED`, THE Gateway SHALL set `PeppolDeliveryRecord.mdnStatus=processed` and `mdnMessageId` to the MDN message ID.
7. IF the AS4_Engine receives an absent or failed MDN, THEN THE Gateway SHALL mark the `PeppolDeliveryRecord` as `FAILED`.

---

### Requirement 9: Inbound Document Receipt (C3 Endpoint)

**User Story:** As a buyer organisation registered on the Gateway, I want to receive PEPPOL documents sent to my participant ID, so that my ERP can process inbound invoices from suppliers.

#### Acceptance Criteria

1. WHEN a PEPPOL document is POSTed to `/peppol/as4/receive`, THE Gateway SHALL validate that the payload is non-empty and contains a valid UBL Invoice root element.
2. WHEN a valid inbound document is received, THE Gateway SHALL resolve the `receiverOrganizationId` from the eRegistry using the `X-PEPPOL-Receiver-ID` header.
3. WHEN a valid inbound document is received, THE Gateway SHALL persist an `InboundDocument` record with `routingStatus=RECEIVED` and return HTTP 200 with a `receiptId` and `payloadHash`.
4. IF the inbound payload is empty or malformed, THEN THE Gateway SHALL return HTTP 400 without persisting any record.
5. THE Gateway SHALL compute the `payloadHash` as a SHA-256 hex digest of the received UBL XML payload.
6. WHEN an inbound document is received, THE Gateway SHALL validate that the `X-PEPPOL-Sender-ID` header matches a known `AccessPoint` in the eRegistry; IF no matching sender is found, THEN THE Gateway SHALL return HTTP 403 without persisting any record.

---

### Requirement 10: C4 Routing Job

**User Story:** As a buyer organisation, I want inbound PEPPOL documents to be automatically forwarded to my ERP webhook, so that my system receives invoices without manual intervention.

#### Acceptance Criteria

1. THE C4_Routing_Job SHALL execute on a fixed delay of 30 seconds after the previous execution completes.
2. WHEN the C4_Routing_Job executes, THE C4_Routing_Job SHALL query `InboundDocument` records with `routingStatus=RECEIVED` and `routingRetryCount < 3`, processing up to 50 documents per execution.
3. WHEN processing an inbound document, THE C4_Routing_Job SHALL POST the UBL XML payload to the receiver organisation's `c4WebhookUrl` with `Authorization: Bearer {c4WebhookAuthToken}`.
4. WHEN the C4 webhook returns HTTP 2xx, THE C4_Routing_Job SHALL set the document's `routingStatus=DELIVERED_TO_C4` and record the endpoint and response.
5. WHEN the C4 webhook returns a non-2xx response or times out, THE C4_Routing_Job SHALL increment `routingRetryCount` and set `routingStatus=ROUTING_FAILED`.
6. WHEN a document's `routingRetryCount` reaches 3, THE C4_Routing_Job SHALL permanently mark the document as `ROUTING_FAILED` and SHALL NOT attempt further routing.
7. WHEN a document has `routingStatus=DELIVERED_TO_C4`, THE C4_Routing_Job SHALL NOT select or re-process that document in any subsequent execution.
8. IF the receiver organisation has no `c4WebhookUrl` configured, THEN THE C4_Routing_Job SHALL skip the document and log a warning without incrementing `routingRetryCount`.
9. THE C4_Routing_Job SHALL process each document in its own `@Transactional(REQUIRES_NEW)` scope to prevent partial batch commits.
10. WHEN retrying a document, THE C4_Routing_Job SHALL apply exponential backoff delays of 30 seconds, 60 seconds, and 120 seconds for the first, second, and third retry attempts respectively.

---

### Requirement 11: Delivery Dashboard

**User Story:** As an organisation administrator, I want to view PEPPOL delivery statistics and retry failed deliveries, so that I can monitor invoice delivery health and resolve failures.

#### Acceptance Criteria

1. WHEN a PEPPOL stats request is made for an organisation, THE Delivery_Dashboard SHALL return `totalDispatched`, `delivered`, `failed`, `retrying`, `successRate`, `currentPeriod`, and a 30-day daily delivery trend.
2. THE Delivery_Dashboard SHALL compute `successRate` as `(delivered / totalDispatched) * 100`, returning 0 when `totalDispatched` is 0.
3. WHEN a failed deliveries request is made for an organisation, THE Delivery_Dashboard SHALL return all `PeppolDeliveryRecord` entries with `status=FAILED` for that organisation.
4. WHEN a retry request is submitted for a `PeppolDeliveryRecord` with `status=FAILED`, THE Delivery_Dashboard SHALL re-queue the delivery using the stored `ublXmlPayload` without rebuilding the UBL document.
5. WHEN a retry request is submitted for a `PeppolDeliveryRecord` that does not have `status=FAILED`, THE Delivery_Dashboard SHALL return HTTP 400.

---

### Requirement 12: Metering and Billing Integration

**User Story:** As the platform operator, I want every invoice delivery attempt to be metered, so that organisations are billed accurately for their usage.

#### Acceptance Criteria

1. WHEN an invoice is dispatched via the PEPPOL channel, THE Metering_Service SHALL record a billable delivery event for the sending organisation.
2. WHEN an invoice is dispatched via the email channel, THE Metering_Service SHALL record a billable delivery event for the sending organisation.
3. WHEN a delivery attempt results in `status=FAILED`, THE Metering_Service SHALL record the event as non-billable.
4. THE Gateway SHALL assign a default rate profile to a newly registered Organisation if a platform-default rate profile is configured.

---

### Requirement 13: ERP Adapter Integration

**User Story:** As an organisation, I want to connect my ERP system to the Gateway, so that invoice data is fetched automatically without manual PDF uploads.

#### Acceptance Criteria

1. THE Gateway SHALL support the following ERP adapters: `SAGE_INTACCT`, `QUICKBOOKS_ONLINE`, `DYNAMICS_365`, `ODOO`, and `GENERIC_API`.
2. WHEN an ERP dispatch request specifies a supported `erpSource` and valid `invoiceIds`, THE Gateway SHALL fetch and normalise the invoices into `CanonicalInvoice` objects before dispatch.
3. WHEN an ERP health check is requested for a supported `erpSource`, THE Gateway SHALL return HTTP 200 with `healthy=true` if the ERP is reachable, or HTTP 503 with `healthy=false` if not.
4. IF the ERP system is unreachable during a dispatch request, THEN THE Gateway SHALL return HTTP 502 with an error response identifying the ERP source.

---

### Requirement 14: Customer Contact Registry

**User Story:** As an organisation, I want every invoice recipient to be automatically registered in my customer contact registry, so that I have a traceable record of all customers and can manage their delivery preferences.

#### Acceptance Criteria

1. WHEN invoices are dispatched, THE Gateway SHALL upsert each recipient into the customer contact registry scoped to the sending organisation before any delivery occurs.
2. WHEN a customer contact has a non-null `deliveryMode` override, THE Dispatch_Router SHALL use the customer's `deliveryMode` in preference to the organisation's default.
3. WHEN a customer contact has `unsubscribed=true`, THE Gateway SHALL skip delivery for that contact and record the outcome as `SKIPPED`.
4. WHEN a customer contact is upserted, THE Gateway SHALL update the contact's `name`, `companyName`, and ERP-sourced fields from the latest invoice data.

---

### Requirement 15: Security and Access Control

**User Story:** As the platform operator, I want the Gateway to enforce security controls on all API access and sensitive data, so that organisations cannot access each other's data and credentials are protected.

#### Acceptance Criteria

1. THE Gateway SHALL require a valid API key on all organisation-scoped API endpoints.
2. THE Gateway SHALL scope all customer, delivery, and campaign data queries to the authenticated organisation's ID.
3. WHEN an API key is generated for a new Organisation, THE Gateway SHALL ensure the key is unique across all organisations.
4. WHERE the `GET /api/v1/organizations/by-slug/{slug}` endpoint is accessed, THE Gateway SHALL require authentication to prevent organisation enumeration.
5. WHEN storing `AccessPoint.deliveryAuthToken` and `Organization.c4WebhookAuthToken`, THE Gateway SHALL encrypt these values at rest using AES-256.

---

### Requirement 16: ZIMRA Fiscal Validation

**User Story:** As the platform operator, I want every Zimbabwe-origin invoice to be fiscalised before PEPPOL dispatch, so that the Gateway complies with ZIMRA e-invoicing regulations.

#### Acceptance Criteria

1. WHEN an invoice is dispatched via the PEPPOL channel, THE Gateway SHALL verify that the invoice has a non-null `fiscalMetadata` with a non-blank `verificationCode` before building the UBL document.
2. IF an invoice is dispatched via the PEPPOL channel without a valid `verificationCode`, THEN THE Gateway SHALL reject the dispatch and return an error identifying the non-fiscalised invoice numbers without persisting any delivery record.
3. WHEN an invoice passes fiscal validation, THE UBL_Builder SHALL embed the ZIMRA `verificationCode` in the UBL document as a fiscal note.
4. WHEN an invoice is dispatched via the email channel only, THE Gateway SHALL NOT require fiscal validation.
