package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.dto.LoginOutcome;
import com.anterka.closeauthbackend.auth.dto.LoginOutcome.FailureReason;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.RedisRateLimiter;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.LocalCredentialState;
import com.anterka.closeauthbackend.identity.dto.PasswordVerificationResult;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.IdpType;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The login <em>policy</em> flow (Stage 6a) — composed over 3b's enumeration-safe {@code verifyPassword} primitive.
 *
 * <p><b>Primitives are policy-free; flows apply policy</b> (the principle stated throughout). 3b's
 * {@link UserService#verifyPassword} answers only "are these credentials valid" (enumeration-safe, tenant-scoped,
 * no status gating beyond treating DELETED as absent). This flow answers "is this login <em>allowed right now</em>":
 * <ol>
 *   <li><b>Rate limit</b> — a per-tenant, per-account bucket ({@link CloseAuthProperties.Security#getMaxLoginAttempts()}
 *       failures per {@link CloseAuthProperties.Security#getLockoutDurationMinutes()}-minute window) is checked
 *       <em>before</em> any tenant lookup or password hashing, so an exhausted budget costs nothing to enforce.
 *       Only failed attempts (this policy's own refusals) consume budget — a successful login records nothing,
 *       so a legitimate user who logs in repeatedly never spends attempts they didn't need to.</li>
 *   <li><b>Tenant status</b> — a non-{@code ACTIVE} tenant (suspended/provisioning/deleted) cannot log in.</li>
 *   <li><b>Credentials</b> — delegated to the primitive.</li>
 *   <li><b>User status</b> — only {@code ACTIVE} users complete login ({@code PENDING}/{@code SUSPENDED} refused;
 *       {@code DELETED} is already invisible to the primitive).</li>
 *   <li><b>Credential-lifecycle gate</b> (Phase 2 of the tenant-onboarding design, §2.2/§2.3) — runs only AFTER the
 *       password has verified, so it can never be probed without knowing the credential. A system-generated temp
 *       credential past {@code temp_credential_expires_at} folds into the uniform failure (audit-only distinction,
 *       {@link FailureReason#TEMP_CREDENTIAL_EXPIRED}) and consumes rate-limit budget exactly like a bad password —
 *       otherwise an attacker could distinguish "expired" from "wrong" by watching the budget drain differently.
 *       {@code must_change_password = true} on a live (non-expired) credential returns the distinct
 *       {@link LoginOutcome#rotationRequired}: the caller proved they know the password, but no session may be
 *       issued until they rotate it — this does NOT consume rate-limit budget (a proven credential leaks nothing,
 *       and charging it would let a legitimate admin's own retries lock themselves out).</li>
 * </ol>
 *
 * <p>{@link #isLoginAllowed} (the magic-link path, which has no password to verify) shares the exact same
 * credential-lifecycle evaluation via {@link #evaluateCredentialLifecycle} — a pending-rotation or expired-temp-
 * credential user cannot obtain a session by magic-link either, closing what would otherwise be a second,
 * ungated entry point into the same account.</p>
 *
 * <p><b>Enumeration-safe:</b> every failure returns the same {@link LoginOutcome#failure} to the caller; the specific
 * {@link FailureReason} is logged for audit only (Stage-8 seam) and never surfaced — the flow must not undo 3b's
 * enumeration-safety by leaking which factor failed. This applies to the rate limit too: a throttled attempt is
 * indistinguishable from a bad password to the caller.
 *
 * <p><b>Accepted tradeoff — the limiter is account-keyed (tenant + email), not IP-keyed.</b> Anyone who knows a
 * target's email can exhaust that account's login budget for the window, denying the legitimate owner password
 * login until it resets. Chosen deliberately over IP-keying: this policy exists primarily to bound brute-force
 * exposure on a <em>known-to-exist</em> account with a possibly system-generated credential (e.g. a freshly
 * provisioned tenant admin's temporary password) — a targeted attacker is the realistic threat there, and an
 * IP-keyed limiter alone is materially weaker against it (distributed sources, one target). A supplementary
 * IP-keyed limiter is a reasonable future addition; it is not built here.
 *
 * <p>Tenant-scoped: authentication runs only against the {@code context}'s tenant pool (the tenant resolved from the
 * request's {@code client_id}) — never globally. This is unit-testable in isolation (mocked {@code UserService} /
 * {@code TenantService} / {@code RedisRateLimiter}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginPolicyService {

    private final TenantService tenantService;
    private final UserService userService;
    private final AuditEmitter auditEmitter;
    private final RedisRateLimiter rateLimiter;
    private final CloseAuthProperties properties;

    /**
     * Authenticates {@code email}/{@code rawPassword} against {@code context}'s tenant, applying login policy.
     * Never throws for an ordinary auth failure — returns a uniform {@link LoginOutcome}.
     */
    public LoginOutcome authenticate(TenantContext context, String email, String rawPassword) {
        // Policy 0: per-account rate limit — checked first, before any tenant lookup or password hashing, so an
        // exhausted budget is cheap to enforce. Read-only gate (does not itself record a hit); each failure branch
        // below records via auditFailedAttempt, so only real failures — never a success — consume budget.
        CloseAuthProperties.Security security = properties.getSecurity();
        if (rateLimiter.isLimited(loginKey(context.tenantId(), email), security.getMaxLoginAttempts())) {
            return audit(context.tenantId(), FailureReason.RATE_LIMITED,
                    "login attempts rate-limited for a target in tenant {}", context.tenantId());
        }

        // Policy 1: tenant must be ACTIVE (coarse, tenant-level — not user-enumerating).
        TenantStatus tenantStatus;
        try {
            TenantView tenant = tenantService.getTenantById(context.tenantId());
            tenantStatus = tenant.status();
        } catch (RuntimeException tenantMissing) {
            return auditFailedAttempt(context.tenantId(), email, FailureReason.TENANT_NOT_ACTIVE,
                    "tenant {} not resolvable", context.tenantId());
        }
        if (tenantStatus != TenantStatus.ACTIVE) {
            return auditFailedAttempt(context.tenantId(), email, FailureReason.TENANT_NOT_ACTIVE,
                    "tenant {} status={}", context.tenantId(), tenantStatus);
        }

        // Credentials: 3b's enumeration-safe primitive (tenant-scoped user pool).
        PasswordVerificationResult verification = userService.verifyPassword(context, email, rawPassword);
        if (!verification.success()) {
            return auditFailedAttempt(context.tenantId(), email, FailureReason.INVALID_CREDENTIALS,
                    "credential check failed for tenant {}", context.tenantId());
        }

        // Policy 3: only ACTIVE users may complete login.
        UserView user = verification.user();
        if (user.status() != UserStatus.ACTIVE) {
            return auditFailedAttempt(context.tenantId(), email, FailureReason.USER_NOT_ACTIVE,
                    "user {} status={}", user.id(), user.status());
        }

        // Policy 4/5: credential-lifecycle gate — runs only now, AFTER the password has verified (§2.2's
        // load-bearing placement: probing this state must require knowing the password).
        CredentialGate gate = evaluateCredentialLifecycle(context, user.id());
        if (gate == CredentialGate.TEMP_CREDENTIAL_EXPIRED) {
            // Decision 12: folds into the SAME uniform failure a bad password gets — audit-only distinction — and
            // consumes rate-limit budget exactly like INVALID_CREDENTIALS, so attempt-counting can't distinguish
            // "correct but expired" from "wrong" (that would reopen the oracle the uniform 401 exists to close).
            return auditFailedAttempt(context.tenantId(), email, FailureReason.TEMP_CREDENTIAL_EXPIRED,
                    "user {} temp credential expired", user.id());
        }
        if (gate == CredentialGate.ROTATION_REQUIRED) {
            // A legitimate third outcome: the caller proved the password, but no session may be issued until they
            // rotate it. Deliberately does NOT call auditFailedAttempt — this is a proven credential, not a refusal,
            // so it must not consume the account's own rate-limit budget.
            log.info("Login gated (ROTATION_REQUIRED): user {} tenant {} must rotate temp credential",
                    user.id(), context.tenantId());
            return LoginOutcome.rotationRequired(user.id());
        }

        // idp = the credential source actually used. 6a authenticates via the LOCAL_PASSWORD identity; when
        // federated identities arrive (Phase 2), the specific identity used becomes login-recorded here.
        auditEmitter.emit(AuditEvents.loginSuccess(context.tenantId(), user.id(), null,
                IdpType.LOCAL_PASSWORD.name(), null));
        return LoginOutcome.success(user.id(), IdpType.LOCAL_PASSWORD);
    }

    /**
     * Credential-agnostic login policy: is login allowed for an already-authenticated user (e.g. via magic-link)?
     * Applies the same tenant-status + user-status + credential-lifecycle gates as {@link #authenticate} without a
     * password check. A suspended tenant, a non-ACTIVE user, a pending forced-rotation, or an expired temp
     * credential all refuse login by this path too — magic-link cannot be used to bypass any gate password login
     * enforces.
     */
    public boolean isLoginAllowed(UUID tenantId, UUID userId) {
        TenantStatus tenantStatus;
        try {
            tenantStatus = tenantService.getTenantById(tenantId).status();
        } catch (RuntimeException tenantMissing) {
            log.info("Login refused (TENANT_NOT_ACTIVE): tenant {} not resolvable", tenantId);
            return false;
        }
        if (tenantStatus != TenantStatus.ACTIVE) {
            log.info("Login refused (TENANT_NOT_ACTIVE): tenant {} status={}", tenantId, tenantStatus);
            return false;
        }
        UserStatus userStatus;
        try {
            userStatus = userService.getUserById(TenantContext.of(tenantId), userId).status();
        } catch (RuntimeException userMissing) {
            log.info("Login refused (USER_NOT_ACTIVE): user {} not resolvable in tenant {}", userId, tenantId);
            return false;
        }
        if (userStatus != UserStatus.ACTIVE) {
            log.info("Login refused (USER_NOT_ACTIVE): user {} status={}", userId, userStatus);
            return false;
        }
        CredentialGate gate = evaluateCredentialLifecycle(TenantContext.of(tenantId), userId);
        if (gate != CredentialGate.OK) {
            // Magic-link has no password to rotate away from — both non-OK states refuse rather than route into the
            // rotation flow (§2.2's decision: identity-proven-but-refused is already this method's whole category).
            log.info("Login refused ({}): user {} tenant {}", gate, userId, tenantId);
            return false;
        }
        return true;
    }

    /**
     * The shared credential-lifecycle evaluation both {@link #authenticate} and {@link #isLoginAllowed} apply — the
     * single implementation Phase 2 requires so the two paths cannot drift (see class javadoc). No local password →
     * {@link CredentialGate#OK} (nothing to gate; the password path would already have failed before this runs).
     */
    private CredentialGate evaluateCredentialLifecycle(TenantContext context, UUID userId) {
        Optional<LocalCredentialState> state = userService.getLocalCredentialState(context, userId);
        if (state.isEmpty()) {
            return CredentialGate.OK;
        }
        Instant expiresAt = state.get().tempCredentialExpiresAt();
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            return CredentialGate.TEMP_CREDENTIAL_EXPIRED;
        }
        if (state.get().mustChangePassword()) {
            return CredentialGate.ROTATION_REQUIRED;
        }
        return CredentialGate.OK;
    }

    /** Outcome of {@link #evaluateCredentialLifecycle} — internal to this service; each caller maps it differently. */
    private enum CredentialGate {
        OK,
        TEMP_CREDENTIAL_EXPIRED,
        ROTATION_REQUIRED
    }

    private LoginOutcome audit(UUID tenantId, FailureReason reason, String message, Object... args) {
        // Server-side audit signal only; the caller receives a uniform, enumeration-safe failure (the specific
        // FailureReason is recorded here for audit but never surfaced — preserving 3b's enumeration-safety).
        log.info("Login refused (" + reason + "): " + message, args);
        auditEmitter.emit(AuditEvents.loginFailure(tenantId, reason.name()));
        return LoginOutcome.failure(reason);
    }

    /**
     * A real login failure: records one hit against the account's rate-limit bucket (via {@link RedisRateLimiter#tryAcquire})
     * before delegating to {@link #audit}. The rate-limit refusal itself ({@link FailureReason#RATE_LIMITED}) does
     * NOT go through here — it must not re-record a hit against a bucket it just found already exhausted.
     */
    private LoginOutcome auditFailedAttempt(UUID tenantId, String email, FailureReason reason, String message,
                                             Object... args) {
        CloseAuthProperties.Security security = properties.getSecurity();
        rateLimiter.tryAcquire(loginKey(tenantId, email), security.getMaxLoginAttempts(),
                Duration.ofMinutes(security.getLockoutDurationMinutes()));
        return audit(tenantId, reason, message, args);
    }

    /**
     * Per-account login-attempt bucket key. Normalization MUST match {@code UserService}'s own email normalization
     * (trim + lowercase) — keying on the raw, un-normalized parameter would let {@code Alice@X.com},
     * {@code alice@x.com}, and {@code ALICE@X.COM} land in separate buckets against the same account, trivially
     * defeating the limiter.
     */
    private String loginKey(UUID tenantId, String email) {
        return "rl:login:password:" + tenantId + ":" + normalize(email);
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
