package com.anterka.closeauthbackend.auth.dto;

import com.anterka.closeauthbackend.identity.enums.IdpType;

import java.util.UUID;

/**
 * The result of the login <em>policy</em> flow ({@code LoginPolicyService}). Deliberately <b>enumeration-safe</b>:
 * to the client, every failure is indistinguishable ("invalid credentials"); the {@link FailureReason} is retained
 * for server-side audit only (the Stage-8 seam) and never surfaced to the caller.
 *
 * @param success whether the login is allowed to proceed
 * @param userId  the authenticated user (only when {@code success})
 * @param idp     the identity provider of the credential used to authenticate (only when {@code success}) — the real
 *                {@code user_identities.idp_type}, threaded into the token {@code idp} claim (§12)
 * @param reason  server-side-only failure classification (audit); always {@link FailureReason#NONE} on success
 */
public record LoginOutcome(boolean success, UUID userId, IdpType idp, FailureReason reason) {

    /** Server-side audit classification — never leaked to the client (enumeration-safety). */
    public enum FailureReason {
        NONE,
        TENANT_NOT_ACTIVE,
        USER_NOT_ACTIVE,
        INVALID_CREDENTIALS
    }

    public static LoginOutcome success(UUID userId, IdpType idp) {
        return new LoginOutcome(true, userId, idp, FailureReason.NONE);
    }

    public static LoginOutcome failure(FailureReason reason) {
        return new LoginOutcome(false, null, null, reason);
    }
}
