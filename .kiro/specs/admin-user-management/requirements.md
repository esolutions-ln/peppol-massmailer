# Requirements Document

## Introduction

InvoiceDirect currently supports a single hardcoded admin account configured via environment variables (`ADMIN_USERNAME`, `ADMIN_PASSWORD`, `ADMIN_API_KEY`). This feature replaces that mechanism with a proper multi-user admin/operator management system, allowing multiple "access point administrators" to log in, manage the PEPPOL gateway, and administer each other — while ensuring the system can never be left without at least one active administrator.

## Glossary

- **Admin_User**: A named human operator account stored in the database with a username, hashed password, role, and status. Replaces the single env-var admin account.
- **Access_Point_Administrator**: An Admin_User with the `ADMIN` role who has full access to all gateway management functions.
- **Admin_Auth_Service**: The backend service responsible for authenticating Admin_Users and issuing session tokens.
- **Admin_User_Service**: The backend service responsible for creating, listing, and deactivating Admin_Users.
- **Admin_Session_Token**: A short-lived, opaque token (or JWT) issued upon successful login, sent as the `X-API-Key` header on subsequent requests. Distinct from organisation API keys.
- **Bootstrap_Admin**: The initial Admin_User seeded from environment variables (`ADMIN_USERNAME`, `ADMIN_PASSWORD`) when no Admin_Users exist in the database. Ensures a first-login path exists.
- **Admin_User_Repository**: The database repository for persisting and querying Admin_Users.
- **Admin_Login_Page**: The frontend page at `/admin/login` where Admin_Users authenticate.
- **Admin_Users_Page**: The frontend page where an authenticated Access_Point_Administrator manages Admin_Users.
- **System**: The InvoiceDirect PEPPOL invoice delivery gateway backend.

---

## Requirements

### Requirement 1: Admin User Persistence

**User Story:** As a platform operator, I want admin user accounts stored in the database, so that multiple operators can be managed independently of environment variables.

#### Acceptance Criteria

1. THE System SHALL persist Admin_Users in a dedicated database table containing at minimum: unique identifier, username, hashed password, display name, role, active status, and created-at timestamp.
2. THE System SHALL store passwords using a one-way cryptographic hash (bcrypt with cost factor ≥ 12).
3. THE System SHALL enforce uniqueness of the username field at the database level.
4. WHEN the Admin_User table is empty at application startup, THE System SHALL create a Bootstrap_Admin using the `ADMIN_USERNAME` and `ADMIN_PASSWORD` environment variables.
5. IF the `ADMIN_USERNAME` or `ADMIN_PASSWORD` environment variables are absent at bootstrap time, THEN THE System SHALL log an error and refuse to start.

---

### Requirement 2: Admin Authentication

**User Story:** As an access point administrator, I want to log in with my username and password, so that I receive a session token to authenticate subsequent requests.

#### Acceptance Criteria

1. WHEN a POST request is made to `/api/v1/admin/login` with a valid username and matching password for an active Admin_User, THE Admin_Auth_Service SHALL return an Admin_Session_Token and the Admin_User's display name.
2. WHEN a POST request is made to `/api/v1/admin/login` with an unrecognised username or incorrect password, THE Admin_Auth_Service SHALL return HTTP 401 with no token.
3. WHEN a POST request is made to `/api/v1/admin/login` for a deactivated Admin_User, THE Admin_Auth_Service SHALL return HTTP 403.
4. THE Admin_Auth_Service SHALL validate the submitted password against the stored bcrypt hash without exposing the hash in any response.
5. WHEN a valid Admin_Session_Token is presented in the `X-API-Key` header, THE System SHALL grant `ROLE_ADMIN` to the request.
6. WHEN an expired or invalid Admin_Session_Token is presented, THE System SHALL treat the request as unauthenticated and return HTTP 401.

---

### Requirement 3: Admin Session Token Lifecycle

**User Story:** As a platform operator, I want admin session tokens to expire, so that stolen tokens have a limited window of misuse.

#### Acceptance Criteria

1. THE Admin_Auth_Service SHALL issue Admin_Session_Tokens with a configurable expiry duration (default: 8 hours).
2. WHEN an Admin_Session_Token has passed its expiry time, THE System SHALL reject it with HTTP 401.
3. THE System SHALL store Admin_Session_Tokens (or their identifiers) in the database so that deactivating an Admin_User immediately invalidates all of that user's active tokens.
4. WHEN an Admin_User is deactivated, THE Admin_User_Service SHALL invalidate all active Admin_Session_Tokens belonging to that Admin_User.

---

### Requirement 4: Admin User Management — Create

**User Story:** As an access point administrator, I want to create new admin user accounts, so that additional operators can access the gateway.

#### Acceptance Criteria

1. WHEN a POST request is made to `/api/v1/admin/users` with a unique username, password, and display name by an authenticated Access_Point_Administrator, THE Admin_User_Service SHALL create and persist the new Admin_User with active status.
2. WHEN a POST request is made to `/api/v1/admin/users` with a username that already exists, THE Admin_User_Service SHALL return HTTP 409.
3. WHEN a POST request is made to `/api/v1/admin/users` with a password shorter than 8 characters, THE Admin_User_Service SHALL return HTTP 400 with a descriptive error message.
4. WHEN a POST request is made to `/api/v1/admin/users` by a caller without `ROLE_ADMIN`, THE System SHALL return HTTP 403.

---

### Requirement 5: Admin User Management — List

**User Story:** As an access point administrator, I want to list all admin user accounts, so that I can see who has access to the gateway.

#### Acceptance Criteria

1. WHEN a GET request is made to `/api/v1/admin/users` by an authenticated Access_Point_Administrator, THE Admin_User_Service SHALL return a list of all Admin_Users including their identifier, username, display name, role, active status, and created-at timestamp.
2. THE Admin_User_Service SHALL never include password hashes or Admin_Session_Tokens in the list response.
3. WHEN a GET request is made to `/api/v1/admin/users` by a caller without `ROLE_ADMIN`, THE System SHALL return HTTP 403.

---

### Requirement 6: Admin User Management — Deactivate

**User Story:** As an access point administrator, I want to deactivate admin user accounts, so that operators who no longer need access cannot log in.

#### Acceptance Criteria

1. WHEN a PATCH request is made to `/api/v1/admin/users/{id}/deactivate` by an authenticated Access_Point_Administrator, THE Admin_User_Service SHALL set the target Admin_User's active status to false and invalidate all of that user's active Admin_Session_Tokens.
2. WHEN a PATCH request is made to `/api/v1/admin/users/{id}/deactivate` and the target Admin_User is the only remaining active Admin_User, THE Admin_User_Service SHALL return HTTP 409 with an error message indicating that at least one active administrator must remain.
3. WHEN a PATCH request is made to `/api/v1/admin/users/{id}/deactivate` and the target Admin_User does not exist, THE Admin_User_Service SHALL return HTTP 404.
4. WHEN a PATCH request is made to `/api/v1/admin/users/{id}/deactivate` by a caller without `ROLE_ADMIN`, THE System SHALL return HTTP 403.

---

### Requirement 7: Admin User Management — Reactivate

**User Story:** As an access point administrator, I want to reactivate a previously deactivated admin user account, so that operators can regain access without creating a new account.

#### Acceptance Criteria

1. WHEN a PATCH request is made to `/api/v1/admin/users/{id}/reactivate` by an authenticated Access_Point_Administrator, THE Admin_User_Service SHALL set the target Admin_User's active status to true.
2. WHEN a PATCH request is made to `/api/v1/admin/users/{id}/reactivate` and the target Admin_User does not exist, THE Admin_User_Service SHALL return HTTP 404.
3. WHEN a PATCH request is made to `/api/v1/admin/users/{id}/reactivate` by a caller without `ROLE_ADMIN`, THE System SHALL return HTTP 403.

---

### Requirement 8: Minimum Active Administrator Invariant

**User Story:** As a platform operator, I want the system to prevent the last active administrator from being removed, so that the gateway can never be locked out.

#### Acceptance Criteria

1. THE System SHALL maintain the invariant that at least one active Admin_User exists at all times after initial bootstrap.
2. WHEN any operation would reduce the count of active Admin_Users to zero, THE Admin_User_Service SHALL reject the operation with HTTP 409.
3. FOR ALL sequences of deactivation operations, THE System SHALL preserve at least one active Admin_User after each operation completes.

---

### Requirement 9: Frontend — Admin Users Page

**User Story:** As an access point administrator, I want a UI page to manage admin users, so that I can perform user management without using the API directly.

#### Acceptance Criteria

1. WHEN an authenticated Access_Point_Administrator navigates to the admin users management page, THE Admin_Users_Page SHALL display a list of all Admin_Users showing username, display name, and active status.
2. WHEN an authenticated Access_Point_Administrator submits the create-user form with a valid username, password, and display name, THE Admin_Users_Page SHALL call the create endpoint and refresh the list on success.
3. WHEN an authenticated Access_Point_Administrator clicks deactivate on an Admin_User, THE Admin_Users_Page SHALL prompt for confirmation before calling the deactivate endpoint.
4. WHEN the deactivate endpoint returns HTTP 409, THE Admin_Users_Page SHALL display the error message returned by the server without performing the deactivation.
5. WHEN an authenticated Access_Point_Administrator clicks reactivate on a deactivated Admin_User, THE Admin_Users_Page SHALL call the reactivate endpoint and refresh the list on success.


