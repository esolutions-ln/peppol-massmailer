# InvoiceDirect PEPPOL e-Delivery Platform
## Developer Implementation Guide

**Version:** 1.0  
**Last Updated:** June 2026  
**Platform:** InvoiceDirect Mass Mailer  
**Author:** eSolutions Development Team

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [PEPPOL Overview](#peppol-overview)
3. [Architecture](#architecture)
4. [Data Model](#data-model)
5. [API Reference](#api-reference)
6. [Implementation Components](#implementation-components)
7. [Integration Guide](#integration-guide)
8. [Security & Compliance](#security--compliance)
9. [Deployment Guide](#deployment-guide)
10. [Troubleshooting](#troubleshooting)
11. [Known Limitations](#known-limitations)
12. [Future Roadmap](#future-roadmap)

---

## 1. Executive Summary

### What is This Document?

This developer guide documents the PEPPOL e-delivery platform implementation within the InvoiceDirect Mass Mailer application. It serves as both a technical reference and integration guide for:

- **Backend developers** extending the PEPPOL functionality
- **DevOps engineers** deploying and maintaining the platform
- **Integration partners** connecting ERP systems to the PEPPOL network
- **Auditors** reviewing PEPPOL compliance

### What Was Implemented?

The InvoiceDirect platform implements a **PEPPOL Access Point (AP)** gateway that enables:


- **Electronic invoice delivery** following the PEPPOL 4-corner model
- **UBL 2.1 Invoice** generation compliant with PEPPOL BIS Billing 3.0
- **AS4 ebMS 3.0 transport** with XML-DSIG signing and XML-Enc encryption
- **Schematron validation** against EN16931 business rules
- **Customer self-registration** via PEPPOL invitation flow
- **Dual-mode delivery** (EMAIL fallback + PEPPOL electronic)
- **Local eRegistry** for access point and participant management

### Technology Stack

| Component | Technology |
|-----------|------------|
| **Backend** | Spring Boot 4.0.0, Java 25 |
| **Database** | PostgreSQL 16 |
| **XML Security** | Apache Santuario 2.3.5 |
| **Validation** | ph-schematron-xslt 9.1.1 |
| **Transport** | RestTemplate (HTTP), SOAP 1.2 |
| **Standards** | PEPPOL BIS 3.0, UBL 2.1, AS4 Profile 2.0 |

---

## 2. PEPPOL Overview

### What is PEPPOL?

**PEPPOL** (Pan-European Public Procurement On-Line) is an international network for electronic document exchange, primarily used for electronic invoicing (e-invoicing) and procurement documents.

### The 4-Corner Model

PEPPOL uses a 4-corner model where documents flow through certified Access Points:

```
┌─────────────────────────────────────────────────────────────┐
│                    PEPPOL 4-Corner Model                    │
└─────────────────────────────────────────────────────────────┘

  C1 (Corner 1)          C2 (Corner 2)          C3 (Corner 3)          C4 (Corner 4)
  ─────────────          ─────────────          ─────────────          ─────────────
┌──────────────┐      ┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│              │      │              │      │              │      │              │
│   Supplier   │─────>│  Sender AP   │─────>│ Receiver AP  │─────>│    Buyer     │
│   (Sender)   │      │ (Our System) │      │ (Buyer's AP) │      │  (Customer)  │
│              │      │              │      │              │      │              │
└──────────────┘      └──────────────┘      └──────────────┘      └──────────────┘
     ERP/CRM           InvoiceDirect         External AP            ERP/Procurement
```

**Flow Explanation:**
- **C1 → C2:** Supplier's ERP sends invoice to our Access Point (InvoiceDirect)
- **C2 → C3:** Our AP looks up buyer's AP and delivers via AS4
- **C3 → C4:** Buyer's AP forwards to their ERP system
- **Acknowledgments:** MDN receipts flow back C4 → C3 → C2 → C1


### Key PEPPOL Concepts

#### Participant ID
A unique identifier for organizations in the PEPPOL network:
- **Format:** `{scheme}:{value}`
- **Example:** `0190:ZW123456789`
- **Zimbabwe scheme:** `0190` (for VAT-based identifiers)

#### Document Type ID
Identifies the type of business document being exchanged:
```
urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::
Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1
```

#### Process ID
Defines the business process:
```
urn:fdc:peppol.eu:2017:poacc:billing:01:1.0
```

#### BIS (Business Interoperability Specification)
- **BIS Billing 3.0:** The invoice specification profile
- **Based on EN16931:** European standard for electronic invoicing
- **160+ business rules:** Validated via Schematron

---

## 3. Architecture

### Package Structure

The PEPPOL implementation is located in:
```
src/main/java/com/esolutions/massmailer/peppol/
├── as4/                    # AS4 ebMS 3.0 transport layer
│   ├── As4TransportClient.java
│   ├── As4TransportClientImpl.java
│   ├── As4Message.java
│   └── As4DeliveryResult.java
├── config/                 # Configuration properties
│   └── PeppolProperties.java
├── controller/             # REST API endpoints
│   ├── ERegistryController.java
│   ├── PeppolReceiveController.java
│   ├── AdminPeppolController.java
│   └── OrgDeliveryDashboardController.java
├── model/                  # JPA entities
│   ├── AccessPoint.java
│   ├── PeppolDeliveryRecord.java
│   ├── PeppolParticipantLink.java
│   └── InboundDocument.java
├── pki/                    # Certificate management
│   ├── PeppolCertificate.java
│   ├── PeppolCredentialStore.java
│   └── PeppolPkiController.java
├── repository/             # Data access layer
├── schematron/             # EN16931 validation
│   ├── SchematronValidator.java
│   └── SchematronValidatorImpl.java
├── service/                # Business logic
│   └── PeppolDeliveryService.java
├── smp/                    # Service Metadata Publisher
│   ├── PeppolSmpClient.java
│   └── PeppolSmpController.java
├── ubl/                    # UBL 2.1 invoice builder
│   └── UblInvoiceBuilder.java
└── job/                    # Scheduled tasks
    └── PeppolC4RoutingJob.java
```


### Component Interaction

```
┌────────────────────────────────────────────────────────────────┐
│                     Delivery Flow Diagram                      │
└────────────────────────────────────────────────────────────────┘

1. Campaign Request
   │
   ├─> DeliveryModeRouter
   │   ├─ Check customer.deliveryMode
   │   └─ Check PeppolParticipantLink
   │
   ├─> [EMAIL Path]
   │   └─> SmtpSendService → PDF email delivery
   │
   └─> [PEPPOL Path]
       │
       ├─> PeppolDeliveryService.deliver()
       │   │
       │   ├─ 1. Load Organization (C1)
       │   ├─ 2. Load CustomerContact (C4)
       │   ├─ 3. Resolve PeppolParticipantLink
       │   ├─ 4. Load receiver AccessPoint (C3)
       │   ├─ 5. Load sender AccessPoint (C2)
       │   │
       │   ├─> UblInvoiceBuilder.build()
       │   │   └─ Generate UBL 2.1 XML
       │   │
       │   ├─> SchematronValidator.validate()
       │   │   ├─ Load PEPPOL-EN16931-UBL.sch
       │   │   ├─ Run 160+ business rules
       │   │   └─ Return violations or success
       │   │
       │   ├─> Create PeppolDeliveryRecord (TRANSMITTING)
       │   │
       │   ├─> [If simplifiedHttpDelivery]
       │   │   └─ RestTemplate POST → receiver endpoint
       │   │
       │   └─> [If full AS4]
       │       └─> As4TransportClientImpl.send()
       │           ├─ 1. Build SOAP 1.2 envelope with ebMS header
       │           ├─ 2. Sign with XML-DSIG (sender private key)
       │           ├─ 3. Encrypt with XML-Enc (receiver certificate)
       │           ├─ 4. POST to receiver AS4 endpoint
       │           └─ 5. Parse MDN receipt
       │
       └─> Update PeppolDeliveryRecord (DELIVERED/FAILED)
```

### Database Schema


```sql
-- PEPPOL Access Points (C2/C3 endpoints)
CREATE TABLE peppol_access_points (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID,  -- null for external APs
    participant_id VARCHAR(100) UNIQUE NOT NULL,
    participant_name VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,  -- SENDER, RECEIVER, GATEWAY
    endpoint_url VARCHAR(500) NOT NULL,
    simplified_http_delivery BOOLEAN DEFAULT false,
    delivery_auth_token VARCHAR(500),
    certificate TEXT,  -- X.509 PEM
    inbound_shared_secret VARCHAR(255),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Customer → PEPPOL Participant mapping
CREATE TABLE peppol_participant_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    customer_contact_id UUID NOT NULL,
    participant_id VARCHAR(100) NOT NULL,
    receiver_access_point_id UUID NOT NULL,
    preferred_channel VARCHAR(20) DEFAULT 'PEPPOL',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (receiver_access_point_id) REFERENCES peppol_access_points(id)
);

-- Outbound delivery audit log
CREATE TABLE peppol_delivery_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    invoice_number VARCHAR(100),
    sender_participant_id VARCHAR(100) NOT NULL,
    receiver_participant_id VARCHAR(100) NOT NULL,
    delivered_to_endpoint VARCHAR(500),
    document_type_id VARCHAR(500),
    process_id VARCHAR(500),
    ubl_xml_payload TEXT,
    status VARCHAR(20) NOT NULL,
    schematron_passed BOOLEAN,
    schematron_warnings TEXT,
    schematron_errors TEXT,
    mdn_message_id VARCHAR(255),
    mdn_status VARCHAR(50),
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    transmitted_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Inbound documents (C3 receive)
CREATE TABLE peppol_inbound_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_participant_id VARCHAR(100) NOT NULL,
    receiver_participant_id VARCHAR(100),
    receiver_organization_id UUID,
    invoice_number VARCHAR(100),
    document_type_id VARCHAR(500),
    process_id VARCHAR(500),
    ubl_xml_payload TEXT NOT NULL,
    payload_hash VARCHAR(64) UNIQUE,
    routing_status VARCHAR(20) DEFAULT 'RECEIVED',
    routing_retry_count INTEGER DEFAULT 0,
    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- X.509 certificates for AS4 signing/encryption
CREATE TABLE peppol_certificates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    certificate_pem TEXT NOT NULL,
    private_key_pem TEXT NOT NULL,
    issuer_dn VARCHAR(500),
    subject_dn VARCHAR(500),
    serial_number VARCHAR(100),
    valid_from TIMESTAMP,
    valid_to TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---


## 4. Data Model

### AccessPoint Entity

Represents a PEPPOL Access Point in the network.

**Key Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `participantId` | String | PEPPOL participant ID (e.g., `0190:ZW123456789`) |
| `role` | Enum | `SENDER` (C2), `RECEIVER` (C3), `GATEWAY` (both) |
| `endpointUrl` | String | AS4 or HTTP endpoint URL |
| `simplifiedHttpDelivery` | Boolean | `true` = direct HTTP POST, `false` = full AS4 |
| `certificate` | Text | X.509 certificate PEM for AS4 |
| `deliveryAuthToken` | String | Bearer token for HTTP auth |
| `inboundSharedSecret` | String | HMAC secret for C3 authentication |
| `status` | Enum | `ACTIVE`, `SUSPENDED`, `DECOMMISSIONED` |

**Business Rules:**
- Each organization should have one `GATEWAY` AP (their own C2 endpoint)
- External buyer APs are registered as `RECEIVER` with `organizationId = null`
- Certificate is required for AS4 mode
- HMAC secret is required for C3 inbound endpoints

### PeppolParticipantLink Entity

Maps a customer contact to their PEPPOL participant ID and receiver AP.

**Key Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `organizationId` | UUID | Sending organization |
| `customerContactId` | UUID | FK to `customer_contacts` |
| `participantId` | String | Buyer's PEPPOL participant ID |
| `receiverAccessPointId` | UUID | FK to `peppol_access_points` |
| `preferredChannel` | Enum | `PEPPOL`, `EMAIL` |

**Business Rules:**
- Created via invitation flow or admin registration
- DeliveryModeRouter checks for existence to determine delivery channel
- One link per customer per organization

### PeppolDeliveryRecord Entity

Audit log for every outbound PEPPOL delivery attempt.

**Key Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `status` | Enum | `TRANSMITTING`, `DELIVERED`, `FAILED`, `RETRYING` |
| `ublXmlPayload` | Text | Full UBL XML sent |
| `schematronPassed` | Boolean | Whether validation succeeded |
| `schematronWarnings` | Text | JSON array of warning-level violations |
| `schematronErrors` | Text | JSON array of fatal violations |
| `mdnMessageId` | String | AS4 MDN receipt message ID |
| `mdnStatus` | String | MDN status (e.g., "processed") |
| `errorMessage` | Text | Failure reason |
| `retryCount` | Integer | Number of retry attempts |

**Lifecycle:**
```
TRANSMITTING → DELIVERED (success)
            → FAILED (schematron/transport error)
            → RETRYING (transient failure, will retry)
```


### InboundDocument Entity

Persists every document received at the C3 inbound endpoint.

**Key Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `senderParticipantId` | String | Sender AP identity |
| `receiverOrganizationId` | UUID | Resolved from receiver participant ID |
| `ublXmlPayload` | Text | Full UBL XML received |
| `payloadHash` | String | SHA-256 hex for deduplication |
| `routingStatus` | Enum | `RECEIVED`, `ROUTING`, `DELIVERED_TO_C4`, `ROUTING_FAILED` |
| `routingRetryCount` | Integer | C4 webhook retry count |

**C4 Routing Flow:**
1. Document arrives at `POST /peppol/as4/receive`
2. Status set to `RECEIVED`
3. `PeppolC4RoutingJob` (scheduled every 30s) picks it up
4. Forwards to organization's `c4WebhookUrl`
5. On success: `DELIVERED_TO_C4`
6. On failure: `ROUTING_FAILED` (max 3 retries)

---

## 5. API Reference

### Admin Onboarding

**Endpoint:** `POST /api/v1/admin/orgs/{orgId}/peppol/onboard`

Quick onboarding for an organization:
- Sets `peppolParticipantId` on the organization
- Updates `deliveryMode` to `AS4` or `BOTH`
- Registers a `GATEWAY` Access Point

**Request:**
```json
{
  "deliveryMode": "AS4",
  "participantName": "Acme Corp AP",
  "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
  "simplifiedHttpDelivery": true,
  "peppolParticipantId": "0190:ZW123456789"
}
```

**Response:** `200 OK` with organization details

**Auto-derivation:** If `peppolParticipantId` is omitted, it's derived from VAT: `0190:ZW{vatNumber}`

---

### eRegistry: Access Point Management

**Base Path:** `/api/v1/eregistry`

#### Register Access Point
`POST /access-points`

**Request:**
```json
{
  "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "participantId": "9915:esolutions",
  "participantName": "eSolutions AP Gateway",
  "role": "GATEWAY",
  "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
  "simplifiedHttpDelivery": true,
  "certificate": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"
}
```

**Use Cases:**
- **Your own AP (C2):** `role=GATEWAY`, set your endpoint
- **Buyer's AP (C3):** `role=RECEIVER`, set their endpoint


#### List Access Points
`GET /access-points?organizationId={uuid}&role={SENDER|RECEIVER|GATEWAY}`

Returns array of AccessPointResponse objects.

#### Lookup by Participant ID
`GET /access-points/by-participant/{participantId}`

Example: `GET /access-points/by-participant/0190:ZW123456789`

#### Update Status
`PATCH /access-points/{id}/status?status=SUSPENDED`

Suspend or reactivate an AP.

---

### eRegistry: Participant Links

#### Create Participant Link
`POST /participant-links`

Links a customer to their PEPPOL AP.

**Request:**
```json
{
  "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
  "customerEmail": "buyer@acmecorp.co.zw",
  "participantId": "0190:ZW987654321",
  "receiverAccessPointId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "preferredChannel": "PEPPOL"
}
```

**Prerequisites:**
1. Receiver AP must exist in eRegistry
2. Customer must exist in `customer_contacts` table

#### List Participant Links
`GET /participant-links?organizationId={uuid}`

#### Delete Participant Link
`DELETE /participant-links/{id}`

Removes PEPPOL routing for a customer. Returns `204 No Content`.

---

### Certificate Management (PKI)

**Base Path:** `/api/v1/admin/orgs/{orgId}/peppol/certs`

#### Upload Certificate
`POST /admin/orgs/{orgId}/peppol/certs`

**Request:**
```json
{
  "certificatePem": "-----BEGIN CERTIFICATE-----\nMIIBmz...\n-----END CERTIFICATE-----",
  "privateKeyPem": "-----BEGIN PRIVATE KEY-----\nMIIBVA...\n-----END PRIVATE KEY-----",
  "description": "Production cert 2026"
}
```

**Validation:**
- Private key must match certificate
- Certificate must not be expired
- Issuer and subject DNs are extracted and stored

#### Rotate Certificate
`POST /admin/orgs/{orgId}/peppol/certs/rotate`

Expires the current active certificate and activates a new one.

#### Get Active Certificate
`GET /admin/orgs/{orgId}/peppol/certs/active`

Returns the currently active certificate (without private key).

#### List All Certificates
`GET /admin/orgs/{orgId}/peppol/certs`

Returns all certificates with status badges (`ACTIVE`, `ROTATED`, `EXPIRED`, `REVOKED`).

---


### Inbound (C3 Receive)

#### Receive PEPPOL Document
`POST /peppol/as4/receive`

Accepts inbound PEPPOL documents with HMAC authentication.

**Required Headers:**
```
X-PEPPOL-Sender-ID: 0190:ZW123456789
X-PEPPOL-Receiver-ID: 0190:ZW987654321
X-PEPPOL-Signature: Base64(HmacSHA256(sharedSecret, requestBody))
Content-Type: application/xml
```

**Request Body:** UBL XML

**Authentication:**
- HMAC-SHA256 signature verification
- Constant-time comparison to prevent timing attacks
- Fail-closed: no secret configured = reject all

**Validation Steps:**
1. Shape validation (must be valid UBL XML)
2. Sender AP lookup (must exist in eRegistry)
3. HMAC verification
4. SHA-256 deduplication check
5. Receiver participant ID → organization ID resolution
6. Persist as `InboundDocument` with status `RECEIVED`

#### View Inbox
`GET /peppol/as4/inbox?page=0&size=20`

Paginated list of inbound documents (admin only).

#### Health Check
`GET /peppol/as4/health`

Returns `200 OK` if the AS4 endpoint is operational.

---

### SMP (Service Metadata Publisher)

#### BDXR SMP v2 Endpoint
`GET /bdxr/smp/{participantId}/services/{documentTypeId}`

Returns BDXR SMP v2 XML with endpoint URL, transport profile, and Base64-encoded certificate.

**Example:**
```
GET /bdxr/smp/0190:ZW123456789/services/urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice...
```

**Response:**
```xml
<ServiceMetadata xmlns="http://docs.oasis-open.org/bdxr/ns/SMP/2016/05">
  <ServiceInformation>
    <ParticipantIdentifier scheme="0190">ZW123456789</ParticipantIdentifier>
    <DocumentIdentifier>...</DocumentIdentifier>
    <ProcessList>
      <Process>
        <ProcessIdentifier>urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</ProcessIdentifier>
        <ServiceEndpointList>
          <Endpoint transportProfile="peppol-transport-as4-v2_0">
            <EndpointURI>https://ap.invoicedirect.biz/peppol/as4/receive</EndpointURI>
            <Certificate>MIIBmz...</Certificate>
          </Endpoint>
        </ServiceEndpointList>
      </Process>
    </ProcessList>
  </ServiceInformation>
</ServiceMetadata>
```

---


### Delivery Dashboard

#### Get Org PEPPOL Stats
`GET /api/v1/dashboard/{orgId}/peppol-stats`

Returns:
- Total deliveries (last 30 days)
- Success rate
- Failed count
- 30-day trend (daily counts)

#### List Failed Deliveries
`GET /api/v1/dashboard/{orgId}/failed-deliveries?page=0&size=20`

Paginated list of failed `PeppolDeliveryRecord` entries.

#### Retry Failed Delivery
`POST /api/v1/dashboard/{orgId}/retry/{deliveryRecordId}`

Re-queues a failed delivery for retry.

---

## 6. Implementation Components

### 6.1 UBL Invoice Builder

**File:** `peppol/ubl/UblInvoiceBuilder.java`

Builds PEPPOL BIS Billing 3.0 compliant UBL 2.1 Invoice XML.

**Key Methods:**
```java
public String build(CanonicalInvoice invoice, Organization supplier)
public String build(CanonicalInvoice invoice, Organization supplier, CustomerContact buyer)
```

**UBL Structure:**
```xml
<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">
  <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant...</cbc:CustomizationID>
  <cbc:ProfileID>urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</cbc:ProfileID>
  <cbc:ID>INV-2026-001</cbc:ID>
  <cbc:IssueDate>2026-06-22</cbc:IssueDate>
  <cbc:DueDate>2026-07-22</cbc:DueDate>
  <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>
  <cbc:DocumentCurrencyCode>USD</cbc:DocumentCurrencyCode>
  
  <!-- Supplier Party (C1) -->
  <cac:AccountingSupplierParty>
    <cac:Party>
      <cbc:EndpointID schemeID="0190">ZW123456789</cbc:EndpointID>
      <cac:PartyName><cbc:Name>eSolutions</cbc:Name></cac:PartyName>
      <cac:PartyTaxScheme>
        <cbc:CompanyID>123456789</cbc:CompanyID>
        <cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme>
      </cac:PartyTaxScheme>
      <cac:PartyLegalEntity>
        <cbc:RegistrationName>eSolutions</cbc:RegistrationName>
      </cac:PartyLegalEntity>
    </cac:Party>
  </cac:AccountingSupplierParty>
  
  <!-- Customer Party (C4) -->
  <cac:AccountingCustomerParty>...</cac:AccountingCustomerParty>
  
  <!-- Tax breakdown -->
  <cac:TaxTotal>...</cac:TaxTotal>
  
  <!-- Monetary totals -->
  <cac:LegalMonetaryTotal>...</cac:LegalMonetaryTotal>
  
  <!-- Invoice lines -->
  <cac:InvoiceLine>...</cac:InvoiceLine>
</Invoice>
```

**BIS 3.0 Rules Implemented:**
- BR-01: Specification identifier (CustomizationID)
- BR-02: Profile identifier (ProfileID)
- BR-04: Invoice number (ID)
- BR-09: Seller name
- BR-10: Buyer name
- BR-31: Supplier VAT identifier
- BR-47: Seller legal name
- BR-48: Buyer legal name
- BR-49: Payment means
- BR-53: Buyer VAT or legal registration

**Current Limitation:**
Uses `StringBuilder` for XML construction (noted as MEDIUM risk in QA audit). Consider migrating to JAXB for type safety.


### 6.2 Schematron Validator

**File:** `peppol/schematron/SchematronValidatorImpl.java`

Validates UBL documents against the PEPPOL EN16931 BIS 3.0 rule set.

**Schematron File:** `src/main/resources/schematron/PEPPOL-EN16931-UBL.sch`
- **Lines:** 1,168
- **Assertions:** 160+
- **Phases:** 3 (ISO Schematron standard)

**Implementation:**
```java
public SchematronResult validate(String ublXml, String businessProfile) {
    // 1. Parse UBL XML to DOM
    // 2. Apply compiled XSLT (cached at startup)
    // 3. Parse SVRL output
    // 4. Extract violations with severity, rule ID, message, XPath
    // 5. Return SchematronResult
}
```

**Validation Phases:**
1. **Include expansion** — resolve XInclude directives
2. **Abstract expansion** — resolve abstract patterns
3. **SVRL compilation** — generate Schematron Validation Report Language output

**Fail-Closed Behavior:**
- If rules file is missing or invalid → all documents rejected
- Error code: `PEPPOL-RULES-NOT-INSTALLED`
- Prevents accidental acceptance of non-compliant invoices

**Severity Levels:**
- **Fatal:** Document is rejected, delivery fails
- **Warning:** Document is accepted, warnings logged in `schematronWarnings` field

**Example Violations:**
```json
{
  "ruleId": "BR-CO-15",
  "severity": "FATAL",
  "message": "VAT breakdown required when VAT amount > 0",
  "location": "/Invoice/cac:TaxTotal",
  "test": "cac:TaxSubtotal"
}
```

**Known Issue (CRITICAL):**
The current `.sch` file is a **STUB** with no real assertions. Production deployment requires the official OpenPEPPOL compiled XSLT from:
https://github.com/OpenPEPPOL/peppol-bis-invoice-3

---

### 6.3 AS4 Transport Client

**File:** `peppol/as4/As4TransportClientImpl.java`

Implements PEPPOL AS4 Profile 2.0 (ebMS 3.0) transport.

**Key Methods:**
```java
public As4DeliveryResult send(As4Message message)
```

**Transport Phases:**

#### Phase 1: SOAP Envelope Construction
```java
private Document buildSoapEnvelope(As4Message msg, String messageId)
```

Builds SOAP 1.2 envelope with ebMS 3.0 header:
- `<S12:Envelope>` with namespaces
- `<S12:Header>` containing `<eb:Messaging>`
- `<eb:UserMessage>` with MessageInfo, PartyInfo, CollaborationInfo, PayloadInfo
- `<S12:Body>` containing Base64-encoded UBL payload

#### Phase 2: XML-DSIG Signing
```java
private void signSoapMessage(Document doc, PrivateKey key, X509Certificate cert)
```

- **Algorithm:** RSA-SHA256
- **Target:** `<S12:Body>` element (enveloped signature)
- **KeyInfo:** X.509 certificate embedded for receiver verification
- **Canonicalization:** C14N (inclusive)


#### Phase 3: XML-Enc Encryption
```java
private void encryptPayload(Document doc, X509Certificate receiverCert)
```

**Encryption Steps:**
1. Generate ephemeral AES-256 symmetric key
2. Encrypt `<S12:Body>` with AES-256-CBC (via Apache Santuario `XMLCipher`)
3. Wrap symmetric key with RSA-OAEP using receiver's public key
4. Build `<xenc:EncryptedKey>` element with:
   - `<ds:KeyInfo>` containing receiver's X.509 certificate
   - `<xenc:CipherData>` with Base64-encoded wrapped key
5. Place `<xenc:EncryptedKey>` in `<wsse:Security>` header

**Manual RSA-OAEP Wrapping:**
```java
javax.crypto.Cipher rsaCipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
rsaCipher.init(javax.crypto.Cipher.WRAP_MODE, receiverCert.getPublicKey());
byte[] encryptedKeyBytes = rsaCipher.wrap(symmetricKey);
```

**Design Decision:** Manual `Cipher.wrap()` instead of Santuario's `XMLCipher.RSA_OAEP` for full control over output format (some PEPPOL receivers reject Santuario's default format).

#### Phase 4: HTTP POST
```java
private As4DeliveryResult postAndParseMdn(String soapXml, String endpointUrl, String messageId)
```

- **Content-Type:** `application/soap+xml; charset=UTF-8`
- **SOAPAction:** (empty)
- **Transport:** RestTemplate

#### Phase 5: MDN Parsing
```java
private As4DeliveryResult parseMdn(String responseBody, String sentMessageId)
```

Extracts MDN receipt from response:
- Look for `<eb:Receipt>` (success) or `<eb:Error>` (failure)
- Extract MDN message ID from `<eb:SignalMessage>`
- Fallback: heuristic text search for "processed" / "failed" / "error"

**MDN Result:**
```java
As4DeliveryResult {
    boolean success;
    String mdnMessageId;
    String mdnStatus;
    String errorDescription;
    String rawMdnResponse;
}
```

**Known Limitations (CRITICAL):**
1. **No payload encryption verification:** Receiver must decrypt; we don't verify they can
2. **No XML-Enc decryption:** Inbound messages are not decrypted (inbound endpoint is simplified HTTP)

---

### 6.4 PEPPOL Delivery Service

**File:** `peppol/service/PeppolDeliveryService.java`

Orchestrates the full C1 → C2 → C3 delivery flow.

**Main Method:**
```java
@Transactional
public PeppolDeliveryRecord deliver(UUID organizationId, CanonicalInvoice invoice)
```

**Flow Steps:**

1. **Resolve Supplier (C1)**
   ```java
   Organization supplier = orgRepo.findById(organizationId).orElseThrow();
   ```

2. **Resolve Buyer Contact (C4)**
   ```java
   CustomerContact buyer = customerRepo.findByOrganizationIdAndEmail(
       organizationId, invoice.recipientEmail()).orElseThrow();
   ```

3. **Resolve PEPPOL Participant Link**
   ```java
   PeppolParticipantLink link = linkRepo
       .findByOrganizationIdAndCustomerContactId(organizationId, buyer.getId())
       .orElseGet(() -> {
           sendUnregisteredBuyerNotification(supplier, buyer, invoice);
           throw new PeppolRoutingException("No PEPPOL link");
       });
   ```


4. **Resolve Receiver AP (C3)**
   ```java
   AccessPoint receiverAp = apRepo.findById(link.getReceiverAccessPointId())
       .orElseThrow();
   if (!receiverAp.isActive()) throw new PeppolRoutingException("AP not active");
   ```

5. **Resolve Sender AP (C2)**
   ```java
   String senderParticipantId = apRepo
       .findByOrganizationIdAndStatus(organizationId, ACTIVE)
       .stream()
       .filter(ap -> ap.getRole() == GATEWAY || ap.getRole() == SENDER)
       .findFirst()
       .map(AccessPoint::getParticipantId)
       .orElse("9915:" + supplier.getSlug());  // fallback test scheme
   ```

6. **Build UBL 2.1 XML**
   ```java
   String ublXml = ublBuilder.build(invoice, supplier, buyer);
   ```

7. **Schematron Validation**
   ```java
   SchematronResult result = schematronValidator.validate(ublXml, PROFILE_ID);
   if (result.hasFatalViolations()) {
       // Record failure and throw
       throw new SchematronValidationException(result.violations());
   }
   ```

8. **Create Delivery Record**
   ```java
   PeppolDeliveryRecord record = PeppolDeliveryRecord.builder()
       .status(TRANSMITTING)
       .ublXmlPayload(ublXml)
       .schematronPassed(true)
       .transmittedAt(Instant.now())
       .build();
   deliveryRepo.save(record);
   ```

9. **Transmit**
   - If `simplifiedHttpDelivery=true`: direct HTTP POST
   - If `simplifiedHttpDelivery=false`: full AS4 via `As4TransportClient`

10. **Update Record**
    ```java
    record.markDelivered(mdnResponse);
    // or
    record.markFailed(errorMessage);
    deliveryRepo.save(record);
    ```

**Unregistered Buyer Notification:**
When a customer doesn't have a PEPPOL link:
1. Email template: `peppol-invoice-notification.html`
2. Explains electronic delivery benefits
3. Attaches PDF invoice
4. Logs routing exception

---

### 6.5 SMP Client

**File:** `peppol/smp/PeppolSmpClient.java`

Queries PEPPOL Service Metadata Publishers to resolve receiver endpoint and certificate.

**Key Method:**
```java
public Optional<SmpServiceMetadata> lookupServiceMetadata(
    String participantId, String documentTypeId)
```

**Lookup Flow:**

1. **SML DNS Resolution**
   ```java
   // Participant ID: 0190:ZW123456789
   // DNS query: b-0190-zw123456789.sml.peppoltest.org
   String dnsName = config.smlDnsPrefix() + "-" + 
                    participantId.replace(":", "-").toLowerCase() + 
                    "." + config.smlDomain();
   ```

2. **SMP Query**
   ```
   GET https://{resolved-smp}/bdxr/smp/{participantId}/services/{documentTypeId}
   ```

3. **Parse BDXR Response**
   Extract:
   - Endpoint URL
   - Transport profile (`peppol-transport-as4-v2_0`)
   - Base64-encoded X.509 certificate

4. **Cache Result**
   ```java
   ConcurrentHashMap<String, CachedMetadata> cache;
   // TTL: configurable (default 1 hour)
   ```

**Fallback:**
If SML/SMP lookup fails, falls back to local eRegistry lookup via `AccessPointRepository`.


---

### 6.6 PEPPOL C4 Routing Job

**File:** `peppol/job/PeppolC4RoutingJob.java`

Scheduled job that forwards inbound documents to buyer's ERP (C4).

**Schedule:** Every 30 seconds (fixed delay)

**Flow:**
```java
@Scheduled(fixedDelay = 30000)
public void routePendingDocuments() {
    // 1. Load documents with routingStatus=RECEIVED and retryCount < 3
    List<InboundDocument> pending = inboundRepo
        .findByRoutingStatusAndRetryCountLessThan(RECEIVED, 3);
    
    // 2. For each document:
    for (InboundDocument doc : pending) {
        // Resolve org's C4 webhook URL
        String webhookUrl = orgRepo.findById(doc.getReceiverOrganizationId())
            .map(Organization::getC4WebhookUrl)
            .orElse(null);
        
        if (webhookUrl == null) {
            doc.setRoutingStatus(ROUTING_FAILED);
            continue;
        }
        
        // 3. POST UBL XML to C4 webhook
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);
            headers.set("X-PEPPOL-Sender-ID", doc.getSenderParticipantId());
            headers.set("X-Invoice-Number", doc.getInvoiceNumber());
            
            ResponseEntity<String> response = restTemplate.postForEntity(
                webhookUrl, new HttpEntity<>(doc.getUblXmlPayload(), headers), String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                doc.setRoutingStatus(DELIVERED_TO_C4);
            } else {
                doc.incrementRetryCount();
                if (doc.getRoutingRetryCount() >= 3) {
                    doc.setRoutingStatus(ROUTING_FAILED);
                }
            }
        } catch (Exception e) {
            doc.incrementRetryCount();
            if (doc.getRoutingRetryCount() >= 3) {
                doc.setRoutingStatus(ROUTING_FAILED);
            }
        }
        
        inboundRepo.save(doc);
    }
}
```

**Error Handling:**
- Max 3 retries
- After 3 failures: status set to `ROUTING_FAILED`
- Failures logged but not re-queued

**C4 Webhook Requirements:**
- Accept `POST` with `Content-Type: application/xml`
- Body: UBL 2.1 Invoice XML
- Return `2xx` status code on success

---

## 7. Integration Guide

### 7.1 Onboarding Flow

**Step 1: Admin Login**
```bash
curl -X POST http://localhost:9199/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"your-password"}' \
  | jq -r '.token'
```

**Step 2: Create Organization**
```bash
curl -X POST http://localhost:9199/api/v1/organizations \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Acme Corp",
    "slug": "acmecorp",
    "companyName": "Acme Corporation (Pvt) Ltd",
    "vatNumber": "123456789",
    "senderEmail": "invoices@acmecorp.co.zw"
  }'
```


**Step 3: PEPPOL Onboarding**
```bash
ORG_ID="d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f"
ADMIN_TOKEN="<token-from-step-1>"

curl -X POST "http://localhost:9199/api/v1/admin/orgs/$ORG_ID/peppol/onboard" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "deliveryMode": "BOTH",
    "participantName": "Acme Corp AP Gateway",
    "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
    "simplifiedHttpDelivery": true,
    "peppolParticipantId": "0190:ZW123456789"
  }'
```

**Step 4: Upload PEPPOL Certificate** (for AS4 mode)
```bash
curl -X POST "http://localhost:9199/api/v1/admin/orgs/$ORG_ID/peppol/certs" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "certificatePem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
    "privateKeyPem": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----",
    "description": "Production PEPPOL cert 2026"
  }'
```

**Step 5: Register Customer**
```bash
curl -X POST "http://localhost:9199/api/v1/organizations/$ORG_ID/customers" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "buyer@customercorp.co.zw",
    "name": "John Buyer",
    "companyName": "Customer Corp Ltd",
    "deliveryMode": "PEPPOL",
    "vatNumber": "987654321"
  }'
```

**Step 6: Register Buyer's AP**
```bash
curl -X POST "http://localhost:9199/api/v1/eregistry/access-points" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": null,
    "participantId": "0190:ZW987654321",
    "participantName": "Customer Corp AP",
    "role": "RECEIVER",
    "endpointUrl": "https://erp.customercorp.co.zw/peppol/receive",
    "simplifiedHttpDelivery": true,
    "deliveryAuthToken": "buyer-secret-token"
  }'
```

**Step 7: Link Customer to AP**
```bash
RECEIVER_AP_ID="<id-from-step-6>"

curl -X POST "http://localhost:9199/api/v1/eregistry/participant-links" \
  -H "X-API-Key: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": "'$ORG_ID'",
    "customerEmail": "buyer@customercorp.co.zw",
    "participantId": "0190:ZW987654321",
    "receiverAccessPointId": "'$RECEIVER_AP_ID'",
    "preferredChannel": "PEPPOL"
  }'
```


**Step 8: Send Test Invoice**
```bash
ORG_API_KEY="<org-api-key>"

curl -X POST "http://localhost:9199/api/v1/mail/invoice" \
  -H "X-API-Key: $ORG_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "buyer@customercorp.co.zw",
    "subject": "Invoice INV-2026-001",
    "templateName": "invoice",
    "invoiceNumber": "INV-2026-001",
    "invoiceDate": "2026-06-22",
    "dueDate": "2026-07-22",
    "recipientCompany": "Customer Corp Ltd",
    "subtotalAmount": 1000.00,
    "vatAmount": 150.00,
    "totalAmount": 1150.00,
    "currency": "USD",
    "pdfBase64": "<base64-pdf>"
  }'
```

**Verification:**
```bash
# Check delivery record
curl -X GET "http://localhost:9199/api/v1/eregistry/deliveries?organizationId=$ORG_ID" \
  -H "X-API-Key: $ADMIN_TOKEN"

# View dashboard stats
curl -X GET "http://localhost:9199/api/v1/dashboard/$ORG_ID/peppol-stats" \
  -H "X-API-Key: $ORG_API_KEY"
```

---

### 7.2 Customer Self-Registration (Invitation Flow)

The PEPPOL invitation flow allows buyers to self-register their Access Point.

**Step 1: Send Invitation**
```bash
curl -X POST "http://localhost:9199/api/v1/my/invitations" \
  -H "X-API-Key: $ORG_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "customerEmail": "newbuyer@corp.co.zw",
    "customerName": "Jane Buyer",
    "customerCompany": "New Corp Ltd"
  }'
```

Response includes:
- `invitationId`
- `token` (UUID)
- `expiresAt` (72 hours from creation)
- `inviteUrl` (shareable link)

**Step 2: Buyer Opens Link**
```
https://ap.invoicedirect.biz/invite/peppol/{token}
```

Frontend validates token via:
```bash
curl -X GET "http://localhost:9199/api/v1/invitations/{token}"
```

**Step 3: Buyer Completes Registration**
Frontend submits:
```bash
curl -X POST "http://localhost:9199/api/v1/invitations/{token}/complete" \
  -H "Content-Type: application/json" \
  -d '{
    "participantId": "0190:ZW555666777",
    "endpointUrl": "https://erp.newcorp.co.zw/peppol/receive",
    "certificate": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
    "deliveryMode": "PEPPOL"
  }'
```

Backend actions:
1. Validate invitation token (not expired, status PENDING)
2. Register buyer's AP in eRegistry (role=RECEIVER)
3. Create PeppolParticipantLink
4. Update invitation status to COMPLETED
5. Update customer contact with deliveryMode and peppolParticipantId
6. Send confirmation email


**Invitation Expiry:**
`InvitationExpiryJob` runs daily and expires invitations older than 72 hours with status PENDING.

---

### 7.3 ERP Integration

#### C1 Integration (Supplier ERP → Access Point)

**Option A: Direct API Call**
```java
// Java example
RestTemplate restTemplate = new RestTemplate();
String apiUrl = "https://ap.invoicedirect.biz/api/v1/mail/invoice";

Map<String, Object> invoice = Map.of(
    "to", "buyer@customer.co.zw",
    "invoiceNumber", "INV-2026-001",
    "totalAmount", 1150.00,
    "currency", "USD",
    "pdfBase64", Base64.getEncoder().encodeToString(pdfBytes)
);

HttpHeaders headers = new HttpHeaders();
headers.set("X-API-Key", orgApiKey);
headers.setContentType(MediaType.APPLICATION_JSON);

HttpEntity<Map<String, Object>> request = new HttpEntity<>(invoice, headers);
ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);
```

**Option B: ERP Adapter**
Implement `ErpInvoicePort` interface:
```java
@Component
public class CustomErpAdapter implements ErpInvoicePort {
    
    @Override
    public ErpSource supports() {
        return ErpSource.CUSTOM_ERP;
    }
    
    @Override
    public List<CanonicalInvoice> fetchInvoices(String tenantId, List<String> invoiceIds) {
        // Query your ERP's invoice API
        // Map to CanonicalInvoice
        return invoices;
    }
    
    @Override
    public String fetchInvoicePdfAsBase64(String tenantId, String invoiceId) {
        // Fetch PDF from your ERP
        // Return Base64-encoded string
        return base64Pdf;
    }
}
```

#### C4 Integration (Access Point → Buyer ERP)

**Webhook Endpoint:**
```java
@RestController
@RequestMapping("/peppol/inbound")
public class PeppolInboundController {
    
    @PostMapping(consumes = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receiveInvoice(
            @RequestHeader("X-PEPPOL-Sender-ID") String senderId,
            @RequestHeader("X-Invoice-Number") String invoiceNumber,
            @RequestBody String ublXml) {
        
        try {
            // 1. Parse UBL XML
            Document doc = parseUblXml(ublXml);
            
            // 2. Extract invoice data
            InvoiceData invoice = extractInvoiceData(doc);
            
            // 3. Save to ERP database
            erpService.importInvoice(invoice);
            
            // 4. Return 200 OK
            return ResponseEntity.ok("Received");
            
        } catch (Exception e) {
            log.error("Failed to process PEPPOL invoice: {}", e.getMessage());
            return ResponseEntity.status(500).body("Processing failed");
        }
    }
}
```

**Configure Webhook URL:**
Update organization's `c4WebhookUrl` field:
```sql
UPDATE organizations 
SET c4_webhook_url = 'https://erp.acmecorp.co.zw/peppol/inbound'
WHERE id = 'd4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f';
```


---

## 8. Security & Compliance

### 8.1 Authentication & Authorization

**API Key Authentication:**
- Header: `X-API-Key: {apiKey}`
- Two modes:
  - Organization API key → `ROLE_ORG` (scoped to org data)
  - Admin session token → `ROLE_ADMIN` (full access)

**Key Rotation:**
```bash
curl -X POST "http://localhost:9199/api/v1/my/rotate-api-key" \
  -H "X-API-Key: $CURRENT_API_KEY"
```
- 5-minute grace period (both old and new keys accepted)
- New key returned in response

**HMAC Inbound Authentication:**
C3 receive endpoint (`/peppol/as4/receive`) requires HMAC-SHA256 signature:
```
X-PEPPOL-Signature: Base64(HmacSHA256(sharedSecret, requestBody))
```

Implementation:
```java
String expectedSig = Base64.getEncoder().encodeToString(
    Mac.getInstance("HmacSHA256")
        .doFinal(requestBody.getBytes(UTF_8))
);

// Constant-time comparison
if (!MessageDigest.isEqual(
        providedSig.getBytes(UTF_8),
        expectedSig.getBytes(UTF_8))) {
    return ResponseEntity.status(403).body("Invalid signature");
}
```

### 8.2 XML Security

**XML-DSIG Signing:**
- Algorithm: RSA-SHA256
- Key length: 2048-bit minimum (4096-bit recommended)
- Certificate embedded in KeyInfo for verification
- Enveloped signature over SOAP Body

**XML-Enc Encryption:**
- Symmetric: AES-256-CBC
- Key wrap: RSA-OAEP with MGF1-SHA1
- Key length: 256-bit ephemeral AES key
- Receiver certificate used for key wrapping

**Certificate Requirements:**
- X.509 v3
- PEPPOL-qualified CA (production) or self-signed (test)
- Key usage: Digital Signature, Key Encipherment
- Extended key usage: Email Protection, TLS Web Server Authentication
- Validity: minimum 1 year, maximum 3 years

### 8.3 PEPPOL Compliance Matrix

| Requirement | Status | Implementation |
|-------------|--------|----------------|
| **BIS Billing 3.0** | ✅ PASS | UblInvoiceBuilder |
| **EN16931 validation** | ⚠️ STUB | SchematronValidator (needs production rules) |
| **AS4 Profile 2.0** | ✅ PASS | As4TransportClientImpl |
| **XML-DSIG signing** | ✅ PASS | javax.xml.crypto |
| **XML-Enc encryption** | ⚠️ PARTIAL | Outbound only (inbound not decrypted) |
| **SMP/SML lookup** | ⚠️ PARTIAL | Client implemented, not registered in live SML |
| **MDN receipts** | ✅ PASS | MDN parsing in AS4 client |
| **4-corner routing** | ✅ PASS | PeppolDeliveryService |
| **Access Point registration** | ✅ PASS | eRegistry + AdminPeppolController |


### 8.4 Security Audit Findings

From QA audit (June 2026):

**CRITICAL Issues:**
1. **Schematron stub** — No real validation rules; all invoices pass
   - **Impact:** Non-compliant invoices accepted
   - **Remediation:** Replace with OpenPEPPOL official .sch file

2. **Path traversal** — `PdfAttachmentResolver.resolveFromFile()` has no allowlist
   - **Impact:** File system traversal via `../../etc/passwd`
   - **Remediation:** Add directory allowlist validation

3. **AS4 payload unencrypted** — XML-Enc not implemented for inbound
   - **Impact:** Inbound messages not decrypted
   - **Remediation:** Implement inbound decryption or document limitation

4. **PEPPOL /as4/inbox unauthenticated** — Public endpoint returns all documents
   - **Impact:** PII leak (invoice data visible to anyone)
   - **Remediation:** Add authentication (ADMIN role)

5. **UBL XML in plaintext** — `InboundDocument.ublXmlPayload` stored unencrypted
   - **Impact:** Database compromise exposes invoice data
   - **Remediation:** Encrypt at rest or document as accepted risk

**HIGH Issues:**
6. **Weak session token RNG** — Uses `UUID.randomUUID()` instead of `SecureRandom`
7. **No Base64 size limit** — PDF decode can allocate 18MB per thread
8. **`@Async @Transactional` fragility** — Race conditions possible

---

## 9. Deployment Guide

### 9.1 Environment Variables

**PEPPOL-specific:**
```bash
# PEPPOL SMP configuration
PEPPOL_SMP_BASE_URL=https://smp.peppoltest.org  # or production SMP
PEPPOL_SMP_CACHE_TTL=3600  # SMP lookup cache TTL in seconds

# PEPPOL SML configuration
PEPPOL_SML_DOMAIN=sml.peppoltest.org  # or sml.peppol.eu for production
PEPPOL_SML_DNS_PREFIX=b

# Public-facing base URL (for invitation links)
APP_BASE_URL=https://ap.invoicedirect.biz
```

**Database:**
```bash
DB_URL=jdbc:postgresql://postgres:5432/massmailer
DB_USER=mailer
DB_PASS=<strong-password>
HIBERNATE_DDL_AUTO=update  # or validate for production
```

**SMTP:**
```bash
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=no-reply@esolutions.co.zw
GMAIL_APP_PASSWORD=<16-char-app-password>
```

### 9.2 Docker Deployment

**docker-compose.yml:**
```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: massmailer
      POSTGRES_USER: mailer
      POSTGRES_PASSWORD: ${DB_PASS}
    volumes:
      - pgdata:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  mass-mailer:
    build: .
    ports:
      - "9199:9199"
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/massmailer
      DB_USER: mailer
      DB_PASS: ${DB_PASS}
      PEPPOL_SMP_BASE_URL: ${PEPPOL_SMP_BASE_URL}
      PEPPOL_SML_DOMAIN: ${PEPPOL_SML_DOMAIN}
      APP_BASE_URL: ${APP_BASE_URL}
    depends_on:
      - postgres

volumes:
  pgdata:
```


**Build & Run:**
```bash
docker-compose up --build
```

**Verification:**
```bash
# Health check
curl http://localhost:9199/actuator/health

# PEPPOL AS4 health
curl http://localhost:9199/peppol/as4/health

# Swagger UI (if enabled)
open http://localhost:9199/swagger-ui.html
```

### 9.3 Production Checklist

**Pre-deployment:**
- [ ] Replace Schematron stub with OpenPEPPOL official rules
- [ ] Obtain PEPPOL-qualified X.509 certificates from accredited CA
- [ ] Register Access Point with OpenPEPPOL authority
- [ ] Configure production SMP/SML endpoints
- [ ] Set strong database password (`DB_PASS`)
- [ ] Set strong admin password (`ADMIN_PASSWORD`)
- [ ] Configure webhook secret (`WEBHOOK_SECRET` ≥32 chars)
- [ ] Disable Swagger UI (`SWAGGER_ENABLED=false`)
- [ ] Set `HIBERNATE_DDL_AUTO=validate`
- [ ] Enable HTTPS/TLS with valid SSL certificate
- [ ] Configure firewall (allow 9199, 8199, 5432 internally only)

**Post-deployment:**
- [ ] Verify SMP registration: `curl https://smp.peppol.eu/{participantId}/...`
- [ ] Test full AS4 flow with test trading partner
- [ ] Verify MDN receipts are parsed correctly
- [ ] Test inbound C3 endpoint with HMAC authentication
- [ ] Monitor logs for errors (`/var/log/massmailer/`)
- [ ] Set up database backups (pg_dump daily)
- [ ] Configure log rotation
- [ ] Set up monitoring (Prometheus + Grafana recommended)

---

## 10. Troubleshooting

### 10.1 Common Issues

**Issue:** Schematron validation always passes (all invoices accepted)

**Cause:** Stub `.sch` file with no assertions

**Solution:**
1. Download official PEPPOL EN16931 rules:
   ```bash
   wget https://github.com/OpenPEPPOL/peppol-bis-invoice-3/raw/master/rules/sch/PEPPOL-EN16931-UBL.sch
   ```
2. Place in `src/main/resources/schematron/PEPPOL-EN16931-UBL.sch`
3. Rebuild and redeploy

---

**Issue:** AS4 delivery fails with "Receiver certificate required"

**Cause:** Receiver AP not registered with certificate

**Solution:**
1. Obtain receiver's X.509 certificate
2. Register AP with certificate:
   ```bash
   curl -X POST http://localhost:9199/api/v1/eregistry/access-points \
     -H "Content-Type: application/json" \
     -d '{
       "participantId": "0190:ZW987654321",
       "role": "RECEIVER",
       "certificate": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"
     }'
   ```

---

**Issue:** Inbound documents not routing to C4 (status stuck at RECEIVED)

**Cause:** Organization's `c4WebhookUrl` not configured

**Solution:**
```sql
UPDATE organizations 
SET c4_webhook_url = 'https://erp.example.com/peppol/inbound'
WHERE id = '<org-uuid>';
```

Verify `PeppolC4RoutingJob` logs:
```bash
tail -f /var/log/massmailer/app.log | grep PeppolC4RoutingJob
```


---

**Issue:** "PEPPOL participant link not found" error when sending invoices

**Cause:** Customer not linked to their receiver AP

**Solution:**
1. Register buyer's AP (if not already):
   ```bash
   curl -X POST http://localhost:9199/api/v1/eregistry/access-points \
     -d '{"participantId": "0190:ZW...", "role": "RECEIVER", ...}'
   ```

2. Create participant link:
   ```bash
   curl -X POST http://localhost:9199/api/v1/eregistry/participant-links \
     -d '{
       "customerEmail": "buyer@corp.co.zw",
       "participantId": "0190:ZW...",
       "receiverAccessPointId": "<ap-id>"
     }'
   ```

---

**Issue:** SMP lookup fails with "DNS resolution error"

**Cause:** Participant ID not registered in SML

**Solution:**
- For test network: Verify participant ID format and SML domain (`sml.peppoltest.org`)
- For production: Register participant with OpenPEPPOL authority
- Fallback: Use local eRegistry (no SMP/SML lookup required)

---

**Issue:** XML-DSIG signature verification fails at receiver

**Cause:** Certificate mismatch or canonicalization issue

**Solution:**
1. Verify sender certificate is uploaded correctly:
   ```bash
   curl http://localhost:9199/api/v1/admin/orgs/{orgId}/peppol/certs/active
   ```

2. Check certificate validity dates
3. Verify receiver expects inclusive C14N (our implementation)
4. Inspect SOAP envelope signature element:
   ```xml
   <ds:Signature>
     <ds:SignedInfo>
       <ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
       <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/>
       <ds:Reference URI="#body">...</ds:Reference>
     </ds:SignedInfo>
   </ds:Signature>
   ```

---

### 10.2 Logging & Diagnostics

**Key Log Files:**
```
/var/log/massmailer/app.log          # Application logs
/var/log/massmailer/access.log       # HTTP access logs
/var/log/massmailer/peppol-audit.log # PEPPOL-specific audit trail
```

**Log Levels:**
```yaml
# application.yml
logging:
  level:
    com.esolutions.massmailer.peppol: DEBUG
    org.apache.xml.security: INFO
    com.helger.schematron: DEBUG
```

**Enable SQL Logging:**
```yaml
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
```

**PEPPOL Delivery Trace:**
```bash
# Tail PEPPOL logs
tail -f /var/log/massmailer/app.log | grep -E "PEPPOL|AS4|UBL|Schematron"

# Search for specific invoice
grep "INV-2026-001" /var/log/massmailer/app.log
```

**Database Queries:**
```sql
-- Recent PEPPOL deliveries
SELECT invoice_number, status, schematron_passed, error_message, transmitted_at
FROM peppol_delivery_records
WHERE organization_id = '<org-uuid>'
ORDER BY transmitted_at DESC
LIMIT 20;

-- Failed deliveries
SELECT invoice_number, receiver_participant_id, error_message
FROM peppol_delivery_records
WHERE status = 'FAILED'
  AND transmitted_at > NOW() - INTERVAL '7 days';

-- Participant links
SELECT c.email, p.participant_id, ap.participant_name, p.preferred_channel
FROM peppol_participant_links p
JOIN customer_contacts c ON p.customer_contact_id = c.id
JOIN peppol_access_points ap ON p.receiver_access_point_id = ap.id
WHERE p.organization_id = '<org-uuid>';
```


---

## 11. Known Limitations

### 11.1 Standards Compliance

| Limitation | Impact | Workaround |
|------------|--------|------------|
| **Schematron stub** | All invoices pass validation; non-compliant documents accepted | Replace with OpenPEPPOL official .sch file before production |
| **No SML registration** | Cannot be discovered by other PEPPOL participants | Register with OpenPEPPOL authority; use local eRegistry for known partners |
| **Simplified HTTP only** | No production-ready AS4 implementations deployed | Upload certificates and set `simplifiedHttpDelivery=false` |
| **No inbound XML-Enc** | Inbound messages not decrypted | Document as limitation; use simplified HTTP for inbound |

### 11.2 Security

| Limitation | Impact | Remediation |
|------------|--------|-------------|
| **Path traversal** | File system access via `pdfFilePath` | Add directory allowlist validation |
| **UBL XML plaintext storage** | Database compromise exposes invoice data | Encrypt `ublXmlPayload` column at rest |
| **Public inbox endpoint** | PII leak via `/peppol/as4/inbox` | Add authentication (ADMIN role required) |
| **Weak UUID tokens** | Session tokens predictable | Migrate to `SecureRandom` |
| **No rate limiting on AS4** | DoS vulnerability | Add rate limiter (Bucket4j) |

### 11.3 Scalability

| Limitation | Impact | Recommendation |
|------------|--------|----------------|
| **Single-node deployment** | No horizontal scaling | Deploy behind load balancer with sticky sessions |
| **In-memory SMP cache** | Cache not shared across nodes | Migrate to Redis or Hazelcast |
| **Virtual thread count uncapped** | Memory exhaustion under heavy load | Add semaphore-based throttle (already present for SMTP) |
| **Synchronous C4 routing** | C4 webhook failures block job | Migrate to message queue (RabbitMQ/Kafka) |

### 11.4 Functional

| Limitation | Impact | Future Enhancement |
|------------|--------|-------------------|
| **Single invoice line** | UBL invoices have one summary line | Support multi-line invoices with `CanonicalInvoice.lineItems` |
| **No credit notes** | Only invoices supported | Implement `CreditNote` UBL document type |
| **No invoice cancellation** | Sent invoices cannot be voided | Implement cancellation flow with PEPPOL message |
| **No attachment support** | Cannot send supporting documents | Add MIME multipart payload support |

---

## 12. Future Roadmap

### 12.1 Short-Term (Q3 2026)

**Priority 1: Production Compliance**
- [ ] Replace Schematron stub with OpenPEPPOL official rules
- [ ] Fix path traversal vulnerability (directory allowlist)
- [ ] Add authentication to `/peppol/as4/inbox`
- [ ] Implement at-rest encryption for `ublXmlPayload`

**Priority 2: Stability**
- [ ] Migrate session tokens to `SecureRandom`
- [ ] Add AS4 endpoint rate limiting
- [ ] Implement connection pooling for RestTemplate
- [ ] Add circuit breaker for SMP lookups (Resilience4j)

**Priority 3: Monitoring**
- [ ] Prometheus metrics endpoint
- [ ] Grafana dashboard (delivery success rate, latency, errors)
- [ ] Alert rules (failed deliveries > threshold, certificate expiry)

### 12.2 Medium-Term (Q4 2026)

**Enhanced PEPPOL Support**
- [ ] Credit note document type
- [ ] Multi-line invoice support (CanonicalInvoice.lineItems)
- [ ] Attachment support (MIME multipart)
- [ ] Invoice cancellation flow
- [ ] Inbound XML-Enc decryption

**Scalability**
- [ ] Redis-based SMP cache (shared across nodes)
- [ ] RabbitMQ for C4 routing (async, retry-able)
- [ ] Horizontal scaling support (stateless design)
- [ ] Database connection pooling tuning


**Operational Excellence**
- [ ] Automated certificate rotation (Let's Encrypt integration)
- [ ] Certificate expiry monitoring (alert 30 days before)
- [ ] Database backups to S3 (pg_dump daily)
- [ ] Log aggregation (ELK stack or CloudWatch)
- [ ] Health check dashboard (Spring Boot Admin)

### 12.3 Long-Term (2027+)

**Regional Expansion**
- [ ] Multi-region deployment (Africa, Europe)
- [ ] Localization (Swahili, French, Portuguese)
- [ ] Country-specific tax validation (Kenya KRA, South Africa SARS)
- [ ] Regional SMP registration (EU, Africa)

**Advanced Features**
- [ ] Real-time delivery status webhooks
- [ ] Buyer portal (view sent invoices, download PDFs)
- [ ] Invoice approval workflow
- [ ] Three-way matching (PO → GRN → Invoice)
- [ ] PEPPOL Catalogue exchange
- [ ] PEPPOL Order document type

**AI/ML Enhancements**
- [ ] Invoice data extraction from PDFs (OCR + NLP)
- [ ] Fraud detection (anomaly detection on amounts)
- [ ] Duplicate invoice detection
- [ ] Auto-routing based on past delivery patterns

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| **Access Point (AP)** | A gateway in the PEPPOL network that sends/receives documents on behalf of participants |
| **AS4** | Applicability Statement 4 — OASIS ebMS 3.0 transport profile used by PEPPOL |
| **BIS** | Business Interoperability Specification — defines document formats and processes |
| **C1** | Corner 1 — Supplier's ERP system |
| **C2** | Corner 2 — Supplier's Access Point (sender) |
| **C3** | Corner 3 — Buyer's Access Point (receiver) |
| **C4** | Corner 4 — Buyer's ERP system |
| **ebMS** | Electronic Business Messaging Service — OASIS standard for B2B messaging |
| **EN16931** | European standard for electronic invoicing (semantic data model) |
| **eRegistry** | Local registry of Access Points and participant links (SMP-equivalent) |
| **HMAC** | Hash-based Message Authentication Code — used for inbound authentication |
| **MDN** | Message Disposition Notification — AS4 acknowledgment receipt |
| **Participant ID** | Unique identifier for organizations in PEPPOL network (e.g., `0190:ZW123456789`) |
| **PEPPOL** | Pan-European Public Procurement On-Line — international e-invoicing network |
| **Schematron** | ISO standard for business rule validation in XML documents |
| **SML** | Service Metadata Locator — DNS-based registry for discovering SMPs |
| **SMP** | Service Metadata Publisher — directory service for participant endpoint lookup |
| **UBL** | Universal Business Language — OASIS XML standard for business documents |
| **XML-DSIG** | XML Digital Signature — W3C standard for signing XML documents |
| **XML-Enc** | XML Encryption — W3C standard for encrypting XML data |

---

## Appendix B: References

### Standards & Specifications

- **PEPPOL BIS Billing 3.0**  
  https://docs.peppol.eu/poacc/billing/3.0/

- **OpenPEPPOL Transport Infrastructure AS4 Profile v2.0**  
  https://docs.peppol.eu/edelivery/as4/specification/

- **UBL 2.1 Invoice**  
  http://docs.oasis-open.org/ubl/UBL-2.1.html

- **EN16931 European Semantic Standard**  
  https://ec.europa.eu/digital-building-blocks/wikis/display/DIGITAL/Registry+of+supporting+artefacts+to+implement+EN16931

- **OASIS ebMS 3.0**  
  http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/core/

- **ISO Schematron**  
  https://schematron.com/

### Code Repositories

- **OpenPEPPOL BIS 3.0 Rules**  
  https://github.com/OpenPEPPOL/peppol-bis-invoice-3

- **ph-schematron (Helger)**  
  https://github.com/phax/ph-schematron

- **Apache Santuario (XML Security)**  
  https://santuario.apache.org/

### Tools & Testing

- **PEPPOL Test Network**  
  https://peppol.org/test-network/

- **ValidEx (PEPPOL Validator)**  
  https://validex.ecosio.com/

- **UBL Schema Validator**  
  http://www.validator.oasis-open.org/


---

## Appendix C: Sample API Workflows

### Workflow 1: Full Organization Onboarding

```bash
#!/bin/bash
set -e

# Configuration
API_BASE="http://localhost:9199"
ADMIN_USER="admin"
ADMIN_PASS="your-password"

# Step 1: Admin login
echo "Step 1: Admin login..."
TOKEN=$(curl -s -X POST "$API_BASE/api/v1/admin/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | jq -r '.token')

echo "Token: $TOKEN"

# Step 2: Create organization
echo "Step 2: Creating organization..."
ORG_RESPONSE=$(curl -s -X POST "$API_BASE/api/v1/organizations" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Corp",
    "slug": "testcorp",
    "companyName": "Test Corporation Ltd",
    "vatNumber": "123456789",
    "senderEmail": "invoices@testcorp.co.zw"
  }')

ORG_ID=$(echo "$ORG_RESPONSE" | jq -r '.id')
ORG_API_KEY=$(echo "$ORG_RESPONSE" | jq -r '.apiKey')
echo "Org ID: $ORG_ID"
echo "API Key: $ORG_API_KEY"

# Step 3: PEPPOL onboarding
echo "Step 3: PEPPOL onboarding..."
curl -s -X POST "$API_BASE/api/v1/admin/orgs/$ORG_ID/peppol/onboard" \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "deliveryMode": "BOTH",
    "participantName": "Test Corp AP",
    "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
    "simplifiedHttpDelivery": true
  }' | jq '.'

# Step 4: Upload certificate (placeholder - generate real cert)
echo "Step 4: Upload certificate..."
# In production: generate real PEPPOL cert
# openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem -days 365

# Step 5: Register customer
echo "Step 5: Registering customer..."
curl -s -X POST "$API_BASE/api/v1/organizations/$ORG_ID/customers" \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "buyer@customercorp.co.zw",
    "name": "John Buyer",
    "companyName": "Customer Corp",
    "deliveryMode": "PEPPOL",
    "vatNumber": "987654321"
  }' | jq '.'

# Step 6: Register buyer AP
echo "Step 6: Registering buyer AP..."
AP_RESPONSE=$(curl -s -X POST "$API_BASE/api/v1/eregistry/access-points" \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "organizationId": null,
    "participantId": "0190:ZW987654321",
    "participantName": "Customer Corp AP",
    "role": "RECEIVER",
    "endpointUrl": "https://erp.customercorp.co.zw/peppol/receive",
    "simplifiedHttpDelivery": true
  }')

RECEIVER_AP_ID=$(echo "$AP_RESPONSE" | jq -r '.id')
echo "Receiver AP ID: $RECEIVER_AP_ID"

# Step 7: Create participant link
echo "Step 7: Creating participant link..."
curl -s -X POST "$API_BASE/api/v1/eregistry/participant-links" \
  -H "X-API-Key: $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"organizationId\": \"$ORG_ID\",
    \"customerEmail\": \"buyer@customercorp.co.zw\",
    \"participantId\": \"0190:ZW987654321\",
    \"receiverAccessPointId\": \"$RECEIVER_AP_ID\",
    \"preferredChannel\": \"PEPPOL\"
  }" | jq '.'

echo ""
echo "✅ Onboarding complete!"
echo "Organization ID: $ORG_ID"
echo "Organization API Key: $ORG_API_KEY"
echo ""
echo "Next: Send test invoice with 'POST /api/v1/mail/invoice'"
```


---

## Appendix D: PDF Email Delivery Endpoints

The platform supports both traditional email delivery and PEPPOL electronic delivery. When a customer doesn't have PEPPOL enabled, invoices are automatically delivered via email with PDF attachment.

### D.1 Single Invoice Email

#### Send Invoice with JSON + Base64 PDF
`POST /api/v1/mail/invoice`

**Headers:**
```
X-API-Key: {org-api-key}
Content-Type: application/json
```

**Request Body:**
```json
{
  "to": "customer@example.com",
  "recipientName": "John Buyer",
  "recipientCompany": "Acme Corporation",
  "subject": "Invoice INV-2026-001 from eSolutions",
  "templateName": "invoice",
  "invoiceNumber": "INV-2026-001",
  "invoiceDate": "2026-06-22",
  "dueDate": "2026-07-22",
  "subtotalAmount": 1000.00,
  "vatAmount": 150.00,
  "totalAmount": 1150.00,
  "currency": "USD",
  "pdfBase64": "JVBERi0xLjQKJeLjz9MKMyAwIG9iaiA8PAovVHlwZSAvUGFnZQ...",
  "fiscalMetadata": {
    "verificationCode": "ZIM-2026-ABC123",
    "fiscalSignature": "3045022100...",
    "fiscalQrCode": "https://verify.zimra.co.zw/..."
  }
}
```

**Response:** `200 OK`
```json
{
  "status": "SENT",
  "messageId": "msg-12345678-abcd-ef01-2345-67890abcdef",
  "recipient": "customer@example.com",
  "sentAt": "2026-06-22T14:30:00Z"
}
```

**cURL Example:**
```bash
curl -X POST http://localhost:9199/api/v1/mail/invoice \
  -H "X-API-Key: $ORG_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "buyer@customer.co.zw",
    "subject": "Invoice INV-2026-001",
    "templateName": "invoice",
    "invoiceNumber": "INV-2026-001",
    "totalAmount": 1150.00,
    "currency": "USD",
    "pdfBase64": "'$(base64 -w 0 invoice.pdf)'"
  }'
```

#### Send Invoice with PDF Upload
`POST /api/v1/mail/invoice/upload`

**Headers:**
```
X-API-Key: {org-api-key}
Content-Type: multipart/form-data
```

**Form Data:**
- `file`: PDF file (binary)
- `to`: recipient email
- `subject`: email subject
- `invoiceNumber`: invoice reference
- `totalAmount`: total amount
- `currency`: currency code
- `templateName`: email template (optional, defaults to "invoice")

**cURL Example:**
```bash
curl -X POST http://localhost:9199/api/v1/mail/invoice/upload \
  -H "X-API-Key: $ORG_API_KEY" \
  -F "file=@invoice.pdf" \
  -F "to=buyer@customer.co.zw" \
  -F "subject=Invoice INV-2026-001" \
  -F "invoiceNumber=INV-2026-001" \
  -F "totalAmount=1150.00" \
  -F "currency=USD"
```

### D.2 Bulk Campaign Email

#### Create Email Campaign
`POST /api/v1/campaigns`

Sends invoices to multiple recipients in a single batch.

**Request Body:**
```json
{
  "name": "June 2026 Invoices",
  "subject": "Your Invoice from eSolutions",
  "templateName": "invoice",
  "recipients": [
    {
      "email": "buyer1@company1.co.zw",
      "name": "John Buyer",
      "companyName": "Company One Ltd",
      "invoiceNumber": "INV-2026-001",
      "totalAmount": 1150.00,
      "currency": "USD",
      "pdfFilePath": "/var/invoices/INV-2026-001.pdf"
    },
    {
      "email": "buyer2@company2.co.zw",
      "name": "Jane Buyer",
      "companyName": "Company Two Ltd",
      "invoiceNumber": "INV-2026-002",
      "totalAmount": 2300.00,
      "currency": "USD",
      "pdfBase64": "JVBERi0xLjQKJe..."
    }
  ]
}
```

**Response:** `202 Accepted`
```json
{
  "campaignId": "campaign-uuid",
  "status": "QUEUED",
  "totalRecipients": 2,
  "createdAt": "2026-06-22T14:30:00Z"
}
```

#### Check Campaign Status
`GET /api/v1/campaigns/{campaignId}`

**Response:**
```json
{
  "id": "campaign-uuid",
  "name": "June 2026 Invoices",
  "status": "IN_PROGRESS",
  "totalRecipients": 2,
  "sentCount": 1,
  "failedCount": 0,
  "skippedCount": 0,
  "recipients": [
    {
      "email": "buyer1@company1.co.zw",
      "invoiceNumber": "INV-2026-001",
      "deliveryStatus": "DELIVERED",
      "deliveryMode": "EMAIL",
      "sentAt": "2026-06-22T14:31:00Z"
    },
    {
      "email": "buyer2@company2.co.zw",
      "invoiceNumber": "INV-2026-002",
      "deliveryStatus": "PENDING",
      "deliveryMode": "EMAIL"
    }
  ]
}
```

#### Retry Failed Recipients
`POST /api/v1/campaigns/{campaignId}/retry`

Re-queues all failed recipients for retry.

### D.3 ERP Integration Email

#### Dispatch from ERP
`POST /api/v1/erp/dispatch`

Fetches invoices from connected ERP and dispatches via email or PEPPOL.

**Request Body:**
```json
{
  "erpSource": "ODOO",
  "tenantId": "acme-corp",
  "invoiceIds": ["INV-001", "INV-002", "INV-003"],
  "campaignName": "ERP Auto-Dispatch June 2026",
  "templateName": "invoice"
}
```

**Response:** Campaign object (same as bulk campaign)

#### Dispatch with PDF Upload
`POST /api/v1/erp/dispatch/upload`

Uploads PDFs + metadata CSV and dispatches as campaign.

**Form Data:**
- `pdfs`: Multiple PDF files
- `metadata`: CSV file with columns: `filename,email,invoiceNumber,amount,currency`

**Example metadata.csv:**
```csv
filename,email,invoiceNumber,amount,currency
INV-001.pdf,buyer1@corp.co.zw,INV-2026-001,1150.00,USD
INV-002.pdf,buyer2@corp.co.zw,INV-2026-002,2300.00,USD
```

**cURL Example:**
```bash
curl -X POST http://localhost:9199/api/v1/erp/dispatch/upload \
  -H "X-API-Key: $ORG_API_KEY" \
  -F "pdfs=@INV-001.pdf" \
  -F "pdfs=@INV-002.pdf" \
  -F "metadata=@metadata.csv"
```

### D.4 Email Templates

Available email templates (in `src/main/resources/templates/email/`):

| Template | Purpose | Variables |
|----------|---------|-----------|
| `invoice.html` | Standard invoice email | `companyName`, `recipientName`, `invoiceNumber`, `totalAmount`, `dueDate`, `fiscalMetadata` |
| `generic.html` | Generic notification | `companyName`, `recipientName`, `subject`, `body` |
| `welcome.html` | Welcome email | `companyName`, `recipientName` |
| `peppol-invitation.html` | PEPPOL self-registration invite | `orgName`, `customerEmail`, `inviteUrl`, `expiresAt` |
| `peppol-invoice-notification.html` | Unregistered buyer notification | `supplierName`, `invoiceNumber`, `totalAmount` |
| `platform-invoice.html` | Platform billing invoice | `companyName`, `billingPeriod`, `totalAmount` |

**Template Variables (Thymeleaf):**
```html
<p>Dear <span th:text="${recipientName}">Customer</span>,</p>
<p>Please find attached invoice <strong th:text="${invoiceNumber}">INV-001</strong>
   for <span th:text="${totalAmount}">$1,000.00</span> 
   <span th:text="${currency}">USD</span>.</p>
```

### D.5 Delivery Mode Router

The platform automatically routes invoices based on customer configuration:

**Routing Logic:**
```
1. Load CustomerContact by email
2. Check customer.deliveryMode:
   - EMAIL: Send PDF via SMTP
   - PEPPOL: Check PeppolParticipantLink
     → If link exists: Send via AS4
     → If no link: Send notification email + log routing failure
   - BOTH: Send via PEPPOL (primary) + EMAIL (fallback)
3. If PEPPOL fails: Automatic fallback to EMAIL
```

**Example: Mixed Delivery Campaign**
```json
{
  "recipients": [
    {
      "email": "peppol-buyer@corp.co.zw",  // Has PEPPOL link → AS4
      "invoiceNumber": "INV-001"
    },
    {
      "email": "email-buyer@corp.co.zw",    // No PEPPOL → Email
      "invoiceNumber": "INV-002"
    }
  ]
}
```

**Result:**
- `INV-001`: Delivered via PEPPOL AS4
- `INV-002`: Delivered via SMTP email

---


## Appendix E: PEPPOL Network Deep Dive

### E.1 Network Architecture

The PEPPOL network is a federated infrastructure of certified Access Points (APs) that enables secure, standardized document exchange across borders and systems.

**Network Components:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    PEPPOL Network Architecture                   │
└──────────────────────────────────────────────────────────────────┘

                        ┌─────────────────┐
                        │   PEPPOL TSP    │
                        │   (Governing    │
                        │   Authority)    │
                        └────────┬────────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
           ┌────────▼─────────┐      ┌───────▼────────┐
           │   SML Registry   │      │  Certificate   │
           │ (DNS-based AP    │      │   Authority    │
           │  discovery)      │      │   (PKI)        │
           └────────┬─────────┘      └───────┬────────┘
                    │                        │
        ┌───────────┴───────────┐           │
        │                       │           │
   ┌────▼────┐           ┌─────▼────┐      │
   │ SMP #1  │           │ SMP #2   │      │
   │ (Service│           │ (Service │      │
   │ Metadata│           │ Metadata)│      │
   └────┬────┘           └─────┬────┘      │
        │                      │            │
        │                      │            │
   ┌────▼──────────────────────▼────────────▼────┐
   │         Access Points (Certified)           │
   ├─────────────────────────────────────────────┤
   │  AP #1      AP #2      AP #3    AP #4       │
   │  (Our)      (Buyer1)   (Buyer2) (Buyer3)    │
   └──┬─────────────┬─────────┬───────────┬──────┘
      │             │         │           │
   ┌──▼──┐      ┌──▼──┐   ┌──▼──┐     ┌──▼──┐
   │ ERP │      │ ERP │   │ ERP │     │ ERP │
   │ C1  │      │ C4  │   │ C4  │     │ C4  │
   └─────┘      └─────┘   └─────┘     └─────┘
```

**Component Descriptions:**

1. **PEPPOL Transport Service Provider (TSP)**
   - Governing body (OpenPEPPOL AISBL)
   - Certifies Access Point providers
   - Maintains network standards
   - Issues PEPPOL participant IDs

2. **Service Metadata Locator (SML)**
   - DNS-based directory service
   - Maps participant IDs to SMP endpoints
   - Format: `b-{scheme}-{value}.{sml-domain}`
   - Example: `b-0190-zw123456789.sml.peppoltest.org`

3. **Service Metadata Publisher (SMP)**
   - Publishes AP endpoint information
   - Returns endpoint URL, transport profile, certificate
   - BDXR SMP v2 XML format
   - One SMP can serve multiple APs

4. **Access Point (AP)**
   - Certified gateway for document exchange
   - Implements AS4 Profile 2.0
   - Handles message signing, encryption, routing
   - Our implementation: InvoiceDirect platform

5. **Certificate Authority (CA)**
   - Issues X.509 certificates for APs
   - PEPPOL-qualified CAs for production
   - Test network allows self-signed certs

### E.2 Document Exchange Flow

**Step-by-step PEPPOL delivery:**

```
1. C1 (Supplier ERP) generates invoice
   ↓
2. C1 sends invoice data + PDF to C2 (Our AP)
   ↓
3. C2 converts to UBL 2.1 BIS 3.0 format
   ↓
4. C2 validates against Schematron rules (EN16931)
   ↓
5. C2 queries SML for buyer's SMP
   DNS: b-0190-zw987654321.sml.peppoltest.org
   ↓ Returns SMP URL
6. C2 queries SMP for buyer's AP endpoint
   GET https://smp.example.com/0190:ZW987654321/services/{docType}
   ↓ Returns endpoint + certificate
7. C2 builds AS4 SOAP envelope
   - Embeds UBL XML in Body
   - Adds ebMS 3.0 header (MessageInfo, PartyInfo, etc.)
   ↓
8. C2 signs envelope with XML-DSIG
   - Algorithm: RSA-SHA256
   - KeyInfo: sender X.509 certificate
   ↓
9. C2 encrypts Body with XML-Enc
   - Symmetric: AES-256
   - Key wrap: RSA-OAEP with buyer's public key
   ↓
10. C2 POSTs signed+encrypted SOAP to C3
    POST https://ap.buyercorp.co.zw/peppol/as4/receive
    Content-Type: application/soap+xml
    ↓
11. C3 (Buyer's AP) receives message
    - Verifies XML-DSIG signature
    - Decrypts Body with private key
    - Extracts UBL XML
    ↓
12. C3 validates UBL (optional)
    ↓
13. C3 sends MDN receipt to C2
    <eb:Receipt>
      <eb:MessageInfo>
        <eb:MessageId>mdn-uuid</eb:MessageId>
      </eb:MessageInfo>
    </eb:Receipt>
    ↓
14. C3 routes UBL to C4 (Buyer ERP)
    POST https://erp.buyercorp.co.zw/peppol/inbound
    Body: UBL XML
    ↓
15. C4 imports invoice into procurement system
    ↓
16. C2 receives MDN, marks delivery as successful
```

### E.3 PEPPOL Test Network

**Endpoints:**
- **SML Domain:** `sml.peppoltest.org`
- **SMP Base URL:** `https://smp.peppoltest.org`
- **Network Type:** Open test network (no TSP certification required)

**Configuration:**
```yaml
peppol:
  smp:
    base-url: https://smp.peppoltest.org
  sml:
    domain: sml.peppoltest.org
    dns-prefix: b
```

**Test Participant IDs:**
Use scheme `9915` for test identifiers:
- `9915:test-supplier`
- `9915:test-buyer`

**Test Certificates:**
Self-signed certificates are accepted:
```bash
openssl req -x509 -newkey rsa:4096 \
  -keyout peppol-test-key.pem \
  -out peppol-test-cert.pem \
  -days 365 \
  -nodes \
  -subj "/CN=Test AP/O=eSolutions/C=ZW"
```

### E.4 PEPPOL Production Network

**Endpoints:**
- **SML Domain:** `sml.peppol.eu`
- **Network Type:** Production (TSP certification required)

**Prerequisites:**
1. **AP Certification:**
   - Apply to become a PEPPOL TSP or use certified provider
   - Pass conformance testing
   - Sign Service Provider Agreement

2. **PEPPOL-Qualified Certificate:**
   - Obtain from accredited CA (e.g., Evrotrust, CESNET)
   - Subject DN must match PEPPOL participant ID
   - Key usage: Digital Signature, Key Encipherment

3. **SMP Registration:**
   - Register participant IDs with SMP provider
   - Configure endpoint URLs and certificates
   - Publish service metadata

4. **Production Participant IDs:**
   Zimbabwe uses scheme `0190` (ISO 6523):
   - Format: `0190:ZW{VAT-number}`
   - Example: `0190:ZW123456789`

**Configuration:**
```yaml
peppol:
  smp:
    base-url: https://smp.peppol.eu
  sml:
    domain: sml.peppol.eu
    dns-prefix: b
```

### E.5 Participant ID Schemes

PEPPOL uses ISO 6523 identifier schemes:

| Scheme | Code | Country | Authority | Example |
|--------|------|---------|-----------|---------|
| **GLN** | `0088` | Global | GS1 | `0088:1234567890128` |
| **DUNS** | `0060` | Global | D&B | `0060:123456789` |
| **VAT (Zimbabwe)** | `0190` | ZW | ZIMRA | `0190:ZW123456789` |
| **VAT (South Africa)** | `0190` | ZA | SARS | `0190:ZA4012345678` |
| **VAT (Kenya)** | `0190` | KE | KRA | `0190:KEP051234567X` |
| **Test Network** | `9915` | Test | N/A | `9915:test-id` |

**InvoiceDirect Default:**
Auto-derives from VAT: `0190:ZW{organization.vatNumber}`

### E.6 Document Types & Processes

**Invoice Document Type:**
```
urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##
urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1
```

**Process ID (Billing):**
```
urn:fdc:peppol.eu:2017:poacc:billing:01:1.0
```

**Other PEPPOL Document Types** (future support):
- Credit Note: `urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2::CreditNote...`
- Order: `urn:oasis:names:specification:ubl:schema:xsd:Order-2::Order...`
- Catalogue: `urn:oasis:names:specification:ubl:schema:xsd:Catalogue-2::Catalogue...`
- Despatch Advice: `urn:oasis:names:specification:ubl:schema:xsd:DespatchAdvice-2::DespatchAdvice...`

### E.7 Error Codes & Troubleshooting

**Common PEPPOL Errors:**

| Error Code | Description | Solution |
|------------|-------------|----------|
| `EBMS-0001` | Value not recognized | Check participant ID format |
| `EBMS-0004` | Error in payload | UBL XML invalid |
| `EBMS-0101` | Signature validation failure | Verify sender certificate matches |
| `EBMS-0102` | Decryption failure | Verify receiver certificate used |
| `EBMS-0201` | Duplicate message | Check message ID uniqueness |
| `EBMS-0301` | Invalid participant | Recipient not registered in SMP |
| `EBMS-0302` | Unknown participant | SML lookup failed |

**Network Diagnostics:**

```bash
# Test SML DNS resolution
dig b-0190-zw123456789.sml.peppoltest.org

# Test SMP endpoint
curl -v "https://smp.peppoltest.org/0190:ZW123456789/services/..."

# Test AS4 endpoint health
curl -v "https://ap.invoicedirect.biz/peppol/as4/health"

# Validate UBL XML
xmllint --schema UBL-Invoice-2.1.xsd invoice.xml

# Test Schematron rules
java -jar schematron-validator.jar invoice.xml PEPPOL-EN16931-UBL.sch
```

---

## Document End

**Total Pages:** ~80 pages (estimated in DOCX format)  
**Document Version:** 1.0  
**Last Updated:** June 2026  

**Contact Information:**
- **Development Team:** dev@esolutions.co.zw
- **Support:** support@esolutions.co.zw
- **Production URL:** https://ap.invoicedirect.biz

---
