package com.anterka.closeauthbackend.auth.dto;

import com.anterka.closeauthbackend.identity.enums.IdpType;

import java.util.UUID;

/**
 * The result of the login <em>policy</em> flow ({@code LoginPolicyService}). Deliberately <b>enumeration-safe</b>:
 * to the client, every {@link Result#FAILURE} is indistinguishable ("invalid credentials"); the {@link FailureReason}
 * is retained for server-side audit only (the Stage-8 seam) and never surfaced to the caller. This includes
 * {@link FailureReason#RATE_LIMITED} and {@link FailureReason#TEMP_CREDENTIAL_EXPIRED}: both collapse to the exact
 * same uniform 401 as a bad password, an inactive user, or an inactive tenant — {@code LoginController} must never
 * branch on {@code reason()}, and no future change should give any {@link Result#FAILURE} cause a distinguishable
 * HTTP status. Doing so would reopen the account-existence oracle this enumeration-safety exists to close.
 *
 * <p>{@link Result#ROTATION_REQUIRED} (Phase 2 of the tenant-onboarding design, {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md}
 * §2.2) is a third, distinct terminal outcome — NOT a success and NOT the uniform failure. It means the caller
 * proved they know the correct password for a {@code user_identities} row with {@code must_change_password = true};
 * that proof is legitimate, but no session/code/token may be issued for it. {@code LoginController} must route this
 * outcome to the password-rotation interstitial, never to {@link #success}'s session-establishing path.
 *
 * @param result the terminal outcome: proceed, must rotate first, or refused
 * @param userId the subject user (set on both {@link Result#SUCCESS} and {@link Result#ROTATION_REQUIRED})
 * @param idp    the identity provider of the credential used to authenticate (only when {@link Result#SUCCESS}) — the
 *               real {@code user_identities.idp_type}, threaded into the token {@code idp} claim (§12)
 * @param reason server-side-only failure classification (audit); always {@link FailureReason#NONE} otherwise
 */
public record LoginOutcome(Result result, UUID userId, IdpType idp, FailureReason reason) {

    /** The three terminal outcomes of {@code LoginPolicyService.authenticate}. */
    public enum Result {
        SUCCESS,
        /** Correct credential, but a forced password rotation is pending — no session may be issued. */
        ROTATION_REQUIRED,
        FAILURE
    }

    /** Server-side audit classification — never leaked to the client (enumeration-safety). */
    public enum FailureReason {
        NONE,
        TENANT_NOT_ACTIVE,
        USER_NOT_ACTIVE,
        INVALID_CREDENTIALS,
        /** Too many attempts against this tenant+email inside the configured window (server-side audit only). */
        RATE_LIMITED,
        /** Correct password, but the system-generated temp credential's hard expiry has passed (server-side audit only). */
        TEMP_CREDENTIAL_EXPIRED
    }

    /** {@code true} only for {@link Result#SUCCESS} — retained so existing success-path call sites read unchanged. */
    public boolean success() {
        return result == Result.SUCCESS;
    }

    /** {@code true} only for {@link Result#ROTATION_REQUIRED}. */
    public boolean rotationRequired() {
        return result == Result.ROTATION_REQUIRED;
    }

    public static LoginOutcome success(UUID userId, IdpType idp) {
        return new LoginOutcome(Result.SUCCESS, userId, idp, FailureReason.NONE);
    }

    /** The password was correct, but {@code must_change_password} is set — route to password rotation, not a session. */
    public static LoginOutcome rotationRequired(UUID userId) {
        return new LoginOutcome(Result.ROTATION_REQUIRED, userId, null, FailureReason.NONE);
    }

    public static LoginOutcome failure(FailureReason reason) {
        return new LoginOutcome(Result.FAILURE, null, null, reason);
    }
}
