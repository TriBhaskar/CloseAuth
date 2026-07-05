package com.anterka.closeauthbackend.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The result of issuing a one-time token: the <b>raw secret</b> (for the caller to deliver out-of-band — never stored
 * or logged), plus the persisted token id and expiry. The store holds only the secret's hash.
 *
 * @param rawSecret  the plaintext secret to embed in the email code/link (transient — do not persist or log)
 * @param tokenId    the persisted {@code one_time_tokens} row id
 * @param expiresAt  when the token expires
 */
public record RawOneTimeToken(String rawSecret, UUID tokenId, Instant expiresAt) {
}
