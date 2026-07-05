package com.anterka.closeauthbackend.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Command to register a new OAuth2 client (input DTO).
 *
 * @param clientId       the OAuth2 client identifier (unique within the tenant).
 * @param clientName     display name.
 * @param clientSecret   raw secret for a confidential client; {@code null} for a public client (auth method NONE).
 * @param grantTypes     e.g. {@code client_credentials}, {@code authorization_code}, {@code refresh_token}.
 * @param scopes         requested scopes (may include {@code openid}, {@code profile}, ...).
 * @param redirectUris   required for {@code authorization_code}; ignored otherwise.
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

        String clientSecret,

        @NotEmpty
        List<String> grantTypes,

        List<String> scopes,

        List<String> redirectUris,

        boolean requireProofKey,

        boolean trusted

) {}
