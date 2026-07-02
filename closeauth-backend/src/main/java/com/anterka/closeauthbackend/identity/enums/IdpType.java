package com.anterka.closeauthbackend.identity.enums;

/**
 * Credential source for a {@code user_identities} row (Section 7.4). Values must
 * match the CHECK constraint on {@code user_identities.idp_type} exactly.
 */
public enum IdpType {
    LOCAL_PASSWORD,
    SOCIAL_GOOGLE,
    SOCIAL_GITHUB,
    OIDC_FEDERATED,
    SAML,
    AGENT_KEY
}
