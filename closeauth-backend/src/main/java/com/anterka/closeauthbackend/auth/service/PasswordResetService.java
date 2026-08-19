package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.IssueTokenCommand;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.RedisRateLimiter;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import com.anterka.closeauthbackend.notification.service.NotificationDeliveryException;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Password reset (Stage 6b-i) — composes the one-time-token primitive (high-entropy opaque {@code PASSWORD_RESET}
 * link) with 3b's {@code resetLocalPassword} and Stage 5's session revocation.
 *
 * <ul>
 *   <li><b>request</b> — <b>enumeration-safe</b>: the response is identical whether or not the email exists (never
 *       reveals account existence). If it exists, prior unused reset tokens are invalidated (single active reset
 *       token) and a fresh link is issued + emailed. Rate-limited.</li>
 *   <li><b>reset</b> — consume the token, set the new password, then the <b>post-reset cascade</b> (§13.4): a password
 *       change is a strong compromise signal, so ALL of the user's sessions are revoked — which via Stage 5 also
 *       revokes their refresh-token families and writes an access-token revocation marker. Every existing token dies.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    /** Uniform, enumeration-safe outcome for the reset step. */
    public enum ResetOutcome { RESET, INVALID }

    private final OneTimeTokenService oneTimeTokenService;
    private final RedisRateLimiter rateLimiter;
    private final AuthNotificationSender notifier;
    private final UserService userService;
    private final AuthServerSessionService sessionService;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    /**
     * Requests a reset link. Externally identical for existing / non-existing emails (no account enumeration).
     * {@code tenantSlug} (BE-B) is the tenant's public Tenant ID, resolved by the caller from {@code clientId} — used
     * only to namespace the emailed link's path; {@code null} degrades to the un-namespaced base URL rather than
     * emitting a broken link (the tenant existing at the point {@code clientId} was resolved is the only invariant
     * this depends on, so this should never actually happen in practice).
     */
    @Transactional
    public void requestReset(TenantContext context, String email, String clientId, String tenantSlug) {
        String target = normalize(email);
        CloseAuthProperties.OneTimeToken cfg = properties.getOneTimeToken();
        if (!rateLimiter.tryAcquire(issueKey(context, target), cfg.getIssuanceMaxPerWindow(), cfg.getIssuanceWindow())) {
            log.info("Password-reset issuance rate-limited for a target in tenant {}", context.tenantId());
            return;
        }
        if (!userService.existsByEmail(context, target)) {
            log.info("Password-reset requested for an unknown email in tenant {} (no-op, enumeration-safe)",
                    context.tenantId());
            return; // identical externally to the exists case — never reveal account existence
        }
        UserView user = userService.getUserByEmail(context, target);
        oneTimeTokenService.invalidateForTarget(OneTimeTokenPurpose.PASSWORD_RESET, context.tenantId(), target);
        RawOneTimeToken raw = oneTimeTokenService.issue(new IssueTokenCommand(
                OneTimeTokenPurpose.PASSWORD_RESET, context.tenantId(), user.id(), target, null,
                OneTimeTokenFormat.OPAQUE_LINK, cfg.getPasswordResetTtl()));
        try {
            notifier.sendPasswordResetLink(target, resetUrl(raw.rawSecret(), clientId, tenantSlug));
        } catch (NotificationDeliveryException deliveryFailure) {
            // Enumeration-safety: the response must be identical whether or not the account exists AND whether or not
            // SMTP is up. Swallow + log (recipient + event only, never the link) — an outage is an ops/log concern,
            // never a per-request signal an unauthenticated caller could use to enumerate accounts.
            log.warn("[notification] PASSWORD_RESET delivery failed to {} (link not logged); returning uniform response",
                    target);
        }
        auditEmitter.emit(AuditEvents.passwordResetRequested(context.tenantId(), user.id(), target));
    }

    /** Consumes a reset token, sets the new password, and revokes every existing session/token (post-reset cascade). */
    @Transactional
    public ResetOutcome resetPassword(TenantContext context, String rawToken, String newPassword) {
        ConsumeResult result = oneTimeTokenService.consume(rawToken, OneTimeTokenPurpose.PASSWORD_RESET, context.tenantId());
        if (!result.success()) {
            return ResetOutcome.INVALID; // generic — never reveals which check failed
        }
        UUID userId = result.userId();
        userService.resetLocalPassword(context, userId, newPassword);
        // Post-reset cascade (§13.4): a password change kills existing sessions + refresh families + access tokens.
        int revokedSessions = sessionService.revokeAllUserSessions(context.tenantId(), userId);
        log.info("Password reset for user {} in tenant {}; revoked {} session(s) + token cascade",
                userId, context.tenantId(), revokedSessions);
        auditEmitter.emit(AuditEvents.passwordChanged(context.tenantId(), userId, "PASSWORD_RESET"));
        auditEmitter.emit(AuditEvents.passwordResetCompleted(context.tenantId(), userId, revokedSessions));
        return ResetOutcome.RESET;
    }

    private String resetUrl(String rawSecret, String clientId, String tenantSlug) {
        // Front-end reset page (the user enters a new password there; the page POSTs token + password back here).
        // BE-B: tenant-namespaced under /t/{slug} (spec §2.2); tenantSlug == null degrades to the un-namespaced path.
        String base = properties.getBff().getBaseUrl() + (tenantSlug == null ? "" : "/t/" + tenantSlug);
        String url = base + "/reset-password?token=" + enc(rawSecret);
        return clientId == null ? url : url + "&client_id=" + enc(clientId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String issueKey(TenantContext context, String target) {
        return "rl:ott:issue:PASSWORD_RESET:" + context.tenantId() + ":" + target;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
