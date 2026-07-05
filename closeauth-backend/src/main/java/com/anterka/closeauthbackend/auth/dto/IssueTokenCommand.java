package com.anterka.closeauthbackend.auth.dto;

import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;

import java.time.Duration;
import java.util.UUID;

/**
 * Inputs to {@code OneTimeTokenService.issue}. The security properties are fixed by the primitive; only the
 * presentation ({@link OneTimeTokenFormat}) and lifetime ({@code ttl}) vary per call (both flow-supplied, from
 * purpose-specific config).
 *
 * @param purpose  what the token authorizes (purpose-bound on consume)
 * @param tenantId the tenant the token is scoped to (tenant-bound on consume)
 * @param userId   the subject user, or {@code null} (an INVITE may precede a user)
 * @param target   the email the raw secret will be delivered to
 * @param payload  small purpose-specific JSON payload, or {@code null}
 * @param format   numeric code vs opaque link
 * @param ttl      lifetime from now
 */
public record IssueTokenCommand(
        OneTimeTokenPurpose purpose,
        UUID tenantId,
        UUID userId,
        String target,
        String payload,
        OneTimeTokenFormat format,
        Duration ttl) {
}
