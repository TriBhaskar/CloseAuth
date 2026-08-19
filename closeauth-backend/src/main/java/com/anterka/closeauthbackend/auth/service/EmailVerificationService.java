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
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import com.anterka.closeauthbackend.notification.service.NotificationDeliveryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Email / OTP verification flow (Stage 6b-i) — composes the one-time-token primitive with a <b>numeric code</b>. Since
 * a 6-digit code is low-entropy, this flow supplies the mandatory pairing: <b>strict issuance rate-limit</b> +
 * <b>per-target consume attempt-lockout</b> (a code is brute-forceable in ~1M tries otherwise). On successful
 * verification it composes 3b's {@code markEmailVerified} and activates a PENDING user.
 *
 * <p><b>FE-2d (spec §6.2.4):</b> the emailed code now also travels as a clickable link (the SAME code, not a second
 * secret — see {@link #verifyLinkUrl}) so the frontend can auto-consume it. {@link VerificationOutcome} also gains a
 * narrow, deliberate exception to the one-time-token primitive's blanket "never distinguish why a consume failed"
 * rule: {@code EXPIRED} and {@code ALREADY_USED} are surfaced for THIS flow only. Safe here because the code is
 * rate-limited (see {@code verify-max-attempts-per-window}) to a handful of guesses against a KNOWN target — brute
 * -forcing to discover which of these applies is already impractical regardless of the distinction — and "already
 * used" genuinely needs to read as success (spec: re-clicking an old email link is normal, not an error). Every
 * OTHER failure reason ({@code NOT_FOUND}/{@code WRONG_PURPOSE}/{@code WRONG_TENANT}) still collapses into the
 * existing generic {@code INVALID} — this is not a general relaxation of the primitive's rule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    public enum VerificationOutcome { VERIFIED, ALREADY_USED, EXPIRED, INVALID, RATE_LIMITED }

    private final OneTimeTokenService oneTimeTokenService;
    private final RedisRateLimiter rateLimiter;
    private final AuthNotificationSender notifier;
    private final UserService userService;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    /**
     * Issues (and delivers) a fresh email-verification code, invalidating any prior unused one. Rate-limited.
     * {@code clientId}/{@code tenantSlug} (FE-2d) are used only to build the emailed link's URL — {@code tenantSlug}
     * degrades to the un-namespaced path when null (BE-B convention), {@code clientId} is appended only when present.
     */
    @Transactional
    public void requestVerification(TenantContext context, UUID userId, String email, String clientId, String tenantSlug) {
        String target = normalize(email);
        CloseAuthProperties.OneTimeToken cfg = properties.getOneTimeToken();
        if (!rateLimiter.tryAcquire(issueKey(context, target), cfg.getIssuanceMaxPerWindow(), cfg.getIssuanceWindow())) {
            log.info("Email-verification issuance rate-limited for a target in tenant {}", context.tenantId());
            return; // silently drop over the limit (no leak, no email-bomb)
        }
        oneTimeTokenService.invalidateForTarget(OneTimeTokenPurpose.EMAIL_VERIFICATION, context.tenantId(), target);
        RawOneTimeToken raw = oneTimeTokenService.issue(new IssueTokenCommand(
                OneTimeTokenPurpose.EMAIL_VERIFICATION, context.tenantId(), userId, target, null,
                OneTimeTokenFormat.NUMERIC_CODE, cfg.getEmailVerificationTtl()));
        try {
            notifier.sendEmailVerificationCode(target, raw.rawSecret(),
                    verifyLinkUrl(raw.rawSecret(), target, clientId, tenantSlug));
        } catch (NotificationDeliveryException deliveryFailure) {
            // Enumeration-safety: the response must be identical whether or not the account exists AND whether or not
            // SMTP is up. Swallow + log (recipient + event only, never the code) — an outage is an ops/log concern,
            // never a per-request signal an unauthenticated caller could use to enumerate accounts.
            log.warn("[notification] EMAIL_VERIFICATION delivery failed to {} (code not logged); "
                    + "returning uniform response", target);
        }
        auditEmitter.emit(AuditEvents.emailVerificationIssued(context.tenantId(), userId, target));
    }

    /**
     * Verifies a code: the per-target attempt-lockout (low-entropy pairing) runs first, then the primitive's
     * single-use consume, then — on success — {@code markEmailVerified} + activation of a PENDING user.
     */
    @Transactional
    public VerificationOutcome verify(TenantContext context, String email, String code) {
        String target = normalize(email);
        CloseAuthProperties.OneTimeToken cfg = properties.getOneTimeToken();
        if (!rateLimiter.tryAcquire(verifyKey(context, target), cfg.getVerifyMaxAttemptsPerWindow(),
                cfg.getVerifyAttemptWindow())) {
            log.info("Email-verification attempts locked for a target in tenant {}", context.tenantId());
            return VerificationOutcome.RATE_LIMITED; // brute-force lockout for the low-entropy code
        }
        ConsumeResult result = oneTimeTokenService.consumeCode(
                code, OneTimeTokenPurpose.EMAIL_VERIFICATION, context.tenantId(), target);
        if (!result.success()) {
            // See class javadoc — EXPIRED/ALREADY_USED are the one documented exception to the primitive's blanket
            // collapse rule. A failed ConsumeResult carries no userId (nothing to act on), so ALREADY_USED is
            // returned as-is, with no attempt to re-verify/re-activate anything — there is nothing here to redo.
            return switch (result.reason()) {
                case EXPIRED -> VerificationOutcome.EXPIRED;
                case ALREADY_USED -> VerificationOutcome.ALREADY_USED;
                default -> VerificationOutcome.INVALID; // NOT_FOUND / WRONG_PURPOSE / WRONG_TENANT — still generic
            };
        }
        // Act on the token's own subject (authoritative), not the presented email.
        UUID userId = result.userId();
        UserView user = userService.getUserById(context, userId);
        userService.markEmailVerified(context, userId);
        if (user.status() == UserStatus.PENDING) {
            userService.activateUser(context, userId); // email-verified mode gates activation on this
        }
        auditEmitter.emit(AuditEvents.emailVerified(context.tenantId(), userId));
        return VerificationOutcome.VERIFIED;
    }

    /** FE-2d: the SAME code as a clickable link (spec §6.2.4's "link" entry) — see BE-B's tenant-namespacing convention. */
    private String verifyLinkUrl(String code, String email, String clientId, String tenantSlug) {
        String base = properties.getBff().getBaseUrl() + (tenantSlug == null ? "" : "/t/" + tenantSlug);
        String url = base + "/verify-email?code=" + enc(code) + "&email=" + enc(email);
        return clientId == null ? url : url + "&client_id=" + enc(clientId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String issueKey(TenantContext context, String target) {
        return "rl:ott:issue:EMAIL_VERIFICATION:" + context.tenantId() + ":" + target;
    }

    private String verifyKey(TenantContext context, String target) {
        return "rl:ott:verify:EMAIL_VERIFICATION:" + context.tenantId() + ":" + target;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
