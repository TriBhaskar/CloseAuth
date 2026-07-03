package com.anterka.closeauthbackend.token.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tenant-aware {@link org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService}
 * for {@code oauth2_authorization_consent} — same Approach A. Consent rows are only written during the
 * authorization-code + consent flow (Stage 6 for the branded consent page); wired here for completeness so its
 * {@code NOT NULL tenant_id} is handled the same way. INSERT is hand-built (3 SAS columns + tenant_id derived
 * from the client); UPDATE delegates to SAS.
 */
@Component
public class TenantAwareOAuth2AuthorizationConsentService extends JdbcOAuth2AuthorizationConsentService {

    // PINNED TO SAS 1.5.1. On any SAS version bump, re-verify this column list and count against
    // JdbcOAuth2AuthorizationConsentService's INSERT columns and update BOTH the list below and this assertion
    // count. See STAGE_4A_REPORT.md §1.
    private static final int SAS_COLUMN_COUNT = 3; // registered_client_id, principal_name, authorities

    private static final String INSERT_SQL = """
            INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities, tenant_id)
            VALUES (?, ?, ?, CAST(? AS uuid))""";

    private static final String SELECT_CLIENT_TENANT_SQL =
            "SELECT tenant_id FROM oauth2_registered_client WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    public TenantAwareOAuth2AuthorizationConsentService(JdbcTemplate jdbcTemplate,
                                                        RegisteredClientRepository registeredClientRepository) {
        super(jdbcTemplate, registeredClientRepository);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        boolean isNew = findById(authorizationConsent.getRegisteredClientId(),
                authorizationConsent.getPrincipalName()) == null;
        if (!isNew) {
            super.save(authorizationConsent); // UPDATE — authorities only; tenant_id untouched
            return;
        }

        String tenantId = jdbcTemplate.queryForObject(
                SELECT_CLIENT_TENANT_SQL, String.class, authorizationConsent.getRegisteredClientId());
        if (tenantId == null) {
            throw new IllegalStateException("Cannot resolve tenant_id for registered client "
                    + authorizationConsent.getRegisteredClientId());
        }

        List<SqlParameterValue> sasParameters = getAuthorizationConsentParametersMapper().apply(authorizationConsent);
        if (sasParameters.size() != SAS_COLUMN_COUNT) {
            throw new IllegalStateException("Unexpected SAS consent column count: "
                    + sasParameters.size() + " (expected " + SAS_COLUMN_COUNT + " for SAS 1.5.1)");
        }

        List<Object> args = new ArrayList<>(sasParameters);
        args.add(tenantId); // bound to CAST(? AS uuid)
        jdbcTemplate.update(INSERT_SQL, args.toArray());
    }
}
