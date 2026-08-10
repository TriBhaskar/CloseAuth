package com.anterka.closeauthbackend.client.dto;

/**
 * The ONE-TIME response for a secret regeneration (UI-3c) — wire-identical in shape to {@link ClientCreatedView}
 * on purpose (same two fields, same meaning) so the BFF/SPA can decode both the create and the regenerate response
 * with one shared type and render both through the same credential-display component. Kept as a distinct record
 * rather than reusing {@link ClientCreatedView} so each endpoint's response type states plainly what it is — this
 * is a rotation, not a creation.
 *
 * <p>Only the hash is persisted; the plaintext {@code clientSecret} is returned here once and is NEVER retrievable
 * again ({@code GET} returns {@link ClientView}, which has no secret).
 */
public record ClientSecretView(ClientView client, String clientSecret) {
}
