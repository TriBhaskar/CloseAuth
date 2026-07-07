package com.anterka.closeauthbackend.client.dto;

/**
 * The ONE-TIME create response for a registered client (§7.8) — the only place a client secret ever appears in a
 * response. Only the hash is persisted; the plaintext {@code clientSecret} is returned here once and is NEVER
 * retrievable again (subsequent {@code GET}s return {@link ClientView}, which has no secret).
 */
public record ClientCreatedView(ClientView client, String clientSecret) {
}
