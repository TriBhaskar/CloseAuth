package com.anterka.closeauthbackend.auth.dto;

/**
 * One requested scope as shown on the consent page (Stage 6b-ii): the raw scope string, a human-readable description
 * (from the Resource Server catalog or the platform built-in map), and whether it requires explicit consent.
 *
 * @param scope           the raw requested scope (e.g. {@code todomaster-api:read}, {@code openid})
 * @param description     human-readable ("Read your to-do items"), never the raw scope when a description exists
 * @param requiresConsent {@code true} → the user must explicitly approve; {@code false} → auto-grantable (a default
 *                        read scope). Platform OIDC scopes default to {@code true} (shown + approved).
 */
public record ConsentScopeView(String scope, String description, boolean requiresConsent) {
}
