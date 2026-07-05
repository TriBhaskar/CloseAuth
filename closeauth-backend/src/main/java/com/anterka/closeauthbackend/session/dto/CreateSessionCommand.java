package com.anterka.closeauthbackend.session.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Command to establish a new Auth Server session (§7.5). Called by the Stage 6 login flow after a user authenticates;
 * exposed now as a capability (mechanism-before-flow, same split as prior stages).
 *
 * @param userId     the authenticated user
 * @param tenantId   the tenant the session is bound to (the login always happens within a resolved tenant)
 * @param ipAddress  client IP for the ledger/audit (nullable)
 * @param userAgent  client user-agent for the ledger/audit (nullable)
 * @param rememberMe whether to extend the absolute cap to the remember-me window (subject to platform policy)
 * @param amr        the OIDC authentication-method reference for THIS login (RFC 8176, e.g. {@code pwd},
 *                   {@code magic_link}) — a plain String to keep the session module free of an auth-module type;
 *                   the login flow supplies {@code AuthMethod.amrValue()}. Nullable (omits the amr claim).
 */
public record CreateSessionCommand(
        @NotNull UUID userId,
        @NotNull UUID tenantId,
        String ipAddress,
        String userAgent,
        boolean rememberMe,
        String amr) {
}
