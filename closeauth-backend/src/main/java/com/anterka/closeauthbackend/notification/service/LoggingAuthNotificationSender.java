package com.anterka.closeauthbackend.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Log-only {@link AuthNotificationSender}: records that a delivery happened (purpose + target) but NEVER logs the
 * code/link — the raw secret must not appear in logs (the whole point of hash-at-rest storage). Useful for local dev
 * without a real SMTP relay, and the default for the backend's own test suite.
 *
 * <p><b>Opt-in.</b> {@link SmtpAuthNotificationSender} (real delivery) is the production default; this sender is
 * selected only when {@code closeauth.notification.email.transport=logging}. A test may still substitute a
 * {@code @Primary} capturing sender to assert the delivered secret, which overrides whichever transport is active.
 */
@Component
@ConditionalOnProperty(prefix = "closeauth.notification.email", name = "transport", havingValue = "logging")
@Slf4j
public class LoggingAuthNotificationSender implements AuthNotificationSender {

    @Override
    public void sendEmailVerificationCode(String target, String code) {
        log.info("[notification] email-verification code delivered to {} (code not logged)", target);
    }

    @Override
    public void sendMagicLink(String target, String magicLinkUrl) {
        log.info("[notification] magic-link delivered to {} (link not logged)", target);
    }

    @Override
    public void sendPasswordResetLink(String target, String resetUrl) {
        log.info("[notification] password-reset link delivered to {} (link not logged)", target);
    }

    @Override
    public void sendInviteLink(String target, String inviteUrl) {
        log.info("[notification] invite link delivered to {} (link not logged)", target);
    }

    @Override
    public void sendTenantAdminOnboardingLink(String target, String onboardingUrl, String tenantName) {
        log.info("[notification] tenant-admin onboarding link delivered to {} for tenant {} (link not logged)",
                target, tenantName);
    }
}
