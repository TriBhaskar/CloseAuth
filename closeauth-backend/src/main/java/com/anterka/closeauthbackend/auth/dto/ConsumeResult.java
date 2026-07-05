package com.anterka.closeauthbackend.auth.dto;

import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;

import java.util.UUID;

/**
 * The result of consuming a one-time token. <b>Enumeration-safe by design:</b> on ANY failure {@code success == false}
 * and the metadata is null; the {@link FailureReason} is an internal detail for audit only. A flow surfacing the
 * outcome to a user MUST collapse every failure to one generic message (never reveal not-found vs expired vs used vs
 * wrong-purpose) — the same discipline as {@code PasswordVerificationResult}.
 *
 * @param success  whether the token was validly consumed (and atomically marked used)
 * @param tokenId  the consumed token's id (success only)
 * @param purpose  the token's purpose (success only)
 * @param tenantId the token's tenant (success only)
 * @param userId   the token's subject user, or null (success only)
 * @param target   the delivery target (success only)
 * @param payload  the token's payload, or null (success only)
 * @param reason   internal-only failure classification (audit); {@link FailureReason#NONE} on success
 */
public record ConsumeResult(
        boolean success,
        UUID tokenId,
        OneTimeTokenPurpose purpose,
        UUID tenantId,
        UUID userId,
        String target,
        String payload,
        FailureReason reason) {

    /** Internal-only reason for a failed consume (audit); never surface the specific value to end users. */
    public enum FailureReason {
        NONE,
        NOT_FOUND,
        WRONG_PURPOSE,
        WRONG_TENANT,
        EXPIRED,
        ALREADY_USED
    }

    public static ConsumeResult success(UUID tokenId, OneTimeTokenPurpose purpose, UUID tenantId, UUID userId,
                                        String target, String payload) {
        return new ConsumeResult(true, tokenId, purpose, tenantId, userId, target, payload, FailureReason.NONE);
    }

    public static ConsumeResult failure(FailureReason reason) {
        return new ConsumeResult(false, null, null, null, null, null, null, reason);
    }
}
