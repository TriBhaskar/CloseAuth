package com.anterka.closeauthbackend.notification.service;

/**
 * Out-of-band delivery of one-time secrets for the identity flows (Stage 6b-i). The port lives in the notification
 * module (which owns "sending things to users"); the auth flows call it. Methods are purpose-specific so a real
 * implementation can template each email; the raw code/link is the secret and MUST NOT be logged.
 *
 * <p>Stage 6b-i ships a logging placeholder ({@code LoggingAuthNotificationSender}); wiring a real SMTP/templated
 * {@code EmailService} (Spring {@code JavaMailSender}, {@code spring.mail.*} is already configured) is a documented
 * notification-module build-out. Tests substitute a capturing implementation.
 */
public interface AuthNotificationSender {

    /** Delivers a short numeric email-verification code. */
    void sendEmailVerificationCode(String target, String code);

    /** Delivers a magic-link login URL (already containing the opaque token). */
    void sendMagicLink(String target, String magicLinkUrl);

    /** Delivers a password-reset URL (already containing the opaque token). */
    void sendPasswordResetLink(String target, String resetUrl);

    /** Delivers an invitation URL (already containing the opaque invite token). */
    void sendInviteLink(String target, String inviteUrl);
}
