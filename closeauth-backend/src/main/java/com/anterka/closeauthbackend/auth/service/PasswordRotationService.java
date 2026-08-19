package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.IssueTokenCommand;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.LocalCredentialState;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Forced password rotation (Phase 2 of the tenant-onboarding design, {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md}
 * §2.2). Sibling to {@link PasswordResetService} — deliberately NOT built on it: {@code resetPassword} hardcodes the
 * {@code PASSWORD_RESET} purpose and has scenario-specific side effects (§1.13), and this scenario's on-ramp
 * ({@code LoginPolicyService.authenticate} returning {@link com.anterka.closeauthbackend.auth.dto.LoginOutcome#rotationRequired})
 * has no email/enumeration concern to protect (the caller already proved the password) and a resume target
 * ({@code authorize_query}) to carry that self-service reset never has.
 *
 * <ul>
 *   <li><b>{@link #beginRotation}</b> — the shared "invalidate any outstanding onboarding token, mint a fresh
 *       one, return its rotation-page URL" primitive, called from two on-ramps: {@code LoginController} on a
 *       proven-but-gated login (Phase 2), and {@code TenantOnboardingService.bootstrapFirstAdmin}/
 *       {@code reissueOnboardingCredential} at credential-issuance time (Phase 3) — the latter passes a
 *       {@code null authorizeQuery} (§2.2.2's on-ramp 2: there is no interrupted authorize request to resume) and
 *       the tenant's {@code admin-console-{slug}} client id, then emails the returned URL directly rather than
 *       redirecting to it. Mints via {@link OneTimeTokenService#issue} directly, NOT via
 *       {@link PasswordResetService#requestReset} (§1.8: that method shares one rate-limit bucket with genuine
 *       self-service resets, and hardcodes a different purpose) — first invalidating any outstanding token for this
 *       target (§2.3: exactly one live onboarding token per user).</li>
 *   <li><b>{@link #completeRotation}</b> — consumes the token, sets the real password AND clears both
 *       credential-lifecycle fields ({@link UserService#completeForcedRotation}), invalidates any sibling token,
 *       revokes every existing session (§2.2 finding: defensive by construction, but load-bearing if the flag was
 *       ever set against a user who already had a session — see the plan's Finding 1), then — and ONLY then —
 *       establishes the session via the unmodified {@link LoginSuccessResponder}, resuming the original authorize
 *       request exactly as an ordinary login would have.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordRotationService {

    /** Uniform, enumeration-safe outcome for the confirm step — mirrors {@link PasswordResetService.ResetOutcome}. */
    public enum RotationOutcome { ROTATED, INVALID }

    private final OneTimeTokenService oneTimeTokenService;
    private final UserService userService;
    private final AuthServerSessionService sessionService;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    /**
     * Invalidates any outstanding onboarding token for {@code email}, mints a fresh one, and returns the BFF
     * rotation-page URL carrying it — plus {@code client_id} and the (already percent-encoded) {@code authorizeQuery}
     * so the confirm step can resume the interrupted {@code /oauth2/authorize} request exactly.
     *
     * <p>Called from two on-ramps: {@code LoginController} on a proven-but-gated login (an
     * {@code authorizeQuery} to resume), and {@code TenantOnboardingService} at credential-issuance time
     * (Phase 3 bootstrap/reissue — no interrupted login, so {@code authorizeQuery} is {@code null} and the
     * caller emails the returned URL instead of redirecting to it). {@code tenantSlug} (BE-B) namespaces the
     * returned URL's path — {@code LoginController} resolves it from {@code clientId} via
     * {@link AuthFlowTenantResolver}; {@code TenantOnboardingService} already holds the tenant entity and passes
     * {@code tenant.getSlug()} directly. {@code null} degrades to the un-namespaced path.
     */
    @Transactional
    public String beginRotation(TenantContext context, UUID userId, String email, String clientId,
                                String authorizeQuery, String tenantSlug) {
        String target = normalize(email);
        CloseAuthProperties.OneTimeToken cfg = properties.getOneTimeToken();
        oneTimeTokenService.invalidateForTarget(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING, context.tenantId(), target);
        RawOneTimeToken raw = oneTimeTokenService.issue(new IssueTokenCommand(
                OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING, context.tenantId(), userId, target, null,
                OneTimeTokenFormat.OPAQUE_LINK, cfg.getTenantAdminOnboardingTtl()));
        log.info("Password rotation onboarding token issued for user {} in tenant {}", userId, context.tenantId());
        return rotationPageUrl(raw.rawSecret(), clientId, authorizeQuery, tenantSlug);
    }

    /**
     * Consumes a {@code TENANT_ADMIN_ONBOARDING} token, sets the new password, clears the rotation flag, revokes
     * every existing session, and returns the resume URL from the unmodified {@link LoginSuccessResponder} — the
     * ONLY point in this flow where a session is actually established.
     */
    @Transactional
    public RotationResult completeRotation(TenantContext context, String rawToken, String newPassword) {
        ConsumeResult result = oneTimeTokenService.consume(rawToken, OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING,
                context.tenantId());
        if (!result.success()) {
            return RotationResult.invalid();
        }
        UUID userId = result.userId();

        // Token binding, re-confirmed here: userId comes ONLY from the consumed token, never from a request
        // parameter — the request carries no user identifier of its own. A stale token (rotation already completed
        // by another request, e.g. a race, or the flag was cleared some other way) must not silently re-apply.
        Optional<LocalCredentialState> state = userService.getLocalCredentialState(context, userId);
        if (state.isEmpty() || !state.get().mustChangePassword()) {
            log.info("Password-rotation confirm rejected: user {} tenant {} has no pending rotation (stale token)",
                    userId, context.tenantId());
            return RotationResult.invalid();
        }

        userService.completeForcedRotation(context, userId, newPassword);
        // Kill any sibling onboarding token (e.g. the emailed link, if this request came from the temp-password
        // on-ramp) — mirrors PasswordResetService's single-active-token discipline.
        oneTimeTokenService.invalidateForTarget(OneTimeTokenPurpose.TENANT_ADMIN_ONBOARDING, context.tenantId(),
                result.target());

        // By construction a pending-rotation user has no session (LoginPolicyService never establishes one for
        // ROTATION_REQUIRED, and isLoginAllowed refuses magic-link the same way) — but revoke anyway, both to match
        // PasswordResetService's documented invariant that a credential change kills every session/refresh
        // family/access token, and to close the gap defensively regardless of how must_change_password came to be
        // set (see the plan's Finding 1: a future issuance path against an EXISTING user, e.g. phase 3's reissue
        // endpoint, could leave a live session behind if it forgets to revoke at set time).
        int revokedSessions = sessionService.revokeAllUserSessions(context.tenantId(), userId);
        log.info("Password rotation completed for user {} in tenant {}; revoked {} session(s) + token cascade",
                userId, context.tenantId(), revokedSessions);
        auditEmitter.emit(AuditEvents.passwordChanged(context.tenantId(), userId, "FORCED_ROTATION"));
        auditEmitter.emit(AuditEvents.passwordResetCompleted(context.tenantId(), userId, revokedSessions));
        return RotationResult.rotated(userId);
    }

    /** Outcome of {@link #completeRotation} — carries the userId on success so the caller can establish the session. */
    public record RotationResult(RotationOutcome outcome, UUID userId) {
        static RotationResult rotated(UUID userId) {
            return new RotationResult(RotationOutcome.ROTATED, userId);
        }

        static RotationResult invalid() {
            return new RotationResult(RotationOutcome.INVALID, null);
        }
    }

    private String rotationPageUrl(String rawSecret, String clientId, String authorizeQuery, String tenantSlug) {
        // {bff.baseUrl}/t/{slug}/password-rotation — the SPA-facing rotation page (BE-B: tenant-namespaced per spec
        // §2.2; tenantSlug == null degrades to the un-namespaced path). authorizeQuery is handed in ALREADY
        // percent-encoded (built by LoginController.buildAuthorizeQuery, unchanged) — enc() here encodes it ONCE
        // MORE as a single opaque query parameter value; never re-encode on the way back out (see completeRotation /
        // LoginSuccessResponder).
        StringBuilder url = new StringBuilder(properties.getBff().getBaseUrl());
        if (tenantSlug != null) {
            url.append("/t/").append(tenantSlug);
        }
        url.append("/password-rotation?token=").append(enc(rawSecret));
        if (clientId != null) {
            url.append("&client_id=").append(enc(clientId));
        }
        if (authorizeQuery != null && !authorizeQuery.isBlank()) {
            url.append("&authorize_query=").append(enc(authorizeQuery));
        }
        return url.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
