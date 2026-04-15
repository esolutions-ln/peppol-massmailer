# Implementation Plan: Fiscalised Invoice Email

## Overview

The core dispatch pipeline (CampaignOrchestrator, SmtpSendService, PdfAttachmentResolver,
ZimraFiscalValidator, TemplateRenderService) and all three REST controllers are already
implemented. This plan fills the remaining gaps: the DeliveryModeRouter service, wiring
fiscal validation into the orchestrator, enforcing the unsubscribe check in
CustomerContactService, and the full suite of property-based tests for all 12 correctness
properties.

## Tasks

- [x] 1. Create DeliveryModeRouter service
  - Create `src/main/java/com/esolutions/massmailer/service/DeliveryModeRouter.java`
  - Inject `OrganizationRepository` and `CustomerContactRepository`
  - Implement `resolveDeliveryMode(UUID organizationId, String recipientEmail) → DeliveryMode`
  - Logic: customer-level `CustomerContact.deliveryMode` override → org default → `EMAIL` fallback
  - _Requirements: 11.1, 11.2, 11.3_

- [x] 2. Wire fiscal validation gate into CampaignOrchestrator
  - Inject `ZimraFiscalValidator` and `MailerProperties` into `CampaignOrchestrator`
  - In `sendInvoiceEmail()`, after PDF resolution (step 2), add fiscal validation step:
    - If `fiscalValidationEnabled = true`, call `fiscalValidator.validate(pdf.bytes, invoiceNumber)`
    - If `result.valid() == false`, return `DeliveryResult.Failed` with the error list
    - If `fiscalValidationEnabled = false`, skip validation entirely
  - Note: `PdfAttachmentResolver` currently also validates — the orchestrator gate is the
    authoritative one for campaign dispatch; the resolver's gate handles single-invoice path
  - _Requirements: 6.4, 6.5_

- [x] 3. Enforce unsubscribe check in CustomerContactService.upsertAll()
  - In `CustomerContactService.upsertAll()`, after the missing-email validation block,
    add a second pass: query existing contacts for each email and check `unsubscribed == true`
  - If any contact is unsubscribed, throw `IllegalArgumentException` with the email address
  - The check must run before any DB writes (fail-fast, atomically)
  - _Requirements: 9.5, 15.4_

- [x] 4. Verify ZimbabweCurrency symbol mapping completeness
  - Confirm `ZimbabweCurrency` enum contains all 7 required codes: USD, ZWG, ZAR, GBP, EUR, CNY, BWP
  - Confirm `symbolFor()` returns a non-null, non-empty string for each code
  - Confirm `symbolFor(null)` and `symbolFor("")` return `""` without throwing
  - No code changes expected — this is a verification task; add a comment if any code is missing
  - _Requirements: 13.1, 13.2_

- [x] 5. Property-based tests — PDF and fiscal validation (P6, P7)
  - Create `src/test/java/com/esolutions/massmailer/service/PdfAttachmentResolverPropertyTest.java`
  - Use jqwik `@Property` / `@ForAll` with `@AddLifecycleHook(JqwikSpringExtension.class)`

  - [x] 5.1 Write property test P6 — PDF magic bytes invariant
    - **Property P6: PDF Magic Bytes**
    - Generate valid PDF byte arrays (prepend `%PDF-` to arbitrary bytes)
    - Assert `resolveFromBytes(bytes, "test.pdf").source()` first 5 bytes == `{0x25,0x50,0x44,0x46,0x2D}`
    - Also assert that bytes NOT starting with `%PDF-` throw `PdfResolutionException`
    - **Validates: Requirements 5.3, 5.4**

  - [x] 5.2 Write property test P7 — Fiscal validation gate
    - **Property P7: Fiscal Validation Gate**
    - Create `ZimraFiscalValidatorPropertyTest.java`
    - Generate PDF content strings that contain all three ZIMRA markers → assert `valid == true`
    - Generate PDF content strings missing each marker individually → assert `valid == false` with non-empty errors
    - Assert `result.errors().isEmpty() == result.valid()` for all inputs
    - **Validates: Requirements 6.1, 6.2, 6.3**

- [x] 6. Property-based tests — UsageRecord billing outcomes (P4, P5)
  - Create `src/test/java/com/esolutions/massmailer/billing/UsageRecordPropertyTest.java`

  - [x] 6.1 Write property test P5 — Billable outcomes
    - **Property P5: Billable Outcomes**
    - For all `DeliveryOutcome` values, assert `record.isBillable() ⟺ outcome ∈ {DELIVERED, FAILED}`
    - Assert `SKIPPED` is never billable
    - **Validates: Requirements 10.5, 10.6**

  - [x] 6.2 Write property test P4 — Metering coverage
    - **Property P4: Metering Coverage**
    - For each `DeliveryResult` variant (Delivered, Failed, Skipped), call `MeteringService.recordDelivery()`
    - Assert exactly one `UsageRecord` is persisted with matching `campaignId`, `recipientEmail`, `invoiceNumber`
    - Assert `billingPeriod` matches current UTC `YYYY-MM`
    - Assert `billed == false` on all new records
    - **Validates: Requirements 10.1, 10.2, 10.3, 10.4**

- [x] 7. Property-based tests — Customer contact registry (P8, P9)
  - Create `src/test/java/com/esolutions/massmailer/customer/CustomerContactServicePropertyTest.java`

  - [x] 7.1 Write property test P8 — Customer registry completeness and idempotency
    - **Property P8: Customer Registry Completeness**
    - Generate lists of `CanonicalInvoice` with distinct emails, call `upsertAll()` twice
    - Assert exactly one `CustomerContact` per `(organizationId, email)` pair after both calls
    - Assert all emails are stored lowercase
    - **Validates: Requirements 9.1, 9.3, 9.4**

  - [x] 7.2 Write property test P9 — Unsubscribe enforcement
    - **Property P9: Unsubscribe Enforcement**
    - Pre-create a `CustomerContact` with `unsubscribed = true`
    - Generate a `CanonicalInvoice` for that email
    - Assert `upsertAll()` throws `IllegalArgumentException` before any email is sent
    - **Validates: Requirements 9.5**

- [x] 8. Property-based tests — Delivery mode routing (P10)
  - Create `src/test/java/com/esolutions/massmailer/service/DeliveryModeRouterPropertyTest.java`

  - [x] 8.1 Write property test P10 — Delivery mode routing precedence
    - **Property P10: Delivery Mode Routing**
    - For all combinations of (customer override, org default), assert customer override wins when non-null
    - Assert result defaults to `EMAIL` when both are null
    - Assert no recipient with effective mode `AS4` is routed to the email channel
    - **Validates: Requirements 11.1, 11.2, 11.3**

- [x] 9. Property-based tests — Campaign lifecycle invariants (P1, P2, P3)
  - Create `src/test/java/com/esolutions/massmailer/service/CampaignOrchestratorPropertyTest.java`

  - [x] 9.1 Write property test P1 — Campaign completeness counter invariant
    - **Property P1: Campaign Completeness**
    - After `createCampaign()`, assert `totalRecipients == request.recipients().size()`
    - After simulated dispatch (mock SMTP), assert `sentCount + failedCount + skippedCount == totalRecipients`
    - **Validates: Requirements 3.1, 3.4**

  - [x] 9.2 Write property test P2 — Recipient terminal status after completion
    - **Property P2: Recipient Terminal Status**
    - After campaign reaches `COMPLETED` or `PARTIALLY_FAILED`, assert all recipients have status
      in `{SENT, FAILED, SKIPPED}` — none remain `PENDING`
    - **Validates: Requirements 3.3**

  - [x] 9.3 Write property test P3 — Delivery correlation for SENT recipients
    - **Property P3: Delivery Correlation**
    - For every recipient with `status = SENT`, assert `messageId != null`, `sentAt != null`,
      and `attachmentSizeBytes >= 0`
    - **Validates: Requirements 8.8**

- [x] 10. Property-based tests — Retry idempotency and currency mapping (P11, P12)

  - [x] 10.1 Write property test P11 — Retry idempotency
    - Create `src/test/java/com/esolutions/massmailer/service/CampaignRetryPropertyTest.java`
    - **Property P11: Retry Idempotency**
    - Generate recipients with `status = FAILED` and varying `retryCount` values
    - Assert only recipients with `retryCount < maxRetries` are eligible for retry
    - Assert recipients with `status = SENT` or `status = SKIPPED` are never re-dispatched
    - **Validates: Requirements 12.1, 12.2, 12.3, 12.4**

  - [x] 10.2 Write property test P12 — Currency symbol mapping
    - Create `src/test/java/com/esolutions/massmailer/domain/ZimbabweCurrencyPropertyTest.java`
    - **Property P12: Currency Symbol Mapping**
    - For each of the 7 supported ISO codes (USD, ZWG, ZAR, GBP, EUR, CNY, BWP),
      assert `ZimbabweCurrency.symbolFor(code) != null` and non-empty
    - Assert `symbolFor(null)` and `symbolFor("")` return `""` without throwing
    - Assert `symbolFor("UNKNOWN")` returns the code itself (graceful fallback)
    - **Validates: Requirements 13.1, 13.2, 13.3**

- [x] 11. Checkpoint — Ensure all tests pass
  - Run `mvn test --enable-preview` and confirm all property tests pass
  - Verify `DeliveryModeRouter` is correctly injected wherever delivery mode resolution is needed
  - Verify fiscal validation gate in `CampaignOrchestrator` produces `FAILED` recipients (not exceptions)
  - Verify unsubscribe check in `CustomerContactService` returns `400` before any DB writes
  - Ask the user if any questions arise before proceeding.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- All property tests use jqwik (already in `pom.xml` as `net.jqwik:jqwik:1.9.2`)
- The `--enable-preview` flag is required for Java 25 sealed interfaces and `StructuredTaskScope`
- `ddl-auto: update` means no Flyway migrations are needed — JPA creates `mail_campaigns` and
  `mail_recipients` tables automatically from the existing `@Entity` classes
- `ZimraFiscalValidator` and `PdfAttachmentResolver` are already fully implemented — tasks 5.1
  and 5.2 add test coverage only
- `CampaignOrchestrator`, `SmtpSendService`, `TemplateRenderService`, `CustomerContactService`,
  `MeteringService`, `ErpCampaignController`, `CampaignController`, and `SingleMailController`
  are already implemented — tasks 2 and 3 add the missing wiring
