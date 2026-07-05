package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.LoginOutcome;
import com.anterka.closeauthbackend.auth.dto.LoginOutcome.FailureReason;
import com.anterka.closeauthbackend.common.security.TenantContext;
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

import java.util.UUID;

/**
 * The login <em>policy</em> flow (Stage 6a) — composed over 3b's enumeration-safe {@code verifyPassword} primitive.
 *
 * <p><b>Primitives are policy-free; flows apply policy</b> (the principle stated throughout). 3b's
 * {@link UserService#verifyPassword} answers only "are these credentials valid" (enumeration-safe, tenant-scoped,
 * no status gating beyond treating DELETED as absent). This flow answers "is this login <em>allowed right now</em>":
 * <ol>
 *   <li><b>Tenant status</b> — a non-{@code ACTIVE} tenant (suspended/provisioning/deleted) cannot log in.</li>
 *   <li><b>Credentials</b> — delegated to the primitive.</li>
 *   <li><b>User status</b> — only {@code ACTIVE} users complete login ({@code PENDING}/{@code SUSPENDED} refused;
 *       {@code DELETED} is already invisible to the primitive).</li>
 * </ol>
 *
 * <p><b>Enumeration-safe:</b> every failure returns the same {@link LoginOutcome#failure} to the caller; the specific
 * {@link FailureReason} is logged for audit only (Stage-8 seam) and never surfaced — the flow must not undo 3b's
 * enumeration-safety by leaking which factor failed.
 *
 * <p>Tenant-scoped: authentication runs only against the {@code context}'s tenant pool (the tenant resolved from the
 * request's {@code client_id}) — never globally. This is unit-testable in isolation (mocked {@code UserService} /
 * {@code TenantService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginPolicyService {

    private final TenantService tenantService;
    private final UserService userService;

    /**
     * Authenticates {@code email}/{@code rawPassword} against {@code context}'s tenant, applying login policy.
     * Never throws for an ordinary auth failure — returns a uniform {@link LoginOutcome}.
     */
    public LoginOutcome authenticate(TenantContext context, String email, String rawPassword) {
        // Policy 1: tenant must be ACTIVE (coarse, tenant-level — not user-enumerating).
        TenantStatus tenantStatus;
        try {
            TenantView tenant = tenantService.getTenantById(context.tenantId());
            tenantStatus = tenant.status();
        } catch (RuntimeException tenantMissing) {
            return audit(FailureReason.TENANT_NOT_ACTIVE, "tenant {} not resolvable", context.tenantId());
        }
        if (tenantStatus != TenantStatus.ACTIVE) {
            return audit(FailureReason.TENANT_NOT_ACTIVE, "tenant {} status={}", context.tenantId(), tenantStatus);
        }

        // Credentials: 3b's enumeration-safe primitive (tenant-scoped user pool).
        PasswordVerificationResult verification = userService.verifyPassword(context, email, rawPassword);
        if (!verification.success()) {
            return audit(FailureReason.INVALID_CREDENTIALS, "credential check failed for tenant {}", context.tenantId());
        }

        // Policy 3: only ACTIVE users may complete login.
        UserView user = verification.user();
        if (user.status() != UserStatus.ACTIVE) {
            return audit(FailureReason.USER_NOT_ACTIVE, "user {} status={}", user.id(), user.status());
        }

        // idp = the credential source actually used. 6a authenticates via the LOCAL_PASSWORD identity; when
        // federated identities arrive (Phase 2), the specific identity used becomes login-recorded here.
        // TODO(stage-8): emit a LOGIN_SUCCEEDED audit event via the audit outbox (§7.11).
        return LoginOutcome.success(user.id(), IdpType.LOCAL_PASSWORD);
    }

    /**
     * Credential-agnostic login policy: is login allowed for an already-authenticated user (e.g. via magic-link)?
     * Applies the same tenant-status + user-status gates as {@link #authenticate} without a password check. A
     * suspended tenant or a non-ACTIVE user cannot complete login by any credential path.
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
        return true;
    }

    private LoginOutcome audit(FailureReason reason, String message, Object... args) {
        // Server-side audit signal only; the caller receives a uniform, enumeration-safe failure.
        // TODO(stage-8): emit a LOGIN_FAILED audit event (reason={}) via the audit outbox (§7.11).
        log.info("Login refused (" + reason + "): " + message, args);
        return LoginOutcome.failure(reason);
    }
}
