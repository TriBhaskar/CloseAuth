package com.anterka.closeauthbackend.client.service;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.time.Instant;
import java.util.UUID;

/**
 * Bridge for carrying CloseAuth-specific data on a Spring Authorization Server {@link RegisteredClient}.
 *
 * <p><b>How tenant context is threaded into SAS's persistence path:</b> the owning {@code tenant_id} is stored
 * as a custom entry in the client's {@link ClientSettings}. SAS serializes the entire settings map into the
 * {@code client_settings} JSON column (and deserializes it back on read), so the tenant id round-trips through
 * SAS's own flow with no ambient state. The tenant-aware repository additionally mirrors it into the dedicated
 * {@code tenant_id} column (the FK / composite-uniqueness / query source of truth). The token customizer reads
 * it back off the {@code RegisteredClient} at issuance time.
 *
 * <p><b>FE-4c addition:</b> {@code SECRET_ROTATED_AT} reuses this exact same mechanism for a second custom value
 * — no migration needed, since it round-trips through the same {@code client_settings} JSON column the tenant id
 * already does.
 */
public final class CloseAuthClientSettings {

    /** Custom client-settings key holding the owning tenant id (as a UUID string). */
    public static final String TENANT_ID = "settings.client.closeauth.tenant-id";

    /** Custom client-settings key holding the last secret-rotation instant (as an ISO-8601 string), absent until the first rotation. */
    public static final String SECRET_ROTATED_AT = "settings.client.closeauth.secret-rotated-at";

    private CloseAuthClientSettings() {
    }

    /** Adds the tenant id to a {@link ClientSettings.Builder}. */
    public static ClientSettings.Builder withTenantId(ClientSettings.Builder builder, UUID tenantId) {
        return builder.setting(TENANT_ID, tenantId.toString());
    }

    /** Reads the tenant id from a registered client, or {@code null} if absent. */
    public static UUID getTenantId(RegisteredClient registeredClient) {
        Object value = registeredClient.getClientSettings().getSettings().get(TENANT_ID);
        return value == null ? null : UUID.fromString(value.toString());
    }

    /** Adds the secret-rotation instant to a {@link ClientSettings.Builder}. */
    public static ClientSettings.Builder withSecretRotatedAt(ClientSettings.Builder builder, Instant rotatedAt) {
        return builder.setting(SECRET_ROTATED_AT, rotatedAt.toString());
    }

    /** Reads the secret-rotation instant from a registered client, or {@code null} if it has never been rotated. */
    public static Instant getSecretRotatedAt(RegisteredClient registeredClient) {
        Object value = registeredClient.getClientSettings().getSettings().get(SECRET_ROTATED_AT);
        return value == null ? null : Instant.parse(value.toString());
    }
}
