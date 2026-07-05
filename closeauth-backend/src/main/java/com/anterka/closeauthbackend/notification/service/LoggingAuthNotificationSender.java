package com.anterka.closeauthbackend.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Placeholder {@link AuthNotificationSender} for Stage 6b-i: records that a delivery happened (purpose + target) but
 * NEVER logs the code/link — the raw secret must not appear in logs (the whole point of hash-at-rest storage). A real
 * templated {@code EmailService} (via {@code JavaMailSender}) replaces this; a test supplies a {@code @Primary}
 * capturing sender to assert the delivered secret without touching callers.
 *
 * <p>TODO(notification): implement SMTP delivery with per-purpose templates in the notification module.
 */
@Component
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
}
