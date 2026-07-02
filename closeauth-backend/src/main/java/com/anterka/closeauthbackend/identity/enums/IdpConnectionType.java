package com.anterka.closeauthbackend.identity.enums;

/**
 * Upstream IdP connection protocol for {@code tenant_idp_connections} (Phase 2).
 * Values must match the CHECK constraint on
 * {@code tenant_idp_connections.connection_type} exactly.
 */
public enum IdpConnectionType {
    OIDC,
    SAML,
    GOOGLE_WORKSPACE,
    AZURE_AD
}
