# CloseAuth Backend — Improvement Changelog

## Overview

This document describes all improvements and refactoring applied to the CloseAuth backend. The project compiles successfully (`BUILD SUCCESS`) after all changes.

---

## 1. Persisted RSA Keys (Critical Security Fix)

**Problem:** RSA keypair was regenerated in-memory on every application restart, invalidating all previously issued JWTs.

**Solution:**
- Generated persistent PEM files: `src/main/resources/keys/public.pem` and `private.pem`
- `AuthorisationServerConfig.jwkSource()` now loads keys from `CloseAuthProperties` instead of calling `generateRsaKey()`
- Tokens survive application restarts

**Files changed:**
- `AuthorisationServerConfig.java` — removed `generateRsaKey()`, uses `properties.getKeys().getRsaPublicKey()`
- `src/main/resources/keys/public.pem` — created
- `src/main/resources/keys/private.pem` — created

**Config:**
```properties
closeauth.keys.rsa-public-key=classpath:keys/public.pem
closeauth.keys.rsa-private-key=classpath:keys/private.pem
```

---

## 2. Fixed Admin Auth Security Chain (Security Contradiction)

**Problem:** Order(2) chain used `.anyRequest().permitAll()` but controller used `@PreAuthorize("hasAuthority('SCOPE_client.create')")`. This was contradictory — `permitAll()` at chain level means unauthenticated requests pass through.

**Solution:** Changed to `.anyRequest().authenticated()` since all admin auth endpoints are accessed via the BFF which always sends a Bearer token.

**Files changed:**
- `AuthorisationServerConfig.java` — line in `adminAuthEndpointsSecurityFilterChain()`

---

## 3. CORS Configuration

**Problem:** No CORS setup existed. The Vue frontend (BFF) on a different origin would be blocked by browsers.

**Solution:**
- Created `CorsConfig.java` with a `CorsConfigurationSource` bean
- All values externalized via `CloseAuthProperties.Cors`
- Applied `.cors(cors -> cors.configurationSource(...))` to all 4 security filter chains

**Files created:**
- `common/config/CorsConfig.java`

**Config:**
```properties
closeauth.cors.allowed-origins=http://localhost:5173
closeauth.cors.allowed-methods=GET,POST,PUT,DELETE,PATCH,OPTIONS
closeauth.cors.allowed-headers=Authorization,X-User-Token,Content-Type
closeauth.cors.allow-credentials=true
```

---

## 4. Account Lockout (Security Hardening)

**Problem:** `failed_attempts` and `locked_until` columns existed in the DB but were never used in login logic.

**Solution:** Added lockout enforcement in `AuthenticationService.loginUser()`:
- Checks `lockedUntil` before password validation
- On failed password: increments `failed_attempts`, locks account if threshold exceeded
- On success: resets `failed_attempts` and `locked_until` to null
- Thresholds are configurable via properties

**Files changed:**
- `AuthenticationService.java` — added `handleFailedLogin()`, `resetFailedAttempts()`, lockout check

**Config:**
```properties
closeauth.security.max-login-attempts=5
closeauth.security.lockout-duration-minutes=30
```

---

## 5. Secure OTP Validation (Timing Attack Prevention)

**Problem:** `storedOtp.equals(providedOtp)` is vulnerable to timing attacks — an attacker can deduce characters by measuring response time.

**Solution:** Replaced with `MessageDigest.isEqual(a.getBytes(), b.getBytes())` which performs constant-time comparison.

**Files changed:**
- `OtpService.java` — added `constantTimeEquals()` method, used in `validateOtp()` and `validatePhoneOtp()`

---

## 6. Rate-Limited OTP Resend

**Problem:** `resendOtp()` had no rate limiting, enabling OTP spam.

**Solution:** Added `rateLimiterService.isLimited("resend_otp", email)` check before generating a new OTP.

**Files changed:**
- `AuthenticationService.java` — added rate limit check in `resendOtp()`

---

## 7. Centralized Configuration (`CloseAuthProperties`)

**Problem:** Hardcoded values scattered across classes (`OTP_VALIDITY_SECONDS`, issuer URL, lockout thresholds, etc.)

**Solution:** Created a single `@ConfigurationProperties(prefix = "closeauth")` class with nested configuration for all domains.

**Files created:**
- `common/config/properties/CloseAuthProperties.java`

**All configurable values:**
```properties
closeauth.issuer-url=http://localhost:9088
closeauth.bff.login-page=http://localhost:5173/login
closeauth.bff.consent-page=http://localhost:5173/consent
closeauth.security.max-login-attempts=5
closeauth.security.lockout-duration-minutes=30
closeauth.otp.length=6
closeauth.otp.validity-seconds=600
closeauth.otp.resend-rate-limit=3
closeauth.registration.cache-ttl-hours=2
closeauth.registration.admin-pending-ttl-days=7
closeauth.keys.rsa-public-key=classpath:keys/public.pem
closeauth.keys.rsa-private-key=classpath:keys/private.pem
closeauth.cors.allowed-origins=http://localhost:5173
closeauth.cors.allowed-methods=GET,POST,PUT,DELETE,PATCH,OPTIONS
closeauth.cors.allowed-headers=Authorization,X-User-Token,Content-Type
closeauth.cors.allow-credentials=true
```

---

## 8. Shared `ClientOwnershipVerifier` (DRY)

**Problem:** `verifyClientOwnership()` and `getCurrentUserId()` were duplicated across 4 service classes (~50 lines of repetition).

**Solution:** Extracted into a shared `@Component ClientOwnershipVerifier` with two methods:
- `verify(String clientId, HttpServletRequest request)` — throws if not owner
- `getUserId(HttpServletRequest request)` — extracts user ID from request attributes

**Files created:**
- `client/service/ClientOwnershipVerifier.java`

**Files changed (removed duplicated methods, injected verifier):**
- `ClientThemeService.java`
- `ApplicationRoleService.java`
- `ThemeConfigurationService.java`
- `ApplicationRegistrationConfigService.java`

---

## 9. Event-Driven Audit (Decoupling)

**Problem:** Services directly called `auditLogService.logAction(...)`, tightly coupling business logic to audit persistence.

**Solution:**
- Created `CloseAuthAuditEvent` record with convenience factories (`success()`, `failure()`)
- Added `@TransactionalEventListener(phase = AFTER_COMMIT)` handler in `AuditLogService`
- Services can now publish events via `ApplicationEventPublisher` — decoupled from persistence
- Existing direct `logAction()` calls still work (backward compatible)

**Files created:**
- `audit/event/CloseAuthAuditEvent.java`

**Files changed:**
- `AuditLogService.java` — added `handleAuditEvent()` listener

**Usage (services can now do either):**
```java
// Option A: Direct call (existing, still works)
auditLogService.logAction(clientId, userId, "ACTION", ip, ua, metadata);

// Option B: Event-driven (new, decoupled)
eventPublisher.publishEvent(CloseAuthAuditEvent.success(clientId, userId, "ACTION", ip, ua, metadata));
```

---

## 10. Clean Code Improvements

| Change | File | Before → After |
|--------|------|----------------|
| Fix control flow | `DefaultClientInitializer.java` | try-catch for existence check → null-check with early return |
| Remove verbose try-catch | `AuthController.java` | Caught exceptions manually → let `GlobalAdviceController` handle |
| Reduce log noise | `TwoLayerAuthenticationFilter.java` | `log.info(...)` on every request → `log.debug(...)` |
| Generic error messages | `AuthenticationService.validateUserData()` | "Username already exists: X" → "Registration failed. Please check your details." |
| Extracted email helper | `AuthenticationService.java` | Duplicated `whenComplete` callback → single `sendOtpEmail()` method |
| OTP validity from config | `OtpService.java` | `static final OTP_VALIDITY_SECONDS` → `properties.getOtp().getValiditySeconds()` |
| Added `OtpService` dependency | `ClientRegistrationService.java` | Used static constant → uses instance method |

---

## Summary of All Files

### Created (6 files)
| File | Purpose |
|------|---------|
| `common/config/properties/CloseAuthProperties.java` | Centralized configuration |
| `common/config/CorsConfig.java` | CORS configuration |
| `client/service/ClientOwnershipVerifier.java` | Shared ownership verification |
| `audit/event/CloseAuthAuditEvent.java` | Audit event record |
| `src/main/resources/keys/public.pem` | Persisted RSA public key |
| `src/main/resources/keys/private.pem` | Persisted RSA private key |

### Modified (13 files)
| File | Changes |
|------|---------|
| `AuthorisationServerConfig.java` | Persisted keys, CORS, properties, fixed permitAll |
| `AuthenticationService.java` | Account lockout, rate-limited resend, extracted email helper, generic errors |
| `OtpService.java` | Configurable values, constant-time comparison |
| `AuditLogService.java` | Event listener added |
| `AuthController.java` | Removed verbose try-catch |
| `DefaultClientInitializer.java` | Null-check instead of try-catch |
| `TwoLayerAuthenticationFilter.java` | Log levels reduced to debug |
| `ClientThemeService.java` | Uses `ClientOwnershipVerifier` |
| `ApplicationRoleService.java` | Uses `ClientOwnershipVerifier` |
| `ThemeConfigurationService.java` | Uses `ClientOwnershipVerifier` |
| `ApplicationRegistrationConfigService.java` | Uses `ClientOwnershipVerifier` |
| `ClientRegistrationService.java` | Added `OtpService` dependency |
| `OAuth2RegistrationService.java` | Uses instance method for OTP validity |

### Updated Config
| File | Changes |
|------|---------|
| `application.properties` | All `closeauth.*` properties added |

---

## Remaining (Optional, for later)

1. **Migrate audit calls to events** — Existing `auditLogService.logAction(...)` calls in services can be gradually replaced with `eventPublisher.publishEvent(CloseAuthAuditEvent.success(...))`. The listener is ready.
2. **`ClientService.java`** — Uses `getCurrentUserId()` without `HttpServletRequest` (different pattern from dual-auth services). Review separately.
3. **Add `resend_otp` to `RateLimitStrategyFactory`** — Ensure the factory has a strategy registered for the `"resend_otp"` action key.

