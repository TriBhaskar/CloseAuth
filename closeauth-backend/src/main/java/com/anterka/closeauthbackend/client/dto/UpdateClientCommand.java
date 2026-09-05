package com.anterka.closeauthbackend.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Command to update a client's mutable fields (full replacement of the mutable set, same convention as
 * {@code UpdateResourceServerCommand}: a caller always sends every field pre-populated from the current
 * values, never a sparse/partial patch).
 *
 * <p><b>{@code clientId}, {@code tenantId}, {@code publicClient}, and {@code grantTypes} are deliberately
 * absent</b> — structurally immutable, the same treatment {@code UpdateResourceServerCommand} gives
 * {@code audienceIdentifier}. {@code publicClient} in particular is not just "hard to change": flipping it
 * would silently change the client's authentication method and secret semantics as a side effect of an
 * operation whose name promises only a config update, so it is refused by omission rather than validated
 * away. {@code grantTypes} defines the client's fundamental type (confidential-web vs. SPA vs. M2M) and stays
 * fixed for the same reason a resource server's audience does — a stable contract, not a knob.
 *
 * @param clientName     display name.
 * @param scopes         requested scopes — full replacement (may include {@code openid}, {@code profile}, ...).
 * @param redirectUris   full replacement of the registered redirect URIs.
 * @param postLogoutUris full replacement of the registered post-logout redirect URIs.
 * @param requireProofKey PKCE requirement.
 * @param trusted        first-party / trusted client (Stage 6b-ii): {@code true} skips the OAuth consent
 *                       screen ({@code requireAuthorizationConsent = false}); {@code false} requires it.
 */
public record UpdateClientCommand(

        @NotBlank
        @Size(max = 200)
        String clientName,

        List<String> scopes,

        List<String> redirectUris,

        List<String> postLogoutUris,

        boolean requireProofKey,

        boolean trusted

) {}
