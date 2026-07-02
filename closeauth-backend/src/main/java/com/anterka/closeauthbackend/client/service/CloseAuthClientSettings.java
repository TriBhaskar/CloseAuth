package com.anterka.closeauthbackend.client.service;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

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
 */
public final class CloseAuthClientSettings {

    /** Custom client-settings key holding the owning tenant id (as a UUID string). */
    public static final String TENANT_ID = "settings.client.closeauth.tenant-id";

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
}
