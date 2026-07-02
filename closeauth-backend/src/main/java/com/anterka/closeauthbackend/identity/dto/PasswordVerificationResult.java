package com.anterka.closeauthbackend.identity.dto;

/**
 * Result of the {@code verifyPassword} primitive (Concern 4).
 *
 * <p><b>Enumeration-safety by design:</b> {@code verifyPassword} never throws and never returns a
 * different <em>shape</em> for different failures. On ANY failure {@code success == false} and
 * {@code user == null}; the {@link FailureReason} is an <em>internal</em> detail intended only for
 * server-side audit logging. A caller that surfaces the outcome to an end user (Stage 6 login) MUST
 * collapse every failure to a single generic "invalid credentials" message, so an attacker cannot
 * distinguish "no such user" from "wrong password" from "no local password".
 */
public record PasswordVerificationResult(boolean success, UserView user, FailureReason failureReason) {

    /** Internal-only reason for a failed verification (for audit; never surface the specific value to end users). */
    public enum FailureReason {
        USER_NOT_FOUND,
        NO_LOCAL_PASSWORD,
        BAD_PASSWORD
    }

    public static PasswordVerificationResult success(UserView user) {
        return new PasswordVerificationResult(true, user, null);
    }

    public static PasswordVerificationResult failure(FailureReason reason) {
        return new PasswordVerificationResult(false, null, reason);
    }
}
