package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates one-time secrets and hashes them for storage. The raw secret uses {@link SecureRandom}; the stored value
 * is a SHA-256 hex hash (fast is correct here — these are high-entropy random tokens, NOT passwords, so a slow
 * password hash would be wrong). The raw value is returned to the caller and never stored or logged.
 */
@Component
public class OneTimeTokenGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int OPAQUE_BYTES = 32; // 256 bits

    /** Generates a raw secret in the requested format. {@code codeLength} applies only to {@link OneTimeTokenFormat#NUMERIC_CODE}. */
    public String generate(OneTimeTokenFormat format, int codeLength) {
        return switch (format) {
            case NUMERIC_CODE -> numericCode(codeLength);
            case OPAQUE_LINK -> opaque();
        };
    }

    /** SHA-256 hex of the raw secret — the value persisted in {@code one_time_tokens.token_hash}. */
    public String hash(String rawSecret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawSecret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String numericCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private String opaque() {
        byte[] bytes = new byte[OPAQUE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
