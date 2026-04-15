# Implementation Plan: PEPPOL Customer Invitation

## Overview

Implement the tokenised PEPPOL self-registration invitation flow. The backend lives in a new
`com.esolutions.massmailer.invitation` package (model → repository → service → controller → job).
The frontend adds a public `PeppolInvitePage` and an "Invite to PEPPOL" button in `CustomersPage`.
All 18 correctness properties from the design are covered by jqwik property tests.

## Tasks

- [x] 1. Create `PeppolInvitation` entity and `InvitationRepository`
  - Create `com.esolutions.massmailer.invitation.model.PeppolInvitation` JPA entity with fields:
    `id` (UUID), `organizationId`, `customerContactId`, `customerEmail`, `token` (unique),
    `status` (enum), `expiresAt`, `createdAt`, `completedAt`
  - Create `InvitationStatus` enum: `PENDING`, `COMPLETED`, `CANCELLED`, `EXPIRED`
  - Create `com.esolutions.massmailer.invitation.repository.InvitationRepository` extending
    `JpaRepository<PeppolInvitation, UUID>` with query methods:
    `findByToken`, `findByOrganizationIdOrderByCreatedAtDesc`,
    `findByOrganizationIdAndCustomerContactIdAndStatus`,
    `findByStatusAndExpiresAtBefore`
  - _Requirements: 1.1, 8.1_

- [x] 2. Implement `InvitationService` — send and validate
  - [x] 2.1 Implement `sendInvitation(orgId, customerEmail)` in
    `com.esolutions.massmailer.invitation.service.InvitationService`
    - Verify `CustomerContact` exists for org+email; throw 404 if not
    - Verify no active `PeppolParticipantLink` exists; throw 409 if found
    - Cancel any existing `PENDING` invitation for the same org+customer
    - Generate `UUID.randomUUID().toString()` as token
    - Persist `PeppolInvitation` with `status=PENDING`, `expiresAt=createdAt+72h`
    - Send invitation email via `JavaMailSender` + Thymeleaf template
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 8.1_

  - [x] 2.2 Write property test: `InvitationServicePropertyTest#tokenCreationInvariants`
    - **Property 1: Token Creation Invariants**
    - **Validates: Requirements 1.1, 8.1**

  - [x] 2.3 Write property test: `InvitationServicePropertyTest#invitationEmailContainsToken`
    - **Property 2: Invitation Email Contains Token**
    - **Validates: Requirements 1.2**

  - [x] 2.4 Write property test: `InvitationServicePropertyTest#reInviteInvalidatesPrevious`
    - **Property 3: Re-invite Invalidates Previous**
    - **Validates: Requirements 1.3**

  - [x] 2.5 Write property test: `InvitationServicePropertyTest#missingCustomerReturns404`
    - **Property 4: Missing Customer Returns 404**
    - **Validates: Requirements 1.4**

  - [x] 2.6 Write property test: `InvitationServicePropertyTest#alreadyLinkedReturns409`
    - **Property 5: Already-Linked Customer Returns 409**
    - **Validates: Requirements 1.5**

  - [x] 2.7 Implement `validateToken(token)` in `InvitationService`
    - Return `TokenValidationResponse(customerEmail, organisationName)` for valid PENDING tokens
    - Throw 404 for unknown token
    - Throw 410 for `COMPLETED` or `CANCELLED` status
    - Throw 410 for `PENDING` token past `expiresAt`
    - Response must NOT include org API key or internal org UUID
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 8.4_

  - [x] 2.8 Write property test: `InvitationServicePropertyTest#tokenValidationStateMachine`
    - **Property 6: Token Validation State Machine**
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 6.1**

  - [x] 2.9 Write property test: `InvitationServicePropertyTest#tokenValidationResponseContainsNoSensitiveData`
    - **Property 7: Token Validation Response Contains No Sensitive Data**
    - **Validates: Requirements 2.5, 8.4**

- [x] 3. Checkpoint — ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement `InvitationService` — complete registration
  - [x] 4.1 Implement `completeRegistration(token, CompleteRegistrationRequest)` in `InvitationService`
    - Validate token is PENDING and not expired (reuse `validateToken` logic)
    - Validate `participantId` matches `{scheme}:{value}` pattern; return 400 if not
    - Validate `endpointUrl` is a valid HTTPS URL; return 400 if not
    - Within a single `@Transactional` boundary:
      - Upsert `AccessPoint` with `role=RECEIVER` (reuse existing if `participantId` already registered)
      - Create `PeppolParticipantLink` with `preferredChannel=PEPPOL`
      - Update `CustomerContact`: set `deliveryMode=AS4`, `peppolParticipantId`
      - Set `PeppolInvitation.status=COMPLETED`, `completedAt=Instant.now()`
    - After transaction commits, send completion notification email to org `senderEmail`
      (catch and log any mail exception — do NOT roll back)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 7.1, 7.2, 7.3, 8.2_

  - [x] 4.2 Write property test: `InvitationServicePropertyTest#completionInvariants`
    - **Property 8: Completion Invariants**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.8**

  - [x] 4.3 Write property test: `InvitationServicePropertyTest#existingAccessPointIsReused`
    - **Property 9: Existing AccessPoint Is Reused**
    - **Validates: Requirements 3.5**

  - [x] 4.4 Write property test: `InvitationServicePropertyTest#inputValidationRejectsBadInputs`
    - **Property 10: Input Validation Rejects Invalid Participant ID and Non-HTTPS URL**
    - **Validates: Requirements 3.6, 3.7**

  - [x] 4.5 Write property test: `InvitationServicePropertyTest#completionNotificationEmail`
    - **Property 15: Completion Notification Email**
    - **Validates: Requirements 7.1, 7.2**

  - [x] 4.6 Write property test: `InvitationServicePropertyTest#notificationFailureDoesNotRollBack`
    - **Property 16: Notification Failure Does Not Roll Back Registration**
    - **Validates: Requirements 7.3**

  - [x] 4.7 Write property test: `InvitationServicePropertyTest#tokenIsSingleUse`
    - **Property 17: Token Is Single-Use**
    - **Validates: Requirements 8.2**

- [x] 5. Implement `InvitationService` — list and cancel
  - [x] 5.1 Implement `listInvitations(orgId)` — return all invitations ordered by `createdAt` desc;
    compute virtual `EXPIRED` status for PENDING records past `expiresAt`
    - _Requirements: 5.1, 5.2, 6.2_

  - [x] 5.2 Implement `cancelInvitation(orgId, invitationId)` — set status to `CANCELLED`;
    throw 409 if not currently `PENDING`
    - _Requirements: 5.3, 5.4_

  - [x] 5.3 Write property test: `InvitationServicePropertyTest#listResponseOrderingAndCompleteness`
    - **Property 11: List Response Ordering and Completeness**
    - **Validates: Requirements 5.1, 5.2**

  - [x] 5.4 Write property test: `InvitationServicePropertyTest#cancellationStateTransition`
    - **Property 12: Cancellation State Transition**
    - **Validates: Requirements 5.3, 5.4**

  - [x] 5.5 Write property test: `InvitationServicePropertyTest#expiredStatusInListResponses`
    - **Property 13: Expired Status in List Responses**
    - **Validates: Requirements 6.2**

- [x] 6. Implement `InvitationExpiryJob`
  - Create `com.esolutions.massmailer.invitation.job.InvitationExpiryJob` with `@Scheduled`
  - Query `InvitationRepository.findByStatusAndExpiresAtBefore(PENDING, Instant.now())`
  - Bulk-update matching records to `status=EXPIRED`
  - _Requirements: 6.3_

  - [x] 6.1 Write property test: `InvitationExpiryJobPropertyTest#expiryJobTransitionsStaleInvitations`
    - **Property 14: Expiry Cleanup Job Transitions Stale Invitations**
    - **Validates: Requirements 6.3**

- [x] 7. Implement `InvitationController`
  - Create `com.esolutions.massmailer.invitation.controller.InvitationController`
  - Authenticated endpoints under `/api/v1/my/invitations` (`ROLE_ORG`):
    - `POST /api/v1/my/invitations` → `sendInvitation`; return 201
    - `GET /api/v1/my/invitations` → `listInvitations`; return 200
    - `DELETE /api/v1/my/invitations/{id}` → `cancelInvitation`; return 204
  - Public endpoints under `/api/v1/invitations/{token}`:
    - `GET /api/v1/invitations/{token}` → `validateToken`; return 200 or 404/410
    - `POST /api/v1/invitations/{token}/complete` → `completeRegistration`; return 200
  - Update `SecurityConfig` to add `/api/v1/invitations/**` to the `permitAll()` list
  - _Requirements: 1.6, 2.1–2.5, 3.1–3.8, 5.1–5.5, 8.3_

  - [x] 7.1 Write property test: `InvitationControllerPropertyTest#managementEndpointsRequireAuth`
    - **Property 18: Management Endpoints Require Authentication**
    - **Validates: Requirements 1.6, 5.5**

- [x] 8. Checkpoint — ensure all tests pass, ask the user if questions arise.

- [x] 9. Create Thymeleaf email templates
  - Create `src/main/resources/templates/email/peppol-invitation.html`
    - Variables: `#{orgName}`, `#{customerEmail}`, `#{inviteUrl}`, `#{expiresAt}`
    - `inviteUrl` must embed the token: `{baseUrl}/invite/peppol/{token}`
    - _Requirements: 1.2_
  - Create `src/main/resources/templates/email/peppol-invitation-complete.html`
    - Variables: `#{customerEmail}`, `#{participantId}`, `#{endpointUrl}`, `#{completedAt}`
    - _Requirements: 7.1, 7.2_

- [x] 10. Add frontend types and API client functions
  - Add to `frontend/src/types.ts`:
    - `InvitationStatus` type: `'PENDING' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED'`
    - `PeppolInvitation` interface: `id`, `customerEmail`, `status`, `createdAt`, `expiresAt`, `completedAt`
    - `TokenValidationResponse` interface: `customerEmail`, `organisationName`
  - Add to `frontend/src/api/client.ts`:
    - `sendPeppolInvitation(apiKey, customerEmail)` → `POST /api/v1/my/invitations`
    - `listInvitations(apiKey)` → `GET /api/v1/my/invitations`
    - `cancelInvitation(apiKey, id)` → `DELETE /api/v1/my/invitations/{id}`
    - `validateInvitationToken(token)` → `GET /api/v1/invitations/{token}`
    - `completeInvitation(token, data)` → `POST /api/v1/invitations/{token}/complete`
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 11. Implement `PeppolInvitePage` (public self-registration page)
  - Create `frontend/src/pages/PeppolInvitePage.tsx`, route `/invite/peppol/:token`
  - On mount: call `validateInvitationToken(token)`
    - Loading state while request is in flight
    - Error state (invalid/expired/used) if response is 4xx
    - Form state when token is valid: show `organisationName` and `customerEmail` read-only
  - Form fields: `participantId`, `endpointUrl`, optional `deliveryAuthToken`,
    `simplifiedHttpDelivery` toggle (default true)
  - On submit: call `completeInvitation(token, formData)`
    - Success state: show registered `participantId` and `endpointUrl`
    - Inline error on 400 without clearing form
  - Register route in `App.tsx` outside `ProtectedRoute` (no auth requireclkear
  
  d)
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 12. Add "Invite to PEPPOL" button in `CustomersPage`
  - Add `inviteCustomer` action to each customer row where `peppolParticipantId` is absent
  - On click: call `sendPeppolInvitation(apiKey, customer.email)`
  - Show inline success/error feedback per row (no full-page reload)
  - _Requirements: 1.1, 1.4, 1.5_

- [x] 13. Final checkpoint — ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Each property test must use `@Property(tries = 200)` and include a comment referencing the property number
- The `completeRegistration` transaction must commit before the notification email is attempted
- The `InvitationExpiryJob` requires `@EnableScheduling` on the application or a config class
- The public `/api/v1/invitations/**` permit must be added before the catch-all `/api/**` rule in `SecurityConfig`
