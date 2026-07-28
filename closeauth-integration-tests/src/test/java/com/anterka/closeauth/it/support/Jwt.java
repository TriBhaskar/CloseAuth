package com.anterka.closeauth.it.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Minimal black-box JWT reader: decodes the claims (payload) of a compact JWS <b>without verifying the signature</b>.
 * In this external, black-box context the tests assert claim SHAPE (e.g. {@code sub} is the user UUID, {@code tenant_id}
 * is the tenant, not the email) — signature validation is the backend's own concern and not re-proven here.
 */
public final class Jwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jwt() {
    }

    /** Decodes the JWT payload segment to a claims map. */
    public static Map<String, Object> claims(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Not a JWT: " + token);
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = MAPPER.readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
            return claims;
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse JWT claims", e);
        }
    }
}
