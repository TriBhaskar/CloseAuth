package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.token.dto.ConsentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private final AuditEmitter auditEmitter;

    public TenantAwareOAuth2AuthorizationConsentService(JdbcTemplate jdbcTemplate,
                                                        RegisteredClientRepository registeredClientRepository,
                                                        AuditEmitter auditEmitter) {
        super(jdbcTemplate, registeredClientRepository);
        this.jdbcTemplate = jdbcTemplate;
        this.auditEmitter = auditEmitter;
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

        auditEmitter.emit(AuditEvents.consentGranted(UUID.fromString(tenantId),
                authorizationConsent.getPrincipalName(), authorizationConsent.getRegisteredClientId(),
                List.copyOf(authorizationConsent.getScopes())));
    }

    // ---- 7b admin consent management (§7.8) --------------------------------

    private static final String LIST_CONSENTS_SQL = """
            SELECT c.registered_client_id, rc.client_id, c.authorities
              FROM oauth2_authorization_consent c
              JOIN oauth2_registered_client rc ON rc.id = c.registered_client_id
             WHERE c.tenant_id = CAST(? AS uuid) AND c.principal_name = ?""";

    private static final String COUNT_CONSENT_SQL = """
            SELECT count(*) FROM oauth2_authorization_consent
             WHERE registered_client_id = ? AND principal_name = ? AND tenant_id = CAST(? AS uuid)""";

    /** Lists a principal's granted consents within a tenant (tenant-scoped — never crosses tenants). */
    @Transactional(readOnly = true)
    public List<ConsentView> listConsents(UUID tenantId, String principalName) {
        return jdbcTemplate.query(LIST_CONSENTS_SQL, (rs, i) -> {
            String authorities = rs.getString("authorities");
            List<String> scopes = (authorities == null || authorities.isBlank())
                    ? List.of() : List.of(authorities.split(","));
            return new ConsentView(rs.getString("registered_client_id"), rs.getString("client_id"), scopes);
        }, tenantId.toString(), principalName);
    }

    /**
     * Revokes a principal's consent for one client within a tenant. Tenant-scoped (defense in depth: the consent row
     * must belong to {@code tenantId}); idempotent if absent.
     */
    @Transactional
    public void revokeConsent(UUID tenantId, String registeredClientId, String principalName) {
        Integer inTenant = jdbcTemplate.queryForObject(
                COUNT_CONSENT_SQL, Integer.class, registeredClientId, principalName, tenantId.toString());
        if (inTenant == null || inTenant == 0) {
            return; // not found in this tenant — idempotent, and never touches another tenant's row
        }
        OAuth2AuthorizationConsent consent = findById(registeredClientId, principalName);
        if (consent != null) {
            remove(consent);
        }
        auditEmitter.emit(AuditEvents.consentRevoked(tenantId, principalName, registeredClientId));
    }
}
