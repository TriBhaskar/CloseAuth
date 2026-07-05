package com.anterka.closeauthbackend.auth.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Email / OTP verification flow (Stage 6b-i) — composes the one-time-token primitive with a <b>numeric code</b>. Since
 * a 6-digit code is low-entropy, this flow supplies the mandatory pairing: <b>strict issuance rate-limit</b> +
 * <b>per-target consume attempt-lockout</b> (a code is brute-forceable in ~1M tries otherwise). On successful
 * verification it composes 3b's {@code markEmailVerified} and activates a PENDING user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    /** Uniform, enumeration-safe outcome (the flow never distinguishes not-found / expired / used to the caller). */
    public enum VerificationOutcome { VERIFIED, INVALID, RATE_LIMITED }

    private final OneTimeTokenService oneTimeTokenService;
    private final RedisRateLimiter rateLimiter;
    private final AuthNotificationSender notifier;
    private final UserService userService;
    private final CloseAuthProperties properties;

    /** Issues (and delivers) a fresh email-verification code, invalidating any prior unused one. Rate-limited. */
    @Transactional
    public void requestVerification(TenantContext context, UUID userId, String email) {
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
        notifier.sendEmailVerificationCode(target, raw.rawSecret());
        // TODO(stage-8): emit EMAIL_VERIFICATION_ISSUED audit event via the audit outbox (§7.11).
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
            return VerificationOutcome.INVALID; // generic — never reveals which check failed
        }
        // Act on the token's own subject (authoritative), not the presented email.
        UUID userId = result.userId();
        UserView user = userService.getUserById(context, userId);
        userService.markEmailVerified(context, userId);
        if (user.status() == UserStatus.PENDING) {
            userService.activateUser(context, userId); // email-verified mode gates activation on this
        }
        // TODO(stage-8): emit EMAIL_VERIFIED audit event via the audit outbox (§7.11).
        return VerificationOutcome.VERIFIED;
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
