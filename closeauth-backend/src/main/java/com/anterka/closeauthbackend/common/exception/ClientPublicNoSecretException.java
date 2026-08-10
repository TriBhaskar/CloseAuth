package com.anterka.closeauthbackend.common.exception;

import java.util.Map;

/**
 * Secret regeneration was requested for a public client ({@code clientAuthenticationMethod = NONE}) — it never
 * had a secret, so there is nothing to rotate. Refused rather than silently minting one, since that would turn a
 * public (PKCE-only) client confidential as a side effect of an operation whose name promises only rotation.
 * Category {@link ErrorCategory#CONFLICT}.
 */
public class ClientPublicNoSecretException extends CloseAuthDomainException {

    private static final String CODE = "client.public_no_secret";

    public ClientPublicNoSecretException(String clientId) {
        super(ErrorCategory.CONFLICT, CODE,
                "Client is public and has no secret to regenerate: " + clientId,
                Map.of("clientId", clientId));
    }
}
