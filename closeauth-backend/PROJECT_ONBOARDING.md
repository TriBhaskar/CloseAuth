# CloseAuth Backend - Project Onboarding Guide

## 📋 Project Summary

**CloseAuth** is a multi-tenant OAuth2 Authorization Server built with Spring Boot 3.5. It allows developers to register OAuth2 clients (applications) and provides a complete identity management system where each client has its own isolated user base, registration flow, themes, and role-based access control.

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | Spring Boot 3.5 (Java 21) |
| Security | Spring Security OAuth2 Authorization Server |
| Database | PostgreSQL (with Flyway migrations) |
| Cache | Redis (via Jedis 6.0) |
| ORM | Spring Data JPA |
| Build | Maven |
| Other | Lombok, Jakarta Validation, Spring Mail |

---

## 🏗️ Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                        CloseAuth Backend                              │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────┐   ┌─────────────────┐   ┌────────────────────┐    │
│  │  OAuth2     │   │  Admin Auth     │   │  Client Config     │    │
│  │  Endpoints  │   │  (Register/     │   │  (Roles/Themes/    │    │
│  │  (Token/    │   │   Login/Verify) │   │   Registration)    │    │
│  │   Authorize)│   │                 │   │                    │    │
│  └──────┬──────┘   └────────┬────────┘   └─────────┬──────────┘    │
│         │                   │                       │               │
│  ┌──────┴───────────────────┴───────────────────────┴──────────┐    │
│  │                    Service Layer                              │    │
│  │  AuthenticationService, ClientRegistrationService,           │    │
│  │  JwtTokenService, OtpService, OAuth2RegistrationService      │    │
│  └──────────────────────────┬──────────────────────────────────┘    │
│                             │                                        │
│  ┌──────────────────────────┴──────────────────────────────────┐    │
│  │              Data Layer (JPA + Redis)                         │    │
│  │  PostgreSQL: Users, Clients, Roles, Themes, Audit Logs       │    │
│  │  Redis: OTPs, Registration Cache, Rate Limiting, Sessions    │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 📁 Package Structure

```
com.anterka.closeauthbackend/
│
├── CloseauthBackendApplication.java        # Entry point
├── DefaultClientInitializer.java           # Seeds default "admin-client" on startup
│
├── auth/                                   # Admin user authentication
│   ├── controller/
│   │   ├── AuthController.java             # Login, Register, Verify, Password Reset
│   │   └── AdminApprovalController.java    # Approve/reject pending registrations
│   ├── dto/                                # Request/Response DTOs
│   ├── enums/                              # VerificationMode, VerificationType
│   ├── service/
│   │   ├── AuthenticationService.java      # Core auth logic (login, register, lockout)
│   │   ├── ClientRegistrationService.java  # Client-specific user registration
│   │   ├── JwtTokenService.java            # User JWT (X-User-Token) generation
│   │   ├── OtpService.java                # OTP generation & validation (constant-time)
│   │   └── RegistrationCompletionService.java # Persist user after verification
│   └── strategy/
│       ├── UserRegistrationStrategy.java
│       ├── ClientUserRegistrationStrategy.java
│       ├── UserRegistrationStrategyFactory.java
│       └── verification/                   # Strategy pattern for verification modes
│           ├── VerificationStrategy.java (interface)
│           ├── EmailVerificationStrategy.java
│           ├── PhoneVerificationStrategy.java
│           ├── EmailAndPhoneVerificationStrategy.java
│           ├── AdminApprovalStrategy.java
│           ├── AutoApproveStrategy.java
│           └── VerificationStrategyFactory.java
│
├── oauth2/                                 # OAuth2 protocol endpoints
│   ├── controller/
│   │   ├── OAuth2FlowController.java       # Client info, consent page
│   │   └── OAuth2RegistrationController.java # Client-specific user registration
│   ├── dto/
│   ├── entity/
│   ├── repository/
│   └── service/
│       └── OAuth2RegistrationService.java  # Registration orchestration for OAuth2 flow
│
├── client/                                 # Client configuration management
│   ├── controller/
│   │   ├── ClientController.java           # List owned clients
│   │   └── ClientConfigurationController.java # Roles, themes, registration config
│   ├── dto/
│   ├── entity/
│   ├── repository/
│   └── service/
│       ├── ApplicationRoleService.java
│       ├── ApplicationRegistrationConfigService.java
│       ├── ClientThemeService.java
│       ├── ThemeConfigurationService.java
│       ├── ClientInitializationService.java
│       ├── ClientService.java
│       └── ClientOwnershipVerifier.java    # Shared ownership verification (DRY)
│
├── user/                                   # User management
│   ├── entity/                             # Users, UserClientMap entities
│   ├── enums/
│   ├── repository/
│   ├── security/                           # UserContextHelper
│   └── service/                            # UserService, UserPasswordResetService
│
├── cache/                                  # Redis caching layer
│   ├── repository/                         # Redis data access
│   ├── service/                            # RateLimiterService, RegistrationCacheService
│   └── strategy/                           # RateLimitStrategy, RateLimitStrategyFactory
│
├── common/                                 # Cross-cutting concerns
│   ├── config/
│   │   ├── AuthorisationServerConfig.java  # 4 security filter chains + JWT + keys
│   │   ├── CorsConfig.java                 # CORS configuration
│   │   ├── CustomClientMetadataConfig.java # OIDC client registration metadata
│   │   └── properties/
│   │       └── CloseAuthProperties.java    # Centralized @ConfigurationProperties
│   ├── constants/                          # ApiPaths
│   ├── dto/                                # CustomApiResponse, ResponseStatusEnum
│   ├── exception/                          # Custom exceptions + GlobalAdviceController
│   └── filter/                             # TwoLayerAuthenticationFilter
│
├── notification/                           # Email & SMS services
│   └── service/                            # EmailService, SmsService (stub)
│
└── audit/                                  # Audit logging
    ├── entity/
    ├── event/
    │   └── CloseAuthAuditEvent.java        # Event-driven audit record
    ├── repository/
    └── service/
        └── AuditLogService.java            # Direct + @TransactionalEventListener
```

---

## 🔐 Security Architecture

### Dual Authentication Model

The system uses **4 ordered security filter chains** with a dual-auth model:

| Order | Filter Chain | Endpoints | Auth Required |
|-------|-------------|-----------|---------------|
| 1 | OAuth2 Authorization Server | `/oauth2/**`, `/connect/register`, `/.well-known/**` | OAuth2 standard |
| 2 | Admin Auth | `/api/v1/admin/auth/**`, `/oauth2/client-info`, `/oauth2/register/**` | OAuth2 Bearer token |
| 3 | Dual Auth | `/api/v1/clients/**` | OAuth2 Bearer + X-User-Token |
| 4 | Default | Everything else | Form login |

### How Dual Auth Works

```
┌─────────────────┐         ┌──────────────────────────────────────────┐
│  Vue BFF Client │         │          CloseAuth Backend                │
│  (localhost:5173)│         │                                          │
├─────────────────┤         │  ┌─────────────────────────────────────┐ │
│                 │ ──────► │  │ Filter Chain 3 (Dual Auth)           │ │
│  Authorization: │         │  │                                     │ │
│  Bearer {token} │         │  │  1. OAuth2 Resource Server validates │ │
│                 │         │  │     Bearer → sets SecurityContext    │ │
│  X-User-Token:  │         │  │                                     │ │
│  {user_jwt}     │         │  │  2. TwoLayerAuthenticationFilter    │ │
│                 │         │  │     validates X-User-Token           │ │
│                 │         │  │     → stores in request attributes   │ │
└─────────────────┘         │  └─────────────────────────────────────┘ │
                            └──────────────────────────────────────────┘
```

- **OAuth2 Bearer Token** = BFF client identity (scope: `client.create`)
- **X-User-Token** = Admin user identity (custom JWT with userId, username, roles)
- Both are validated independently; SecurityContext holds OAuth2, request attributes hold user info

### RSA Key Management

Keys are persisted as PEM files (`src/main/resources/keys/`) and loaded at startup. Tokens survive application restarts.

### CORS

Configured via `CloseAuthProperties.Cors` and applied to all filter chains. Supports the BFF on a different origin.

### Account Lockout

- After N failed login attempts → account locked for M minutes
- Configurable via `closeauth.security.max-login-attempts` and `lockout-duration-minutes`

---

## 🔄 Complete Code Flows

### Flow 1: Admin Registration

```
Client (BFF) → POST /api/v1/admin/auth/register
    │          Headers: Authorization: Bearer {oauth2_token}
    │          Body: { username, email, password, firstName, lastName, phone }
    │
    ▼
AuthController.registerUser()
    │  @PreAuthorize("hasAuthority('SCOPE_client.create')")
    ▼
AuthenticationService.registerUser()
    ├── validateUserData() → checks username/email/phone uniqueness
    ├── otpService.generateOtp() → 6-digit random (configurable length)
    ├── otpService.saveOtp(email, otp) → Redis with TTL (600s default)
    ├── emailService.sendOTPMail(email, otp) → async via virtual threads
    ├── RegistrationData created (DTO + GlobalRoleEnum.END_USER)
    ├── registrationCacheService.saveRegistration(email, data) → Redis
    └── return UserRegistrationResponse (email, otpValiditySeconds)
```

### Flow 2: Email Verification (Admin)

```
Client (BFF) → POST /api/v1/admin/auth/verify-email
    │          Body: { email, verificationCode }
    ▼
AuthController.verifyEmail()
    ▼
AuthenticationService.verifyUserEmail()
    ├── registrationCacheService.getRegistration(email) → from Redis
    ├── otpService.validateOtp(email, code) → constant-time comparison
    ├── UserRegistrationStrategyFactory.getStrategy(globalRole)
    │       → returns ClientUserRegistrationStrategy (for END_USER)
    ├── strategy.createUser(dto) → builds Users entity, hashes password
    ├── userRepository.save(user) → persist to PostgreSQL
    ├── strategy.performPostRegistrationSetup(user, dto)
    ├── registrationCacheService.deleteRegistration(email)
    └── otpService.deleteOtp(email)
```

### Flow 3: Admin Login

```
Client (BFF) → POST /api/v1/admin/auth/login
    │          Headers: Authorization: Bearer {oauth2_token}
    │          Body: { email, password }
    ▼
AuthController.loginUser()
    │  extracts clientId from JWT subject
    ▼
AuthenticationService.loginUser(request, clientId)
    ├── userRepository.findByEmail() → find user
    ├── CHECK: lockedUntil > now? → 401 "Account temporarily locked"
    ├── passwordEncoder.matches(password, hash)
    │   ├── FAIL → handleFailedLogin(user)
    │   │           ├── increment failed_attempts
    │   │           ├── if >= maxAttempts → set lockedUntil
    │   │           └── save user
    │   └── SUCCESS → continue
    ├── CHECK: emailVerified, disabled, locked
    ├── resetFailedAttempts(user) → set failed_attempts=0, lockedUntil=null
    ├── user.setLastLoginAt(now)
    ├── jwtTokenService.generateToken(user, clientId) → X-User-Token JWT
    └── return UserLoginResponse (userId, email, accessToken, expiresAt)
```

### Flow 4: OAuth2 Client Registration (OIDC Dynamic)

```
Client (BFF) → POST /connect/register
    │          Headers: Authorization: Bearer {oauth2_token}
    │                   X-User-Token: {user_jwt}
    │          Body: { client_name, redirect_uris, grant_types, ... }
    ▼
[Filter Chain 1: OAuth2 Authorization Server handles /connect/register]
    ├── OAuth2 Bearer validated
    ├── TwoLayerAuthenticationFilter validates X-User-Token
    ├── CustomClientMetadataConfig processes custom metadata
    ▼
Spring Authorization Server creates RegisteredClient
    ▼
ClientInitializationService.initializeClient(clientId, ownerId)
    ├── Create ClientOwnership (links admin user → client)
    ├── Create default ApplicationRegistrationConfig
    │       (verification_method=EMAIL, registration_enabled=true, ...)
    ├── Create default ClientTheme (light/dark colors)
    ├── ApplicationRoleService.createDefaultRole(clientId, ownerId)
    │       ├── Create "USER" role (default, with basic permissions)
    │       └── autoAssignRoleToOwner() → UserClientMap + UserApplicationRole
    └── auditLogService.logAction("CLIENT_INITIALIZED", ...)
```

### Flow 5: Client-Specific User Registration (End Users)

```
Client (BFF) → POST /oauth2/register/{clientId}
    │          Headers: Authorization: Bearer {oauth2_token}
    │          Body: { username, email, password, firstName, lastName, phone }
    ▼
OAuth2RegistrationController.registerUser()
    │  @PreAuthorize("hasAuthority('SCOPE_client.create')")
    ▼
OAuth2RegistrationService.registerUser(clientId, dto) [OR ClientRegistrationService]
    ├── Load ApplicationRegistrationConfig for clientId
    ├── CHECK: registrationEnabled? allowSelfRegistration?
    ├── validateRegistrationData() → based on client config (require phone, etc.)
    ├── validateUserDoesNotExist()
    ├── CHECK: registrationCacheService.registrationExists(email)?
    ├── Determine VerificationMode from config (EMAIL/PHONE/EMAIL_AND_PHONE/ADMIN_APPROVAL/AUTO_APPROVE)
    ├── VerificationStrategyFactory.getStrategy(mode) → returns appropriate strategy
    ├── Build RegistrationData (dto, clientId, verificationMode, pendingVerifications)
    │
    ├── IF AUTO_APPROVE:
    │   └── registrationCompletionService.persistUserImmediately()
    │
    ├── ELSE:
    │   ├── registrationCacheService.saveRegistration(email, data) → Redis
    │   ├── strategy.initiate(registrationData)
    │   │   ├── EmailVerificationStrategy → sends email OTP
    │   │   ├── PhoneVerificationStrategy → sends SMS OTP
    │   │   ├── EmailAndPhoneStrategy → sends both
    │   │   └── AdminApprovalStrategy → stores in pending queue
    │   └── return response with otpValiditySeconds
    ▼
[User receives OTP via email/SMS]
    ▼
Client (BFF) → POST /oauth2/register/verify-email (or /verify-phone)
    │          Body: { email, verificationCode, clientId }
    ▼
OAuth2RegistrationController.verifyEmail()
    ├── otpService.validateOtp(email, code) → constant-time
    ├── registrationCacheService.getRegistration(email)
    ├── Remove verified type from pendingVerifications set
    ├── IF all verifications complete:
    │   └── registrationCompletionService.completeRegistration()
    │       ├── Create Users entity (passwordHash, emailVerified=true)
    │       ├── Create UserClientMap (status=ACTIVE, clientId)
    │       ├── Assign default application role
    │       └── Clean up Redis cache + OTP
    └── ELSE: update cache with remaining verifications
```

### Flow 6: OAuth2 Authorization Code Flow

```
1. Client App redirects user to:
   GET /oauth2/authorize?client_id=X&redirect_uri=Y&response_type=code&scope=read

2. [Filter Chain 1] → user not authenticated → redirect to login page
   → BFF shows login form

3. User logs in (form login to /login endpoint)
   → Spring Security authenticates via UserDetailsService
   → Session created

4. Back to /oauth2/authorize → user authenticated
   → If consent required → redirect to BFF consent page
   → User approves scopes

5. Authorization Server issues authorization code
   → Redirects to redirect_uri?code=ABC123

6. Client App exchanges code for tokens:
   POST /oauth2/token
   Body: grant_type=authorization_code&code=ABC123&redirect_uri=Y
   Auth: Basic client_id:client_secret

7. Response: { access_token, refresh_token, id_token, expires_in }
   → access_token contains custom claims: roles, username, token_type
```

### Flow 7: Client Configuration Management

```
Client (BFF) → GET/POST/PUT/DELETE /api/v1/clients/{clientId}/roles
    │          Headers: Authorization: Bearer {oauth2_token}
    │                   X-User-Token: {user_jwt}
    ▼
[Filter Chain 3: Dual Auth]
    ├── OAuth2 Bearer validated → SecurityContext
    ├── TwoLayerAuthenticationFilter → request attributes (userId, username, roles)
    ▼
ClientConfigurationController → delegates to service
    ▼
ApplicationRoleService (or ThemeService, RegistrationConfigService)
    ├── ownershipVerifier.verify(clientId, request) → checks client_ownership table
    ├── Perform CRUD operation
    ├── auditLogService.logAction(...) → audit trail
    └── return response
```

### Flow 8: Password Reset

```
Client (BFF) → POST /api/v1/admin/auth/forgot-password
    │          Body: { email }
    ▼
AuthController.forgotPassword()
    ├── rateLimiter.isLimited("forgot_password", clientIp) → 429 if exceeded
    ├── UserPasswordResetService.processForgotPassword()
    │   ├── Find user by email (fail silently if not found)
    │   ├── Generate reset token → save to reset_tokens table with expiry
    │   ├── Build reset link (BFF URL + token)
    │   └── emailService.sendForgotPasswordLinkMail(email, link)
    └── return "If your email is registered, you will receive a link"
         (same message regardless — prevents email enumeration)

Client (BFF) → POST /api/v1/admin/auth/reset-password
    │          Body: { token, newPassword, confirmPassword }
    ▼
AuthController.resetPassword()
    ├── rateLimiter.isLimited("reset_password", clientIp)
    └── UserPasswordResetService.resetPassword()
        ├── Validate token exists and not expired
        ├── Validate password strength
        ├── Check password not reused
        ├── Hash new password → update user
        └── Delete reset token
```

### Flow 9: Admin Approval (for ADMIN_APPROVAL verification mode)

```
[End user registers → data stored in Redis pending queue]
    ▼
Admin (BFF) → GET /api/v1/admin/clients/{clientId}/pending-registrations
    │          Headers: Bearer + X-User-Token
    ▼
AdminApprovalController → lists pending from Redis
    ▼
Admin (BFF) → POST /api/v1/admin/clients/{clientId}/pending-registrations/{email}/approve
    ▼
AdminApprovalController.approveRegistration()
    ├── Get registration data from Redis
    ├── registrationCompletionService.completeRegistration()
    ├── Delete from pending queue
    └── Notify user (email)
```

---

## 🗄️ Database Schema (Key Tables)

| Table | Purpose |
|-------|---------|
| `oauth2_registered_client` | OAuth2 clients (applications) |
| `oauth2_authorization` | Active OAuth2 tokens/codes |
| `users` | All user accounts (fields: email, password_hash, failed_attempts, locked_until, email_verified, disabled, locked) |
| `global_roles` | System-wide roles (CLIENT_ADMIN, END_USER, SUPER_ADMIN) |
| `user_client_map` | Which users belong to which clients (status: PENDING/ACTIVE/APPROVED/REVOKED) |
| `client_ownership` | Which admin user owns which client |
| `application_roles` | Per-client custom roles (e.g., USER, admin, viewer, editor) |
| `user_application_roles` | Role assignments per user per client |
| `application_registration_config` | Per-client registration settings (verification_method, require_phone, etc.) |
| `client_themes` | Per-client UI themes (light/dark colors) |
| `theme_configurations` | Extended theme key-value pairs |
| `audit_logs` | All system actions logged (action, ip, user_agent, metadata JSON, success) |
| `sessions` | User session tracking |
| `reset_tokens` | Password reset tokens (with expiry) |

---

## 🧩 Design Patterns

| Pattern | Where | Purpose |
|---------|-------|---------|
| **Strategy** | `verification/` package | Different verification flows (Email, Phone, Admin, Auto) |
| **Factory** | `VerificationStrategyFactory`, `UserRegistrationStrategyFactory`, `RateLimitStrategyFactory` | Select correct strategy at runtime |
| **Observer/Event** | `CloseAuthAuditEvent` + `@TransactionalEventListener` | Decoupled audit logging |
| **Builder** | DTOs and responses | Fluent object construction (Lombok `@Builder`) |
| **Filter Chain** | Security config (4 chains) | Different auth requirements per endpoint group |
| **Repository** | JPA + Redis repos | Data access abstraction |
| **Template Method** | `UserRegistrationStrategy` | Common registration steps with customizable hooks |

---

## 🛡️ Security Features

| Feature | Implementation |
|---------|---------------|
| Dual authentication | OAuth2 Bearer + X-User-Token (filter-based) |
| Account lockout | Configurable failed attempts + lockout duration |
| Constant-time OTP validation | `MessageDigest.isEqual()` prevents timing attacks |
| Rate limiting | Redis-backed, strategy pattern (forgot_password, reset_password, resend_otp) |
| Password hashing | BCrypt via `PasswordEncoder` |
| CORS | Configurable allowed origins/methods/headers |
| Generic error messages | No info leakage (username/email existence) |
| RSA key persistence | PEM files, tokens survive restarts |
| CSRF disabled | API-only (no browser forms), Bearer token provides CSRF protection |
| Audit trail | All mutations logged with IP, user agent, metadata |

---

## ⚙️ Configuration

All values externalized via `CloseAuthProperties` (`@ConfigurationProperties(prefix = "closeauth")`):

```properties
# RSA Keys
closeauth.keys.rsa-public-key=classpath:keys/public.pem
closeauth.keys.rsa-private-key=classpath:keys/private.pem

# Server
closeauth.issuer-url=http://localhost:9088

# BFF URLs
closeauth.bff.login-page=http://localhost:5173/login
closeauth.bff.consent-page=http://localhost:5173/consent

# Security
closeauth.security.max-login-attempts=5
closeauth.security.lockout-duration-minutes=30

# OTP
closeauth.otp.length=6
closeauth.otp.validity-seconds=600
closeauth.otp.resend-rate-limit=3

# Registration
closeauth.registration.cache-ttl-hours=2
closeauth.registration.admin-pending-ttl-days=7

# CORS
closeauth.cors.allowed-origins=http://localhost:5173
closeauth.cors.allowed-methods=GET,POST,PUT,DELETE,PATCH,OPTIONS
closeauth.cors.allowed-headers=Authorization,X-User-Token,Content-Type
closeauth.cors.allow-credentials=true
```

### Application Startup

On startup, `DefaultClientInitializer` creates a default OAuth2 client (if not exists):
- **Client ID**: `admin-client`
- **Secret**: `admin-secret-for-creation`
- **Scopes**: `read`, `write`, `client.create`
- **Grant Types**: Authorization Code, Client Credentials, Refresh Token

---

## 📡 API Endpoint Reference

### OAuth2 Protocol (Filter Chain 1)
| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/oauth2/authorize` | Session | Authorization endpoint |
| POST | `/oauth2/token` | Client credentials | Token endpoint |
| GET | `/oauth2/jwks` | Public | JSON Web Key Set |
| POST | `/oauth2/revoke` | Bearer | Token revocation |
| POST | `/oauth2/introspect` | Bearer | Token introspection |
| POST | `/connect/register` | Bearer + X-User-Token | Dynamic client registration (OIDC) |
| GET | `/.well-known/openid-configuration` | Public | OIDC discovery |

### Admin Auth (Filter Chain 2 — Bearer only)
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/admin/auth/register` | Register admin user |
| POST | `/api/v1/admin/auth/login` | Login → returns X-User-Token |
| POST | `/api/v1/admin/auth/verify-email` | Verify email OTP |
| POST | `/api/v1/admin/auth/resend-otp` | Resend OTP (rate-limited) |
| POST | `/api/v1/admin/auth/forgot-password` | Request password reset |
| POST | `/api/v1/admin/auth/reset-password` | Reset password with token |
| GET | `/oauth2/client-info?client_id=X` | Get client display info |
| POST | `/oauth2/register/{clientId}` | Register user for client |
| POST | `/oauth2/register/verify-email` | Verify email for client reg |
| POST | `/oauth2/register/verify-phone` | Verify phone for client reg |
| POST | `/oauth2/register/resend-otp` | Resend email OTP |
| POST | `/oauth2/register/resend-phone-otp` | Resend phone OTP |

### Client Configuration (Filter Chain 3 — Bearer + X-User-Token)
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/clients` | List owned clients |
| GET | `/api/v1/clients/{clientId}/roles` | List application roles |
| POST | `/api/v1/clients/{clientId}/roles` | Create role |
| PUT | `/api/v1/clients/{clientId}/roles/{roleId}` | Update role |
| DELETE | `/api/v1/clients/{clientId}/roles/{roleId}` | Delete role |
| GET | `/api/v1/clients/{clientId}/themes` | List themes |
| POST | `/api/v1/clients/{clientId}/themes` | Create theme |
| PUT | `/api/v1/clients/{clientId}/themes/{themeId}` | Update theme |
| PUT | `/api/v1/clients/{clientId}/themes/{themeId}/activate` | Activate theme |
| DELETE | `/api/v1/clients/{clientId}/themes/{themeId}` | Delete theme |
| GET | `/api/v1/clients/{clientId}/themes/{themeId}/configurations` | Get theme configs |
| POST | `/api/v1/clients/{clientId}/themes/{themeId}/configurations` | Set theme configs |
| GET | `/api/v1/clients/{clientId}/registration-config` | Get registration config |
| PUT | `/api/v1/clients/{clientId}/registration-config` | Update registration config |
| GET | `/api/v1/clients/{clientId}/pending-registrations` | List pending approvals |
| POST | `/api/v1/clients/{clientId}/pending-registrations/{email}/approve` | Approve user |
| POST | `/api/v1/clients/{clientId}/pending-registrations/{email}/reject` | Reject user |

---

## 🚀 Running the Project

```bash
# Prerequisites: Java 21, PostgreSQL, Redis

# 1. Start PostgreSQL and create database
createdb closeauth

# 2. Start Redis
redis-server

# 3. Run the application (Flyway auto-migrates schema)
mvn spring-boot:run

# Or compile only
mvn compile -DskipTests
```

---

## 🔑 Key Concepts for New Joiners

1. **Multi-Tenancy**: Each OAuth2 client is a tenant with isolated users, roles, themes, and registration config.

2. **Two Token Types**:
   - **OAuth2 Access Token** (standard): Identifies the calling application (BFF)
   - **X-User-Token** (custom JWT): Identifies the admin user within that application

3. **Registration is Cached, Not Persisted**: User data goes to Redis first. Only after successful verification does it persist to PostgreSQL. This prevents unverified users from polluting the database.

4. **Client Ownership**: Only the admin who registered a client can modify its configuration (enforced via `ClientOwnershipVerifier` → `client_ownership` table).

5. **Strategy Pattern for Verification**: Adding a new verification method = implement `VerificationStrategy` interface + register in `VerificationStrategyFactory`.

6. **Event-Driven Audit**: Services can publish `CloseAuthAuditEvent` via `ApplicationEventPublisher`. `AuditLogService` listens with `@TransactionalEventListener(AFTER_COMMIT)` for decoupled, reliable audit logging.

7. **Centralized Config**: All configurable values live in `CloseAuthProperties`. No hardcoded magic numbers.

8. **Account Security**: Login lockout after N failures, constant-time OTP comparison, rate-limited password reset and OTP resend.

---

## 📝 Flyway Migrations

| Version | Description |
|---------|-------------|
| V1 | Core schema: OAuth2 tables, users, themes, sessions, audit logs |
| V2 | Seed data (global roles: CLIENT_ADMIN, END_USER, SUPER_ADMIN) |
| V3 | Read-only database user |
| V4 | Application roles, user verification config, registration config |

---

## 🧪 Testing

```bash
mvn test
```

Test infrastructure uses Spring Security Test + MockMvc + Spring REST Docs.

