# Implementation Plan: Admin User Management

## Overview

Replace the single env-var admin account with a multi-user admin system backed by the database. Stateful DB session tokens replace the `ADMIN_API_KEY` env-var approach entirely. Implementation proceeds in layers: entities → repositories → services → controllers → filter update → frontend.

## Tasks

- [x] 1. Create JPA entities and repositories
  - [x] 1.1 Create `AdminUser` entity in `security/model/AdminUser.java`
    - Map to `admin_users` table with fields: id (UUID), username (unique), passwordHash, displayName, role (default `"ADMIN"`), active (default true), createdAt
    - Use `@GeneratedValue` with UUID strategy; `createdAt` defaults to `Instant.now()`
    - _Requirements: 1.1_

  - [x] 1.2 Create `AdminSessionToken` entity in `security/model/AdminSessionToken.java`
    - Map to `admin_session_tokens` table with fields: id (UUID), token (unique), adminUser (ManyToOne LAZY FK), expiresAt, createdAt
    - Add index on `token` column for fast filter lookup
    - _Requirements: 3.3_

  - [x] 1.3 Create `AdminUserRepository` in `security/repository/AdminUserRepository.java`
    - Extend `JpaRepository<AdminUser, UUID>`
    - Methods: `findByUsername(String): Optional<AdminUser>`, `countByActiveTrue(): long`, `existsByUsername(String): boolean`
    - _Requirements: 1.1, 1.3_

  - [x] 1.4 Create `AdminSessionTokenRepository` in `security/repository/AdminSessionTokenRepository.java`
    - Extend `JpaRepository<AdminSessionToken, UUID>`
    - Methods: `findByTokenAndExpiresAtAfter(String token, Instant now): Optional<AdminSessionToken>`, `deleteByAdminUser(AdminUser user)`
    - _Requirements: 2.5, 2.6, 3.3_

  - [x] 1.5 Write property test for `AdminUser` persistence round-trip
    - **Property 1: Admin user persistence round-trip**
    - **Validates: Requirements 1.1**
    - Test class: `AdminUserPersistencePropertyTest` in `security/` test package
    - Use `@SpringBootTest` + `@Transactional`; generate random username/displayName strings via jqwik `@ForAll`

  - [x] 1.6 Write property test for bcrypt password storage
    - **Property 2: Passwords are stored as bcrypt hashes**
    - **Validates: Requirements 1.2**
    - Test class: `AdminUserPersistencePropertyTest`
    - For any plaintext password ≥ 8 chars, assert stored hash ≠ plaintext and `BCryptPasswordEncoder.matches()` returns true

- [x] 2. Implement `AdminProperties` and `application.yml` changes
  - [x] 2.1 Modify `AdminProperties.java` — remove `apiKey` field and its getter/setter; add `tokenExpiryHours` (int, default 8)
    - _Requirements: 3.1_

  - [x] 2.2 Update `application.yml` — remove `admin.api-key` line; add `token-expiry-hours: ${ADMIN_TOKEN_EXPIRY_HOURS:8}`
    - _Requirements: 3.1_

  - [x] 2.3 Update `docker-compose.yml` and `.env` — remove `ADMIN_API_KEY` variable from both files
    - _Requirements: (clean break from env-var key)_

- [x] 3. Implement `AdminBootstrapService`
  - [x] 3.1 Create `AdminBootstrapService` in `security/service/AdminBootstrapService.java`
    - `@Component` with `@EventListener(ApplicationReadyEvent.class)`
    - If `adminUserRepository.count() == 0`: read `adminProperties.getUsername()` and `adminProperties.getPassword()`; if either is blank throw `IllegalStateException` (prevents startup); otherwise hash password with `BCryptPasswordEncoder` (cost 12) and save a new `AdminUser`
    - Log info on successful seed; log error and throw on missing env vars
    - _Requirements: 1.4, 1.5_

- [x] 4. Implement `AdminAuthService` and update `AdminAuthController`
  - [x] 4.1 Create `AdminAuthService` in `security/service/AdminAuthService.java`
    - `login(username, password)`: look up active user by username; return 401 if not found; return 403 if inactive; verify bcrypt; generate opaque token (`UUID.randomUUID().toString()`); persist `AdminSessionToken` with `expiresAt = Instant.now().plus(tokenExpiryHours, HOURS)`; return `AdminLoginResponse(token, displayName)`
    - `logout(token)`: delete token from repository if present
    - Inject `BCryptPasswordEncoder` bean (declare in `SecurityConfig` or a `@Bean` method)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.1_

  - [x] 4.2 Rewrite `AdminAuthController.java`
    - Replace existing plain-text password comparison with delegation to `AdminAuthService`
    - `POST /api/v1/admin/login` → returns `{ token, name }` (rename response field from `apiKey` to `token`)
    - `POST /api/v1/admin/logout` → reads `X-API-Key` header, calls `adminAuthService.logout(token)`, returns 204
    - Remove `AdminProperties` direct dependency for auth logic (keep only for bootstrap)
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 4.3 Write property tests for admin authentication
    - **Property 3: Valid login returns token and name**
    - **Property 4: Invalid credentials return 401**
    - **Property 5: Login response never exposes password hash**
    - **Validates: Requirements 2.1, 2.2, 2.4**
    - Test class: `AdminAuthPropertyTest` in `security/` test package
    - Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`; generate random valid/invalid credential combos via jqwik

- [x] 5. Checkpoint — ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement `AdminUserService` and `AdminUserController`
  - [x] 6.1 Create `AdminUserService` in `security/service/AdminUserService.java`
    - `createUser(username, password, displayName)`: validate password length ≥ 8 (throw 400); check username uniqueness (throw 409); hash password; persist and return `AdminUserDto`
    - `listUsers()`: return all users mapped to `AdminUserDto` (no passwordHash, no tokens)
    - `deactivateUser(UUID id)`: load user (404 if absent); check `countByActiveTrue() > 1` (409 if last active); set `active = false`; call `tokenRepo.deleteByAdminUser(user)`
    - `reactivateUser(UUID id)`: load user (404 if absent); set `active = true`; save
    - _Requirements: 4.1, 4.2, 4.3, 5.1, 5.2, 6.1, 6.2, 6.3, 7.1, 7.2, 8.1, 8.2_

  - [x] 6.2 Create `AdminUserController` in `security/AdminUserController.java`
    - `GET /api/v1/admin/users` → `adminUserService.listUsers()`
    - `POST /api/v1/admin/users` → `adminUserService.createUser(...)`
    - `PATCH /api/v1/admin/users/{id}/deactivate` → `adminUserService.deactivateUser(id)`
    - `PATCH /api/v1/admin/users/{id}/reactivate` → `adminUserService.reactivateUser(id)`
    - Add `@Tag(name = "Admin Users")` and `@Operation` annotations
    - _Requirements: 4.1, 4.4, 5.1, 5.3, 6.1, 6.4, 7.1, 7.3_

  - [x] 6.3 Add DTOs to `AdminUserController` (or a shared DTOs file in `security/`)
    - `record AdminLoginRequest(String username, String password) {}`
    - `record AdminLoginResponse(String token, String name) {}`
    - `record CreateAdminUserRequest(String username, String password, String displayName) {}`
    - `record AdminUserDto(UUID id, String username, String displayName, String role, boolean active, Instant createdAt) {}`
    - _Requirements: 5.1, 5.2_

  - [x] 6.4 Write property tests for `AdminUserService`
    - **Property 9: Deactivation invalidates all tokens**
    - **Property 10: Created admin user is active and persisted**
    - **Property 11: Short passwords are rejected**
    - **Property 14: Deactivate/reactivate round-trip**
    - **Property 15: Minimum active administrator invariant**
    - **Validates: Requirements 3.3, 3.4, 4.1, 4.3, 6.1, 7.1, 8.1, 8.2, 8.3**
    - Test class: `AdminUserServicePropertyTest` in `security/` test package

  - [x] 6.5 Write property tests for `AdminUserController`
    - **Property 12: Admin-only endpoints reject non-admin callers**
    - **Property 13: User list contains all users and no sensitive fields**
    - **Validates: Requirements 4.4, 5.1, 5.2, 5.3, 6.4, 7.3**
    - Test class: `AdminUserControllerPropertyTest` in `security/` test package

- [x] 7. Update `ApiKeyAuthFilter` and `SecurityConfig`
  - [x] 7.1 Rewrite `ApiKeyAuthFilter.java` to inject `AdminSessionTokenRepository` instead of `AdminProperties`
    - New lookup order: (1) query `adminSessionTokenRepo.findByTokenAndExpiresAtAfter(token, Instant.now())` → if present and user is active, grant `ROLE_ADMIN`; (2) query `orgRepo.findByApiKey(token)` → if active org, grant `ROLE_ORG`; (3) proceed unauthenticated
    - Remove all `adminProperties.getApiKey()` references
    - _Requirements: 2.5, 2.6_

  - [x] 7.2 Update `SecurityConfig.java` — replace `AdminProperties` constructor arg with `AdminSessionTokenRepository`; pass it to `ApiKeyAuthFilter`
    - _Requirements: 2.5_

  - [x] 7.3 Write property tests for session token authentication
    - **Property 6: Valid session token grants ROLE_ADMIN**
    - **Property 7: Expired or invalid token is rejected**
    - **Property 8: Issued tokens have correct expiry**
    - **Validates: Requirements 2.5, 2.6, 3.1, 3.2**
    - Test class: `AdminSessionTokenPropertyTest` in `security/` test package

- [x] 8. Checkpoint — ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Update frontend
  - [ ] 9.1 Update `adminLogin` in `client.ts` — change response type from `{ apiKey: string; name: string }` to `{ token: string; name: string }`
    - _Requirements: 2.1_

  - [ ] 9.2 Update `AdminLoginPage.tsx` — map `res.data.token` to `session.apiKey` (keeps interceptor unchanged); remove the 503 "not configured" error branch
    - _Requirements: 2.1_

  - [ ] 9.3 Create `AdminUsersPage.tsx` in `frontend/src/pages/admin/`
    - On mount: fetch `GET /api/v1/admin/users` and render a table with columns: username, display name, role, status (active badge), created date, actions
    - Create-user form (inline or modal): username, password, display name fields; POST on submit; refresh list on success; show validation errors (400, 409)
    - Deactivate button: show confirmation dialog before calling `PATCH /{id}/deactivate`; display server error message on 409 (last admin guard)
    - Reactivate button (shown for inactive users): call `PATCH /{id}/reactivate`; refresh list on success
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [ ] 9.4 Add route in `App.tsx` — import `AdminUsersPage` and add `<Route path="/admin/users" element={<ProtectedRoute adminOnly><AdminUsersPage /></ProtectedRoute>} />`
    - _Requirements: 9.1_

- [ ] 10. Final checkpoint — ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Property tests use jqwik (already in the project); each test class lives under `src/test/java/com/esolutions/massmailer/security/`
- Each property test references its design property in a comment: `// Feature: admin-user-management, Property N: <title>`
- The `Session.apiKey` field in `types.ts` continues to hold the admin session token value — no structural change to the interceptor is needed
- `BCryptPasswordEncoder` with cost factor 12 should be declared as a `@Bean` in `SecurityConfig` (or a dedicated `PasswordConfig`) to allow injection across services
