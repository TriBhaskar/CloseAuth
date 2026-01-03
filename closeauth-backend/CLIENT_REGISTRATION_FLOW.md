# Client-Specific Registration Flow

## Overview

This document describes the client-specific user registration flow implemented for the OAuth2 Authorization Code Flow. Each OAuth2 client can configure its own verification requirements, and users register per-client with isolated user bases.

## Architecture

### Flow Diagram

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│   Client App    │────▶│  Authorization   │────▶│  Registration Page  │
│  (Redirect)     │     │     Server       │     │   (No Account)      │
└─────────────────┘     └──────────────────┘     └─────────────────────┘
                                                          │
                                                          ▼
                                              ┌─────────────────────────┐
                                              │  POST /oauth2/register  │
                                              │      /{clientId}        │
                                              └───────────┬─────────────┘
                                                          │
                                    ┌─────────────────────┼─────────────────────┐
                                    │                     │                     │
                                    ▼                     ▼                     ▼
                            ┌───────────────┐   ┌─────────────────┐   ┌─────────────────┐
                            │    EMAIL      │   │     PHONE       │   │ ADMIN_APPROVAL  │
                            │ Verification  │   │  Verification   │   │    Pending      │
                            └───────┬───────┘   └────────┬────────┘   └────────┬────────┘
                                    │                    │                     │
                                    ▼                    ▼                     ▼
                            ┌───────────────┐   ┌─────────────────┐   ┌─────────────────┐
                            │  Verify OTP   │   │   Verify OTP    │   │  Admin Approves │
                            └───────┬───────┘   └────────┬────────┘   └────────┬────────┘
                                    │                    │                     │
                                    └─────────────────────┴─────────────────────┘
                                                          │
                                                          ▼
                                              ┌─────────────────────────┐
                                              │   User Persisted to DB  │
                                              │   UserClientMap Created │
                                              │     Status = ACTIVE     │
                                              └─────────────────────────┘
```

## Verification Modes

Each client can be configured with one of the following verification modes in `ApplicationRegistrationConfig`:

| Mode | Description | Required Actions |
|------|-------------|------------------|
| `EMAIL` | Email OTP verification required | User verifies email OTP |
| `PHONE` | SMS OTP verification required | User verifies phone OTP |
| `EMAIL_AND_PHONE` | Both email and phone required | User verifies both OTPs |
| `ADMIN_APPROVAL` | Admin must approve registration | Admin approves via dashboard |
| `AUTO_APPROVE` | Immediate activation | No verification needed |

## API Endpoints

### OAuth2 Registration Controller
Base Path: `/oauth2/register`

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/{clientId}` | Register user for specific OAuth2 client |
| `POST` | `/verify-email` | Verify email OTP |
| `POST` | `/verify-phone` | Verify phone OTP |
| `POST` | `/resend-email-otp` | Resend email verification code |
| `POST` | `/resend-phone-otp` | Resend phone verification code |

### Admin Approval Controller
Base Path: `/api/v1/admin/clients/{clientId}/pending-registrations`

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/` | List pending registrations for client |
| `GET` | `/count` | Get count of pending registrations |
| `POST` | `/{email}/approve` | Approve a pending registration |
| `POST` | `/{email}/reject` | Reject a pending registration |

## Request/Response Examples

### 1. Register User

**Request:**
```http
POST /oauth2/register/my-client-id
Content-Type: application/json

{
  "username": "johndoe",
  "email": "john@example.com",
  "password": "SecurePass123!",
  "firstName": "John",
  "lastName": "Doe",
  "phone": "+1234567890"
}
```

**Response (EMAIL mode):**
```json
{
  "email": "john@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "message": "Registration initiated. Please check your email for the verification code.",
  "otpValiditySeconds": 600,
  "timestamp": "2024-12-29T10:30:00"
}
```

### 2. Verify Email

**Request:**
```http
POST /oauth2/register/verify-email
Content-Type: application/json

{
  "email": "john@example.com",
  "verificationCode": "123456"
}
```

**Response:**
```json
{
  "message": "Email verified successfully. Your account is now active.",
  "status": "SUCCESS",
  "timestamp": "2024-12-29T10:35:00"
}
```

### 3. Verify Phone

**Request:**
```http
POST /oauth2/register/verify-phone
Content-Type: application/json

{
  "phone": "+1234567890",
  "otp": "654321",
  "email": "john@example.com"
}
```

**Response:**
```json
{
  "message": "Phone verified successfully. Your account is now active.",
  "status": "SUCCESS",
  "timestamp": "2024-12-29T10:36:00"
}
```

## Data Flow

### Registration Data Storage (Redis)

During registration, user data is stored in Redis (not database) until verification completes:

```
Key: registration_{email}
TTL: 2 hours
Value: {
  "registrationDto": { ... user data ... },
  "globalRoleEnum": "END_USER",
  "clientId": "my-client-id",
  "verificationMode": "EMAIL_AND_PHONE",
  "pendingVerifications": ["EMAIL", "PHONE"]
}
```

### Admin Pending Registrations (Redis)

For `ADMIN_APPROVAL` mode, registrations are stored in a separate cache:

```
Key: admin_pending_{clientId}_{email}
TTL: 7 days
Value: { ... registration data ... }
```

### Database Persistence

User is persisted to database only after all verifications complete:

1. **`users` table** - User account created with `status = ACTIVE`
2. **`user_client_map` table** - Mapping created with `status = ACTIVE`

## Code Structure

### New Files Created

```
src/main/java/com/anterka/closeauthbackend/
├── auth/
│   ├── enums/
│   │   ├── VerificationMode.java          # EMAIL, PHONE, ADMIN_APPROVAL, etc.
│   │   └── VerificationType.java          # EMAIL, PHONE, ADMIN_APPROVAL
│   ├── dto/
│   │   ├── request/
│   │   │   ├── ClientUserRegistrationDto.java
│   │   │   ├── PhoneVerificationDto.java
│   │   │   └── ResendPhoneOtpDto.java
│   │   └── response/
│   │       └── PendingRegistrationResponse.java
│   ├── service/
│   │   └── RegistrationCompletionService.java
│   ├── strategy/
│   │   └── verification/
│   │       ├── VerificationStrategy.java       # Interface
│   │       ├── VerificationStrategyFactory.java
│   │       ├── EmailVerificationStrategy.java
│   │       ├── PhoneVerificationStrategy.java
│   │       ├── AdminApprovalStrategy.java
│   │       ├── EmailAndPhoneVerificationStrategy.java
│   │       └── AutoApproveStrategy.java
│   └── controller/
│       └── AdminApprovalController.java
├── oauth2/
│   ├── controller/
│   │   └── OAuth2RegistrationController.java
│   └── service/
│       └── OAuth2RegistrationService.java
├── notification/
│   └── service/
│       ├── SmsService.java                 # Interface
│       └── StubSmsService.java             # Placeholder implementation
└── cache/
    ├── repository/
    │   └── AdminPendingRegistrationRepository.java
    └── service/
        └── AdminPendingRegistrationCacheService.java
```

### Modified Files

| File | Changes |
|------|---------|
| `auth/dto/RegistrationData.java` | Extended with `clientId`, `verificationMode`, `pendingVerifications` |
| `auth/dto/response/UserRegistrationResponse.java` | Added builder pattern support |
| `auth/service/OtpService.java` | Added phone OTP methods (`savePhoneOtp`, `validatePhoneOtp`) |

## Strategy Pattern

The verification flow uses the Strategy pattern for flexibility:

```java
public interface VerificationStrategy {
    void initiate(RegistrationData registrationData);
    Set<VerificationType> getRequiredVerificationTypes();
    default boolean requiresImmediatePersistence() { return false; }
}
```

### Strategy Selection

```java
VerificationStrategy strategy = switch (verificationMode) {
    case EMAIL -> emailVerificationStrategy;
    case PHONE -> phoneVerificationStrategy;
    case ADMIN_APPROVAL -> adminApprovalStrategy;
    case EMAIL_AND_PHONE -> emailAndPhoneVerificationStrategy;
    case AUTO_APPROVE -> autoApproveStrategy;
};
```

## Client-User Mapping

### Rules

1. A user belongs to a specific OAuth client
2. Each client maintains its own isolated user base
3. A user cannot log in to another client unless explicitly registered
4. Login validates `UserClientMap` exists with `status = ACTIVE`

### UserClientMap Entity

```java
@Entity
@Table(name = "user_client_map")
public class UserClientMap {
    private Integer id;
    private Users user;
    private Client client;
    private String status;  // PENDING, ACTIVE, REVOKED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

## Security Considerations

1. **Rate Limiting** - All endpoints are rate-limited
2. **OTP Expiry** - OTPs expire after 10 minutes
3. **Redis TTL** - Registration data expires after 2 hours
4. **Admin Approval TTL** - Pending approvals expire after 7 days
5. **Client Validation** - Registration config must exist for client
6. **Input Validation** - All DTOs have validation annotations

## Configuration

### ApplicationRegistrationConfig

Each client has a registration configuration in the `application_registration_config` table:

| Field | Description |
|-------|-------------|
| `verification_method` | EMAIL, PHONE, ADMIN_APPROVAL, EMAIL_AND_PHONE, AUTO_APPROVE |
| `require_email_verification` | Boolean flag |
| `require_phone_verification` | Boolean flag |
| `require_admin_approval` | Boolean flag |
| `allow_self_registration` | Whether users can self-register |
| `registration_enabled` | Whether registration is open |
| `require_phone` | Whether phone field is required |
| `require_first_name` | Whether first name is required |
| `require_last_name` | Whether last name is required |

## SMS Service

Currently uses a stub implementation that logs OTPs to console:

```java
@Service
public class StubSmsService implements SmsService {
    @Override
    public CompletableFuture<Boolean> sendOtp(String phoneNumber, String otp) {
        log.info("STUB SMS: Phone={}, OTP={}", phoneNumber, otp);
        return CompletableFuture.completedFuture(true);
    }
}
```

Replace with Twilio, AWS SNS, or other provider for production.

## Future Enhancements

1. **Webhook Notifications** - Notify external systems on registration events
2. **Custom Email Templates** - Per-client email template customization
3. **Social Login** - OAuth2 social provider integration
4. **MFA Support** - Multi-factor authentication options
5. **Invitation-Only Registration** - Invite code based registration

