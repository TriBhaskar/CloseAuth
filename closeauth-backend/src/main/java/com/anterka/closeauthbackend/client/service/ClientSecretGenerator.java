package com.anterka.closeauthbackend.client.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates client secrets for confidential OAuth2 clients (UI-3c). Mirrors
 * {@code auth.service.OneTimeTokenGenerator}'s {@code opaque()} shape exactly (32 {@link SecureRandom} bytes,
 * unpadded URL-safe Base64 — 43 characters): this is high-entropy random material, not a password, so there is
 * nothing for a human to type or remember.
 *
 * <p><b>Why the backend generates this instead of accepting a caller-supplied secret (as it did before UI-3c):</b>
 * a caller-chosen secret can be weak ({@code "hunter2"}), and a server-generated one is the only way the "shown
 * exactly once" ceremony in the admin console is actually load-bearing — otherwise the admin already knows the
 * value they typed, and the once-only display is theatre. This matches Keycloak's admin API behavior. The
 * self-hosted/IaC case (an operator needing to pin a known secret when importing a client) is a real but separate
 * need, deliberately deferred to a future bootstrap/import mechanism rather than reopened here on the admin API.
 */
@Component
public class ClientSecretGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32; // 256 bits

    /** A fresh, high-entropy raw secret. Never logged; the caller is responsible for encoding before persistence. */
    public String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
