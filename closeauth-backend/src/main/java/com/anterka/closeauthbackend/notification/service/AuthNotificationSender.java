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

    /**
     * Delivers a tenant-admin onboarding URL (Phase 3 of the tenant-onboarding design, §2.4/§2.9) — the
     * {@code TENANT_ADMIN_ONBOARDING} rotation-page link returned by
     * {@code PasswordRotationService.beginRotation}, sent when a platform admin bootstraps or reissues a
     * tenant's first admin's temporary credential. Distinct from {@link #sendPasswordResetLink}: a different
     * scenario (system-generated, admin-initiated, not self-requested) with its own copy.
     *
     * <p><b>Must NOT carry the temporary password</b> — only the link. The password is shown exactly once, in
     * the API response to the platform admin who triggered issuance; the email is purely the recipient's own
     * on-ramp to set their real password.
     */
    void sendTenantAdminOnboardingLink(String target, String onboardingUrl, String tenantName);
}
