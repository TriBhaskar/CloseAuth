package com.anterka.closeauthbackend.token.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tenant-aware {@link org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService} for
 * {@code oauth2_authorization} — same Approach A as {@code TenantAwareRegisteredClientRepository}.
 *
 * <p>Extends SAS's {@link JdbcOAuth2AuthorizationService} (so its intricate attribute/token blob serialization is
 * reused) and overrides {@code save()}: on INSERT, hand-build the statement reusing SAS's
 * {@code AuthorizationParametersMapper} for the 33 serialized columns and append {@code tenant_id}; on UPDATE,
 * delegate to SAS. The {@code tenant_id} is derived from the owning registered client row (authorizations are
 * always created for a known client).
 */
@Component
public class TenantAwareOAuth2AuthorizationService extends JdbcOAuth2AuthorizationService {

    // PINNED TO SAS 1.5.1. On any SAS version bump, re-verify this column list and count against
    // JdbcOAuth2AuthorizationService's INSERT columns (COLUMN_NAMES) and update BOTH the list below and this
    // assertion count. See STAGE_4A_REPORT.md §1.
    private static final int SAS_COLUMN_COUNT = 33;

    private static final String INSERT_SQL = """
            INSERT INTO oauth2_authorization (
                id, registered_client_id, principal_name, authorization_grant_type, authorized_scopes, attributes,
                state, authorization_code_value, authorization_code_issued_at, authorization_code_expires_at,
                authorization_code_metadata, access_token_value, access_token_issued_at, access_token_expires_at,
                access_token_metadata, access_token_type, access_token_scopes, oidc_id_token_value,
                oidc_id_token_issued_at, oidc_id_token_expires_at, oidc_id_token_metadata, refresh_token_value,
                refresh_token_issued_at, refresh_token_expires_at, refresh_token_metadata, user_code_value,
                user_code_issued_at, user_code_expires_at, user_code_metadata, device_code_value,
                device_code_issued_at, device_code_expires_at, device_code_metadata, tenant_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS uuid))""";

    private static final String SELECT_CLIENT_TENANT_SQL =
            "SELECT tenant_id FROM oauth2_registered_client WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    public TenantAwareOAuth2AuthorizationService(JdbcTemplate jdbcTemplate,
                                                 RegisteredClientRepository registeredClientRepository) {
        super(jdbcTemplate, registeredClientRepository);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        boolean isNew = findById(authorization.getId()) == null;
        if (!isNew) {
            super.save(authorization); // UPDATE — SAS columns only; tenant_id untouched
            return;
        }

        String tenantId = jdbcTemplate.queryForObject(
                SELECT_CLIENT_TENANT_SQL, String.class, authorization.getRegisteredClientId());
        if (tenantId == null) {
            throw new IllegalStateException("Cannot resolve tenant_id for registered client "
                    + authorization.getRegisteredClientId());
        }

        List<SqlParameterValue> sasParameters = getAuthorizationParametersMapper().apply(authorization);
        if (sasParameters.size() != SAS_COLUMN_COUNT) {
            throw new IllegalStateException("Unexpected SAS authorization column count: "
                    + sasParameters.size() + " (expected " + SAS_COLUMN_COUNT + " for SAS 1.5.1)");
        }

        List<Object> args = new ArrayList<>(sasParameters);
        args.add(tenantId); // bound to CAST(? AS uuid)
        jdbcTemplate.update(INSERT_SQL, args.toArray());
    }
}
