# Requirements Document

## Introduction

The fiscalised-invoice-email feature delivers ZIMRA-fiscalised PDF invoices to customers
via email (and optionally via PEPPOL AS4). It is the primary delivery channel of the
InvoiceDirect platform. The system accepts invoices from ERP/POS systems through three
entry points — mass campaign upload, ERP-pull dispatch, and single real-time dispatch —
validates each PDF against ZIMRA fiscal markers, renders a Thymeleaf HTML email body,
attaches the PDF, and sends via Gmail SMTP with rate limiting and retry. Every delivery
attempt is metered for billing. Delivery mode (EMAIL, AS4, or BOTH) is resolved
per-customer with a customer-level override taking precedence over the organisation default.

## Glossary

- **System**: The InvoiceDirect backend application as a whole.
- **CampaignOrchestrator**: The service that orchestrates asynchronous batch dispatch of invoice emails.
- **ErpCampaignController**: The REST controller that accepts multi-invoice dispatch requests from ERP systems.
- **SingleMailController**: The REST controller that accepts single real-time invoice dispatch requests.
- **PdfAttachmentResolver**: The service that resolves a PDF from a filesystem path or Base64 string and validates its magic bytes.
- **ZimraFiscalValidator**: The service that validates ZIMRA fiscal markers within a PDF content stream.
- **TemplateRenderService**: The service that renders Thymeleaf HTML email templates with merged variables.
- **SmtpSendService**: The service that composes and sends MIME multipart emails via JavaMailSender.
- **CustomerContactService**: The service that upserts customer contact records from canonical invoice data.
- **MeteringService**: The service that records a UsageRecord for every invoice delivery attempt.
- **DeliveryModeRouter**: The service that resolves the effective delivery mode for a recipient.
- **MailCampaign**: A persistent entity representing a batch of invoice emails to be dispatched.
- **MailRecipient**: A persistent entity representing one invoice email within a campaign.
- **CustomerContact**: A persistent entity representing a customer's contact and delivery preferences.
- **UsageRecord**: A persistent entity recording a single delivery attempt for billing purposes.
- **CanonicalInvoice**: An ACL boundary record carrying all invoice data from an ERP source.
- **DeliveryMode**: An enum with values EMAIL, AS4, BOTH — controls how an invoice is delivered.
- **CampaignStatus**: An enum: CREATED, QUEUED, IN_PROGRESS, COMPLETED, PARTIALLY_FAILED, FAILED, CANCELLED.
- **RecipientStatus**: An enum: PENDING, SENT, FAILED, SKIPPED, BOUNCED, UNSUBSCRIBED.
- **DeliveryOutcome**: An enum: DELIVERED, FAILED, SKIPPED — recorded on UsageRecord.
- **ZIMRA**: Zimbabwe Revenue Authority — the fiscal authority whose FDMS markers must appear in fiscalised PDFs.
- **FDMS**: Fiscal Device Management System — the ZIMRA domain (`fdms.zimra.co.zw`) embedded in fiscalised PDFs.
- **fiscalValidationEnabled**: A boolean configuration property (`massmailer.fiscal-validation-enabled`) that gates fiscal validation.
- **batchSize**: A configuration property (`massmailer.batch-size`, default 5000) controlling recipient batch partitioning.
- **maxRetries**: The maximum number of SMTP retry attempts (3), with exponential backoff of 2 s, 4 s, 8 s.

## Requirements

### Requirement 1: Mass Campaign Dispatch via PDF Upload

**User Story:** As an ERP system operator, I want to submit a batch of fiscalised invoices
with their PDF attachments in a single multipart request, so that all customers receive
their invoices in one operation without manual intervention.

#### Acceptance Criteria

1. WHEN a `POST /api/v1/erp/dispatch/upload` request is received with a valid metadata JSON
   and all PDF multipart parts present, THE ErpCampaignController SHALL upsert all customer
   contacts, create a MailCampaign, trigger asynchronous dispatch, and return `202 Accepted`
   with `emailCampaignId` and `totalInvoices`.
2. WHEN a `POST /api/v1/erp/dispatch/upload` request is received and one or more invoice
   numbers in the metadata have no matching multipart file part, THE ErpCampaignController
   SHALL return `400 Bad Request` listing the missing invoice numbers and SHALL NOT perform
   any database writes.
3. WHEN a `POST /api/v1/erp/dispatch` request is received with a valid ERP pull request,
   THE ErpCampaignController SHALL fetch invoices from the ERP integration port, upsert
   customer contacts, create a MailCampaign, trigger asynchronous dispatch, and return
   `202 Accepted`.
4. WHEN a `POST /api/v1/campaigns` request is received with a valid campaign request body,
   THE CampaignOrchestrator SHALL persist the MailCampaign and all MailRecipient records
   and return the created campaign.

---

### Requirement 2: Single Real-Time Invoice Dispatch

**User Story:** As an ERP or POS system, I want to dispatch a single fiscalised invoice
immediately and receive a synchronous delivery result, so that I can confirm delivery
in real time.

#### Acceptance Criteria

1. WHEN a `POST /api/v1/mail/invoice` request is received with valid invoice metadata and
   a PDF source, THE SingleMailController SHALL resolve the PDF, validate fiscal markers,
   render the email template, send via SMTP, and return `200 OK` with delivery status,
   recipient, invoiceNumber, and messageId.
2. WHEN fiscal validation fails on a single-invoice request and `fiscalValidationEnabled`
   is `true`, THE SingleMailController SHALL return `400 Bad Request` with the list of
   missing fiscal marker errors.
3. IF the SMTP send fails after all retries on a single-invoice request, THEN THE
   SingleMailController SHALL return `502 Bad Gateway` with an error message and a
   `retryable` flag.

---

### Requirement 3: Campaign Lifecycle and Status Management

**User Story:** As an ERP operator, I want to track the progress and final status of a
dispatched campaign, so that I know how many invoices were delivered, failed, or skipped.

#### Acceptance Criteria

1. THE CampaignOrchestrator SHALL persist a MailCampaign with `status = QUEUED` and
   `totalRecipients` equal to the number of recipients in the request when a campaign
   is created.
2. WHEN campaign dispatch begins, THE CampaignOrchestrator SHALL transition the campaign
   status to `IN_PROGRESS`.
3. WHEN all recipients have reached a terminal status, THE CampaignOrchestrator SHALL
   transition the campaign status to `COMPLETED` if `failedCount == 0`, or to
   `PARTIALLY_FAILED` if `failedCount > 0`.
4. THE CampaignOrchestrator SHALL maintain the invariant that
   `sentCount + failedCount + skippedCount == totalRecipients` at all times after dispatch
   completes.
5. WHEN a `GET /api/v1/campaigns/{id}` request is received for an existing campaign,
   THE System SHALL return the campaign status, recipient counts, and timestamps.
6. IF a `GET /api/v1/campaigns/{id}` or `POST /api/v1/campaigns/{id}/retry` request is
   received with an unknown campaign ID, THEN THE System SHALL return `404 Not Found`.

---

### Requirement 4: Asynchronous Batch Dispatch with Structured Concurrency

**User Story:** As a platform operator, I want invoice dispatch to run asynchronously
using virtual threads so that large campaigns do not block the API thread and complete
efficiently.

#### Acceptance Criteria

1. THE CampaignOrchestrator SHALL execute `dispatchCampaign()` asynchronously on a
   dedicated `@Async` executor thread pool, returning immediately to the caller.
2. THE CampaignOrchestrator SHALL partition recipients into batches of at most `batchSize`
   recipients, where `batchSize` is configurable via `massmailer.batch-size`.
3. WITHIN each batch, THE CampaignOrchestrator SHALL fork one virtual thread per recipient
   using `StructuredTaskScope` and join all threads before processing results.
4. THE CampaignOrchestrator SHALL pause for 1000 ms between batches to provide SMTP relay
   breathing room.
5. WHEN all recipients in a batch have been processed, THE CampaignOrchestrator SHALL
   persist all recipient status updates and the updated campaign counters before starting
   the next batch.

---

### Requirement 5: PDF Attachment Resolution

**User Story:** As the dispatch pipeline, I want to resolve a PDF from either a filesystem
path or a Base64 string and validate it is a genuine PDF, so that only valid PDF files
are attached to invoice emails.

#### Acceptance Criteria

1. WHEN `PdfAttachmentResolver.resolve()` is called with a non-blank `filePath`, THE
   PdfAttachmentResolver SHALL read the file from disk and return a `ResolvedAttachment`
   with the file bytes, `contentType = "application/pdf"`, and `sizeBytes > 0`.
2. WHEN `PdfAttachmentResolver.resolve()` is called with a non-blank `base64` string,
   THE PdfAttachmentResolver SHALL decode the Base64 bytes and return a `ResolvedAttachment`.
3. THE PdfAttachmentResolver SHALL validate that the resolved bytes begin with the magic
   bytes `%PDF-` (0x25 0x50 0x44 0x46 0x2D) before returning a `ResolvedAttachment`.
4. IF the resolved bytes do not begin with `%PDF-` magic bytes, THEN THE
   PdfAttachmentResolver SHALL throw a `PdfResolutionException` with a descriptive message.
5. IF both `filePath` and `base64` are null or blank, THEN THE PdfAttachmentResolver SHALL
   throw a `PdfResolutionException` indicating no PDF source was provided.
6. WHEN a recipient has no PDF attachment (both `pdfFilePath` and `pdfBase64` are absent),
   THE CampaignOrchestrator SHALL mark that recipient as `SKIPPED` without making any SMTP
   connection.

---

### Requirement 6: ZIMRA Fiscal Validation

**User Story:** As a compliance officer, I want every PDF invoice to be validated for
ZIMRA fiscal markers before dispatch, so that only properly fiscalised invoices are
delivered to customers.

#### Acceptance Criteria

1. WHEN `ZimraFiscalValidator.validate()` is called, THE ZimraFiscalValidator SHALL scan
   the PDF content stream for the FDMS domain string `fdms.zimra.co.zw` (Rule 1), a
   verification code label or 16-character hexadecimal pattern (Rule 2), and at least one
   fiscal device field label (Rule 3).
2. THE ZimraFiscalValidator SHALL return `ValidationResult` with `valid = true` if and
   only if all three rules pass.
3. IF any rule fails, THEN THE ZimraFiscalValidator SHALL return `ValidationResult` with
   `valid = false` and `errors` containing a descriptive message for each failing rule.
4. WHILE `fiscalValidationEnabled` is `true`, THE CampaignOrchestrator SHALL reject any
   recipient whose PDF fails fiscal validation by marking that recipient as `FAILED` with
   the validation error messages.
5. WHILE `fiscalValidationEnabled` is `false`, THE CampaignOrchestrator SHALL skip fiscal
   validation and proceed to template rendering for all recipients.
6. THE ZimraFiscalValidator SHALL NOT mutate the `pdfBytes` input and SHALL NOT produce
   any I/O side effects.

---

### Requirement 7: Email Template Rendering

**User Story:** As a campaign creator, I want invoice emails to be rendered from a
Thymeleaf HTML template with per-recipient merge fields, so that each customer receives
a personalised email body.

#### Acceptance Criteria

1. WHEN `TemplateRenderService.render()` is called, THE TemplateRenderService SHALL load
   the template from `classpath:templates/email/{templateName}.html` and return a rendered
   HTML string.
2. THE TemplateRenderService SHALL merge campaign-level variables with per-recipient merge
   fields, where per-recipient fields take precedence over campaign-level fields when keys
   conflict.
3. THE TemplateRenderService SHALL include the following invoice fields in the merge
   context: `recipientName`, `invoiceNumber`, `invoiceDate`, `dueDate`, `totalAmount`,
   `vatAmount`, `currency`, `currencySymbol`, `fiscalDeviceSerialNumber`, `fiscalDayNumber`,
   `globalInvoiceCounter`, `verificationCode`, and `qrCodeUrl`.
4. IF the specified `templateName` does not correspond to an existing template file, THEN
   THE TemplateRenderService SHALL throw a template resolution exception, causing the
   recipient to be marked `FAILED`.

---

### Requirement 8: SMTP Dispatch with Rate Limiting and Retry

**User Story:** As a platform operator, I want invoice emails to be sent reliably via
SMTP with rate limiting and automatic retry on transient failures, so that temporary
network or server issues do not cause permanent delivery failures.

#### Acceptance Criteria

1. WHEN `SmtpSendService.send()` is called, THE SmtpSendService SHALL compose a MIME
   multipart message with the HTML body and PDF attachment and submit it via
   `JavaMailSender`.
2. THE SmtpSendService SHALL set the `X-Invoice-Number` header to the invoice number on
   every outbound MIME message.
3. THE SmtpSendService SHALL set the `X-Auto-Response-Suppress: All`,
   `Auto-Submitted: auto-generated`, and `Precedence: bulk` headers on every outbound
   MIME message.
4. THE SmtpSendService SHALL acquire a `Semaphore` permit before each SMTP send and
   release it in a `finally` block, where the semaphore capacity is configurable via
   `massmailer.rate-limit`.
5. WHEN a `MessagingException` is thrown during send, THE SmtpSendService SHALL retry
   up to `maxRetries` (3) times with exponential backoff delays of 2 s, 4 s, and 8 s.
6. IF all retry attempts are exhausted due to transient SMTP errors, THEN THE
   SmtpSendService SHALL return `Failed` with `retryable = true`.
7. IF the SMTP server returns a permanent rejection (e.g. 550 invalid address), THEN THE
   SmtpSendService SHALL return `Failed` with `retryable = false` without further retries.
8. WHEN `SmtpSendService.send()` succeeds, THE SmtpSendService SHALL return `Delivered`
   with a non-null `messageId`.

---

### Requirement 9: Customer Contact Registry

**User Story:** As a platform operator, I want customer contact records to be created or
updated automatically from invoice data before dispatch, so that the customer registry
always reflects the latest contact information.

#### Acceptance Criteria

1. WHEN `CustomerContactService.upsertAll()` is called, THE CustomerContactService SHALL
   create a new `CustomerContact` for each `(organizationId, email)` pair that does not
   already exist in the registry.
2. WHEN `CustomerContactService.upsertAll()` is called for an existing
   `(organizationId, email)` pair, THE CustomerContactService SHALL update the `name`,
   `companyName`, and `erpCustomerId` fields if the new values are non-blank.
3. THE CustomerContactService SHALL normalise all email addresses to lowercase before
   lookup and storage.
4. THE CustomerContactService SHALL enforce the unique constraint on
   `(organizationId, email)` such that no duplicate `CustomerContact` records are created
   regardless of how many times `upsertAll()` is called with the same data.
5. IF any invoice in the batch has a recipient whose `CustomerContact` has
   `unsubscribed = true`, THEN THE CustomerContactService SHALL throw an
   `IllegalArgumentException` and the entire dispatch request SHALL be rejected with
   `400 Bad Request` before any emails are sent.

---

### Requirement 10: Delivery Metering

**User Story:** As a billing administrator, I want every invoice delivery attempt to be
recorded as a usage record, so that I can accurately bill organisations for delivered
and failed invoices.

#### Acceptance Criteria

1. WHEN a delivery attempt completes for any recipient, THE MeteringService SHALL create
   exactly one `UsageRecord` containing the `organizationId`, `campaignId`,
   `recipientEmail`, `invoiceNumber`, `outcome`, `pdfSizeBytes`, and `channel`.
2. THE MeteringService SHALL set `billingPeriod` to the current UTC timestamp formatted
   as `YYYY-MM`.
3. THE MeteringService SHALL set `billed = false` on all newly created `UsageRecord`
   entries.
4. THE MeteringService SHALL set `outcome = DELIVERED` when the SMTP send succeeds,
   `outcome = FAILED` when the send fails after retries, and `outcome = SKIPPED` when
   no SMTP attempt was made.
5. THE System SHALL consider a `UsageRecord` billable if and only if
   `outcome ∈ {DELIVERED, FAILED}`.
6. THE System SHALL NOT mark `UsageRecord` entries with `outcome = SKIPPED` as billable.

---

### Requirement 11: Delivery Mode Routing

**User Story:** As an organisation administrator, I want invoice delivery to be routed
to email, PEPPOL AS4, or both channels based on per-customer or organisation-level
configuration, so that each customer receives invoices through their preferred channel.

#### Acceptance Criteria

1. WHEN `DeliveryModeRouter.resolveDeliveryMode()` is called, THE DeliveryModeRouter
   SHALL return the `CustomerContact.deliveryMode` value if it is non-null (customer-level
   override).
2. WHEN a customer has no delivery mode override (`CustomerContact.deliveryMode` is null),
   THE DeliveryModeRouter SHALL return the organisation's configured `deliveryMode`.
3. WHEN neither the customer nor the organisation has a delivery mode configured, THE
   DeliveryModeRouter SHALL default to `EMAIL`.
4. WHEN the effective delivery mode is `EMAIL` or `BOTH`, THE CampaignOrchestrator SHALL
   route the invoice through the SMTP email dispatch path.
5. WHEN the effective delivery mode is `AS4` or `BOTH`, THE CampaignOrchestrator SHALL
   route the invoice through the PEPPOL AS4 delivery path.
6. WHEN the effective delivery mode is `AS4` only, THE CampaignOrchestrator SHALL NOT
   make any SMTP connection for that recipient.

---

### Requirement 12: Campaign Retry

**User Story:** As an ERP operator, I want to retry failed recipients in a campaign
without re-sending to already-delivered recipients, so that transient failures are
resolved without duplicate deliveries.

#### Acceptance Criteria

1. WHEN `POST /api/v1/campaigns/{id}/retry` is called, THE CampaignOrchestrator SHALL
   re-dispatch only recipients with `status = FAILED` and `retryCount < maxRetries`.
2. THE CampaignOrchestrator SHALL increment `retryCount` on each retry attempt for a
   recipient.
3. THE CampaignOrchestrator SHALL NOT re-dispatch recipients with `status = SENT` or
   `status = SKIPPED`.
4. THE CampaignOrchestrator SHALL NOT re-dispatch recipients whose `retryCount` has
   reached `maxRetries`.

---

### Requirement 13: Currency Symbol Mapping

**User Story:** As a customer receiving an invoice email, I want to see the correct
currency symbol in my invoice, so that the amount is clearly presented in the right
currency.

#### Acceptance Criteria

1. THE System SHALL support the following ISO 4217 currency codes: USD, ZWG, ZAR, GBP,
   EUR, CNY, BWP.
2. THE System SHALL map each supported currency code to a non-null display symbol via
   `ZimbabweCurrency.symbolFor()`.
3. WHEN building invoice merge fields for template rendering, THE CampaignOrchestrator
   SHALL include a `currencySymbol` field derived from the recipient's `currency` value.

---

### Requirement 14: API Authentication

**User Story:** As a security administrator, I want all dispatch endpoints to require
a valid API key scoped to an organisation, so that only authorised ERP systems can
submit invoices.

#### Acceptance Criteria

1. THE System SHALL require an `X-API-Key` header on all requests to
   `/api/v1/erp/dispatch/upload`, `/api/v1/erp/dispatch`, `/api/v1/mail/invoice`,
   `/api/v1/campaigns`, and `/api/v1/campaigns/{id}/retry`.
2. IF a request is received without a valid `X-API-Key` header, THEN THE System SHALL
   return `401 Unauthorized` and SHALL NOT process the request.
3. THE System SHALL scope each API key to a single organisation such that an API key
   cannot access or modify data belonging to a different organisation.

---

### Requirement 15: Error Handling and Observability

**User Story:** As a platform operator, I want all error conditions to produce structured
error responses and traceable log entries, so that I can diagnose and resolve issues
quickly.

#### Acceptance Criteria

1. WHEN a recipient's PDF fails magic byte validation during campaign dispatch, THE
   CampaignOrchestrator SHALL mark that recipient as `FAILED` with a descriptive error
   message and SHALL continue processing remaining recipients.
2. WHEN a recipient's PDF fails fiscal validation during campaign dispatch, THE
   CampaignOrchestrator SHALL mark that recipient as `FAILED` with the list of missing
   fiscal marker errors and SHALL continue processing remaining recipients.
3. WHEN a template is not found during dispatch, THE CampaignOrchestrator SHALL mark
   the affected recipient as `FAILED` with a template resolution error message.
4. WHEN an unsubscribed recipient is detected during `upsertAll()`, THE System SHALL
   return `400 Bad Request` with the unsubscribed recipient's email address before any
   emails are sent.
5. THE System SHALL set `X-Invoice-Number` on every outbound SMTP message to enable
   fiscal traceability in mail server logs.
6. THE SmtpSendService SHALL always release the rate-limiter `Semaphore` permit in a
   `finally` block, regardless of whether the send succeeds or fails.
