package com.anterka.closeauthbackend.client.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-aware {@link org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository}
 * — resolves Stage 2's SAS three-table deferral for {@code oauth2_registered_client} (Approach A).
 *
 * <p>Extends SAS's {@link JdbcRegisteredClientRepository} so SAS's own row-mapper / parameters-mapper handle the
 * intricate {@code client_settings}/{@code token_settings} JSON serialization. We override only {@code save()}:
 * <ul>
 *   <li><b>New client (INSERT):</b> SAS's fixed 13-column INSERT can't include our {@code NOT NULL tenant_id}.
 *       So we hand-build the INSERT, reusing SAS's {@code RegisteredClientParametersMapper} for the 13 serialized
 *       column values (no reimplementation of the blob serialization) and appending {@code tenant_id}. The
 *       tenant id is read from the {@link CloseAuthClientSettings#TENANT_ID} client setting.</li>
 *   <li><b>Existing client (UPDATE):</b> delegate to {@code super.save()} — SAS's UPDATE touches only its own 10
 *       columns, leaving {@code tenant_id} untouched (a client never changes tenant).</li>
 * </ul>
 *
 * <p>The column list below is pinned to SAS 1.5.1 (verified against the jar); the runtime size assertion makes a
 * future SAS column-set change fail loudly rather than silently corrupt inserts.
 */
@Component
public class TenantAwareRegisteredClientRepository extends JdbcRegisteredClientRepository {

    // PINNED TO SAS 1.5.1. On any SAS version bump, re-verify this column list and count against
    // JdbcRegisteredClientRepository's INSERT columns (COLUMN_NAMES) and update BOTH the list below and this
    // assertion count. See STAGE_4A_REPORT.md §1.
    private static final int SAS_COLUMN_COUNT = 13;

    private static final String INSERT_SQL = """
            INSERT INTO oauth2_registered_client (
                id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
                client_authentication_methods, authorization_grant_types, redirect_uris, post_logout_redirect_uris,
                scopes, client_settings, token_settings, tenant_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS uuid))""";

    public TenantAwareRegisteredClientRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        boolean isNew = findById(registeredClient.getId()) == null;
        if (!isNew) {
            super.save(registeredClient); // UPDATE — SAS columns only; tenant_id (immutable) untouched
            return;
        }

        // tenant_id is stored in both the settings blob (how SAS round-trips it, read here) and the column
        // (authoritative FK/uniqueness/query source, written below). These must stay consistent; this holds
        // because tenant_id is immutable for a client. A future feature allowing tenant reassignment would have
        // to update BOTH the client_settings blob and the tenant_id column together.
        UUID tenantId = CloseAuthClientSettings.getTenantId(registeredClient);
        if (tenantId == null) {
            throw new IllegalStateException(
                    "Cannot persist registered client without a tenant id setting: " + registeredClient.getId());
        }

        List<SqlParameterValue> sasParameters = getRegisteredClientParametersMapper().apply(registeredClient);
        if (sasParameters.size() != SAS_COLUMN_COUNT) {
            throw new IllegalStateException("Unexpected SAS registered-client column count: "
                    + sasParameters.size() + " (expected " + SAS_COLUMN_COUNT + " for SAS 1.5.1)");
        }

        List<Object> args = new ArrayList<>(sasParameters);
        args.add(tenantId.toString()); // bound to CAST(? AS uuid)
        getJdbcOperations().update(INSERT_SQL, args.toArray());
    }

    /**
     * FE-4d: the overview's Clients count tile. A plain {@code COUNT(*)} against the same table {@link #save}
     * already hand-builds SQL for, and the smallest possible slice of FE-4.10's still-blocked full list —
     * no row data, no pagination, no secret exposure.
     */
    public int countByTenantId(UUID tenantId) {
        Integer count = getJdbcOperations().queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE tenant_id = ?", Integer.class, tenantId);
        return count == null ? 0 : count;
    }
}
