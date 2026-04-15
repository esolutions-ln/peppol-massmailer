# Requirements Document

## Introduction

This feature enables C1 suppliers registered on InvoiceDirect to invite their customers to self-register onto the PEPPOL network. Currently, linking a customer to PEPPOL requires manual admin intervention to register the customer's Access Point and create a Participant Link. This feature automates that process: the supplier triggers an email invitation, the customer clicks a link, fills in their PEPPOL endpoint details, and the system automatically creates the AccessPoint and PeppolParticipantLink — enabling direct ERP-to-ERP invoice delivery without admin involvement.

## Glossary

- **InvoiceDirect**: The invoice delivery platform on which this feature is built.
- **C1**: The supplier organisation registered on InvoiceDirect that sends invoices.
- **C3**: The buyer's PEPPOL Access Point (their ERP endpoint that receives invoices).
- **CustomerContact**: The buyer entity in InvoiceDirect, identified by email address, scoped to a C1 organisation.
- **AccessPoint**: A PEPPOL endpoint record in the eRegistry, storing participantId, endpointUrl, role, and delivery configuration.
- **PeppolParticipantLink**: A routing record that maps a CustomerContact to their AccessPoint and preferred delivery channel (PEPPOL or EMAIL).
- **Invitation**: A time-limited, tokenised email sent by a C1 to a CustomerContact, containing a unique self-registration link.
- **Invitation_Token**: A cryptographically random, single-use UUID token embedded in the invitation link, used to authenticate the self-registration request.
- **Self_Registration_Page**: The public-facing frontend page where a customer completes their PEPPOL endpoint details after clicking an invitation link.
- **eRegistry**: The local PEPPOL Service Metadata Publisher equivalent, storing AccessPoints and PeppolParticipantLinks.
- **Invitation_Service**: The backend service responsible for creating, sending, validating, and completing invitations.
- **Participant_ID**: A PEPPOL participant identifier in the format `{scheme}:{value}`, e.g. `0190:ZW123456789`.

---

## Requirements

### Requirement 1: Send PEPPOL Invitation

**User Story:** As a C1 supplier, I want to send a PEPPOL self-registration invitation to a customer by email, so that I can onboard them to PEPPOL delivery without requiring admin assistance.

#### Acceptance Criteria

1. WHEN a C1 supplier submits a valid invitation request for a CustomerContact email, THE Invitation_Service SHALL create an Invitation record with a unique Invitation_Token, a status of `PENDING`, and an expiry of 72 hours from creation.
2. WHEN an Invitation record is created, THE Invitation_Service SHALL send an invitation email to the CustomerContact's email address containing a link that embeds the Invitation_Token.
3. IF a PENDING invitation already exists for the same organisation and CustomerContact email, THEN THE Invitation_Service SHALL invalidate the existing invitation and issue a new one.
4. IF the CustomerContact email does not exist in the organisation's customer registry, THEN THE Invitation_Service SHALL return a 404 error with a descriptive message.
5. IF the CustomerContact already has an active PeppolParticipantLink for the requesting organisation, THEN THE Invitation_Service SHALL return a 409 error indicating the customer is already linked to PEPPOL.
6. THE Invitation_Service SHALL require a valid C1 organisation API key to accept an invitation request.

---

### Requirement 2: Invitation Token Validation

**User Story:** As a customer who received an invitation email, I want the registration link to be validated before I see the form, so that I cannot use expired or invalid links.

#### Acceptance Criteria

1. WHEN a customer navigates to the Self_Registration_Page with an Invitation_Token, THE Invitation_Service SHALL validate that the token exists, has status `PENDING`, and has not passed its expiry timestamp.
2. IF the Invitation_Token does not exist in the system, THEN THE Invitation_Service SHALL return a 404 error.
3. IF the Invitation_Token has status `COMPLETED` or `CANCELLED`, THEN THE Invitation_Service SHALL return a 410 (Gone) error indicating the link has already been used.
4. IF the Invitation_Token has passed its expiry timestamp, THEN THE Invitation_Service SHALL return a 410 (Gone) error indicating the link has expired.
5. WHEN a valid token is resolved, THE Invitation_Service SHALL return the CustomerContact's email address and the inviting organisation's name so the Self_Registration_Page can pre-populate context for the customer.

---

### Requirement 3: Customer Self-Registration

**User Story:** As a customer, I want to submit my PEPPOL endpoint details through the invitation link, so that I can be automatically connected to receive invoices directly into my ERP.

#### Acceptance Criteria

1. WHEN a customer submits valid PEPPOL endpoint details via a PENDING Invitation_Token, THE Invitation_Service SHALL create an AccessPoint record in the eRegistry with role `RECEIVER`.
2. WHEN the AccessPoint is created, THE Invitation_Service SHALL create a PeppolParticipantLink mapping the CustomerContact to the new AccessPoint with `preferredChannel` set to `PEPPOL`.
3. WHEN the PeppolParticipantLink is created, THE Invitation_Service SHALL update the Invitation record status to `COMPLETED` and record the completion timestamp.
4. WHEN the PeppolParticipantLink is created, THE Invitation_Service SHALL update the CustomerContact's `deliveryMode` to `AS4` and set the `peppolParticipantId` field to the submitted Participant_ID.
5. IF an AccessPoint with the submitted Participant_ID already exists in the eRegistry, THEN THE Invitation_Service SHALL reuse the existing AccessPoint rather than creating a duplicate, and proceed to create the PeppolParticipantLink.
6. IF the submitted Participant_ID does not match the format `{scheme}:{value}` where scheme is a non-empty string and value is a non-empty string, THEN THE Invitation_Service SHALL return a 400 error with a descriptive validation message.
7. IF the submitted endpointUrl is not a valid HTTPS URL, THEN THE Invitation_Service SHALL return a 400 error.
8. THE Invitation_Service SHALL complete steps 1 through 4 atomically — if any step fails, no partial records SHALL be persisted.

---

### Requirement 4: Self-Registration Page (Frontend)

**User Story:** As a customer, I want a clear and guided web page to enter my PEPPOL details, so that I can complete registration without technical support.

#### Acceptance Criteria

1. THE Self_Registration_Page SHALL be publicly accessible without authentication, identified by the Invitation_Token in the URL path.
2. WHEN the Self_Registration_Page loads, THE Self_Registration_Page SHALL call the token validation endpoint and display an error state if the token is invalid, expired, or already used.
3. WHEN the token is valid, THE Self_Registration_Page SHALL display the inviting organisation's name and the customer's email address as read-only context.
4. THE Self_Registration_Page SHALL present input fields for: Participant_ID, endpoint URL, an optional bearer auth token, and a simplified HTTP delivery toggle.
5. WHEN the customer submits the form, THE Self_Registration_Page SHALL display a success confirmation showing the registered Participant_ID and endpoint URL.
6. IF the submission returns a validation error, THE Self_Registration_Page SHALL display the error message inline without clearing the form.

---

### Requirement 5: Invitation Management for C1 Suppliers

**User Story:** As a C1 supplier, I want to view and manage the invitations I have sent, so that I can track which customers have completed PEPPOL onboarding and resend invitations where needed.

#### Acceptance Criteria

1. THE Invitation_Service SHALL provide an endpoint that returns all invitations for a given organisation, ordered by creation date descending.
2. WHEN listing invitations, THE Invitation_Service SHALL include for each invitation: the CustomerContact email, invitation status (`PENDING`, `COMPLETED`, `CANCELLED`, `EXPIRED`), creation timestamp, expiry timestamp, and completion timestamp where applicable.
3. WHEN a C1 supplier requests cancellation of a PENDING invitation, THE Invitation_Service SHALL update the invitation status to `CANCELLED`.
4. IF a C1 supplier requests cancellation of an invitation that is not in `PENDING` status, THEN THE Invitation_Service SHALL return a 409 error.
5. THE Invitation_Service SHALL require a valid C1 organisation API key for all invitation management endpoints.

---

### Requirement 6: Invitation Expiry

**User Story:** As a platform operator, I want expired invitations to be clearly marked, so that the system does not process stale registration attempts.

#### Acceptance Criteria

1. WHEN the token validation endpoint is called with a token whose expiry timestamp is in the past and status is `PENDING`, THE Invitation_Service SHALL treat the invitation as expired and return a 410 error.
2. THE Invitation_Service SHALL expose an invitation status of `EXPIRED` in list responses for invitations whose expiry timestamp has passed and whose status remains `PENDING`.
3. WHERE a scheduled cleanup job is configured, THE Invitation_Service SHALL transition all PENDING invitations past their expiry timestamp to `EXPIRED` status during each job execution.

---

### Requirement 7: Email Notification on Completion

**User Story:** As a C1 supplier, I want to be notified when a customer completes their PEPPOL self-registration, so that I know the customer is ready to receive invoices via PEPPOL.

#### Acceptance Criteria

1. WHEN a customer successfully completes self-registration via an Invitation_Token, THE Invitation_Service SHALL send a notification email to the inviting organisation's `senderEmail` address.
2. THE notification email SHALL include the customer's email address, their registered Participant_ID, and the completion timestamp.
3. IF the notification email fails to send, THEN THE Invitation_Service SHALL log the failure and SHALL NOT roll back the completed registration.

---

### Requirement 8: Security and Token Integrity

**User Story:** As a platform operator, I want invitation tokens to be secure and single-use, so that the self-registration flow cannot be replayed or abused.

#### Acceptance Criteria

1. THE Invitation_Service SHALL generate each Invitation_Token as a cryptographically random UUID (version 4).
2. WHEN an Invitation_Token is used to complete a registration, THE Invitation_Service SHALL immediately set the invitation status to `COMPLETED` so the token cannot be reused.
3. THE Self_Registration_Page endpoint SHALL NOT require an organisation API key, as it is accessed by unauthenticated customers.
4. THE Invitation_Service SHALL NOT expose the inviting organisation's API key or internal IDs in the invitation email or the Self_Registration_Page token validation response.
