package com.anterka.closeauthbackend.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Command to register a new OAuth2 client (input DTO).
 *
 * <p><b>UI-3c change:</b> {@code clientSecret} was removed. The backend now generates the secret for every
 * confidential client ({@link com.anterka.closeauthbackend.client.service.ClientSecretGenerator}) rather than
 * accepting a caller-supplied value — see that class's javadoc for why. {@code publicClient} replaces the old
 * "blank secret means public" convention explicitly. It deliberately defaults to {@code false} (an omitted JSON
 * field deserializes to {@code false}): the safe failure direction is an unwanted-but-inert generated secret on a
 * confidential client, not an accidentally-public client with no secret at all.
 *
 * @param clientId       the OAuth2 client identifier (unique within the tenant).
 * @param clientName     display name.
 * @param publicClient   {@code true} for a public client (no secret, auth method NONE, PKCE-only); {@code false}
 *                       (the default) for a confidential client, which gets a server-generated secret.
 * @param grantTypes     e.g. {@code client_credentials}, {@code authorization_code}, {@code refresh_token}.
 * @param scopes         requested scopes (may include {@code openid}, {@code profile}, ...).
 * @param redirectUris   required for {@code authorization_code}; ignored otherwise.
 * @param postLogoutUris FE-4c: post-logout redirect URIs for RP-initiated logout. Optional; no format validation
 *                       here, same laxity as {@code redirectUris} — the frontend wizard is responsible for shape
 *                       checks before submission.
 * @param requireProofKey PKCE requirement (true for public clients).
 * @param trusted        first-party / trusted client (Stage 6b-ii): when {@code true}, the OAuth consent screen is
 *                       skipped ({@code requireAuthorizationConsent = false}); {@code false} → consent is required.
 *                       A tenant's own auto-created apps are typically trusted; third-party clients are not.
 */
public record RegisterClientCommand(

        @NotBlank
        @Size(max = 100)
        String clientId,

        @NotBlank
        @Size(max = 200)
        String clientName,

        boolean publicClient,

        @NotEmpty
        List<String> grantTypes,

        List<String> scopes,

        List<String> redirectUris,

        List<String> postLogoutUris,

        boolean requireProofKey,

        boolean trusted

) {}
