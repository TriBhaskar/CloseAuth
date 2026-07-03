package com.anterka.closeauthbackend.token.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes refresh token values for storage in {@code refresh_tokens.token_hash}. The raw refresh token is NEVER
 * stored — the ledger holds only this hash, and rotation looks a presented token up by its hash. SHA-256 is
 * sufficient here: the token is a high-entropy random value (no need for a slow password hash).
 */
public final class RefreshTokenHasher {

    private RefreshTokenHasher() {
    }

    public static String sha256Hex(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
