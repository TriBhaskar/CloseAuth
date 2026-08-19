package com.anterka.closeauthbackend.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * The real SMTP {@link AuthNotificationSender} — actually sends the one-time secrets to users via the Boot-configured
 * {@link JavaMailSender} ({@code spring.mail.*}), replacing the log-only placeholder. Plain-text messages matching what
 * the flows describe; no templating/HTML (kept intentionally minimal, per the notification module's scope).
 *
 * <p><b>Active by default</b> ({@code matchIfMissing = true}). Set {@code closeauth.notification.email.transport=logging}
 * to fall back to {@link LoggingAuthNotificationSender} (local dev without a real relay; the backend's own test suite
 * defaults to {@code logging} via {@code src/test/resources/application.properties}). Tests that need to capture the
 * delivered secret substitute a {@code @Primary AuthNotificationSender} double, which overrides this bean regardless.
 *
 * <h2>Secret-logging discipline (must not regress)</h2>
 * The code/link is the secret and is carried ONLY in the email body — it is <b>never</b> logged. Success logs the
 * recipient + event type; a delivery failure logs the recipient + event type + transport error (never the body) and is
 * surfaced as a {@link NotificationDeliveryException} so the caller can handle it. Delivery is synchronous, on the
 * caller's thread — the same semantics the logging placeholder had (callers are unchanged).
 */
@Component
@ConditionalOnProperty(prefix = "closeauth.notification.email", name = "transport", havingValue = "smtp",
        matchIfMissing = true)
@Slf4j
public class SmtpAuthNotificationSender implements AuthNotificationSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpAuthNotificationSender(
            JavaMailSender mailSender,
            @Value("${closeauth.notification.email.from:no-reply@closeauth.local}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendEmailVerificationCode(String target, String code, String verifyUrl) {
        send(target, "EMAIL_VERIFICATION", "Verify your email",
                "Click the link below to verify your email address:\n\n" + verifyUrl
                        + "\n\nOr enter this code manually:\n\n" + code
                        + "\n\nThis will expire shortly.\n\n"
                        + "If you did not request this, you can ignore this email.");
    }

    @Override
    public void sendMagicLink(String target, String magicLinkUrl) {
        send(target, "MAGIC_LINK", "Your CloseAuth sign-in link",
                "Click the link below to sign in to CloseAuth:\n\n" + magicLinkUrl
                        + "\n\nThis link will expire shortly and can be used only once.\n\n"
                        + "If you did not request this, you can ignore this email.");
    }

    @Override
    public void sendPasswordResetLink(String target, String resetUrl) {
        send(target, "PASSWORD_RESET", "Reset your CloseAuth password",
                "We received a request to reset your CloseAuth password. Click the link below to choose a new one:\n\n"
                        + resetUrl + "\n\nThis link will expire shortly and can be used only once.\n\n"
                        + "If you did not request this, you can ignore this email — your password will not change.");
    }

    @Override
    public void sendInviteLink(String target, String inviteUrl) {
        send(target, "INVITE", "You have been invited to CloseAuth",
                "You have been invited to create a CloseAuth account. Click the link below to get started:\n\n"
                        + inviteUrl + "\n\nThis invitation will expire and can be used only once.");
    }

    @Override
    public void sendTenantAdminOnboardingLink(String target, String onboardingUrl, String tenantName) {
        send(target, "TENANT_ADMIN_ONBOARDING", "You're set up as an administrator for " + tenantName + " on CloseAuth",
                "A CloseAuth platform administrator has set you up as an administrator for " + tenantName
                        + ". Click the link below to set your own password and sign in:\n\n" + onboardingUrl
                        + "\n\nThis link will expire and can be used only once.\n\n"
                        + "If you were not expecting this, contact your platform administrator.");
    }

    /** Sends one plain-text message. The body carries the secret and is NEVER logged; only recipient + event are. */
    private void send(String to, String eventType, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            log.info("[notification] {} email sent to {} (contents not logged)", eventType, to);
        } catch (MailException failure) {
            // Log recipient + event + transport error ONLY — the body (with the secret) is never logged.
            log.error("[notification] {} email delivery to {} FAILED: {}: {}", eventType, to,
                    failure.getClass().getSimpleName(), failure.getMessage());
            throw new NotificationDeliveryException(eventType, to, failure);
        }
    }
}
