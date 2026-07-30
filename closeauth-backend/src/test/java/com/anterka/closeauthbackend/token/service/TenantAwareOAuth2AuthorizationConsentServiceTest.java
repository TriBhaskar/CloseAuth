package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.token.dto.ConsentView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantAwareOAuth2AuthorizationConsentService#listConsents}. Focus: the admin consents API must
 * return <b>bare</b> scope names — SAS stores consent authorities as {@code SCOPE_<scope>} in the {@code authorities}
 * column, and this mapping must strip that framework prefix (matching {@code OAuth2AuthorizationConsent#getScopes} and
 * the {@code GET /oauth2/consent} {@code alreadyGranted} shape). Verified black-box by IT-6.
 */
class TenantAwareOAuth2AuthorizationConsentServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void listConsentsReturnsBareScopeNamesStrippingScopeAuthorityPrefix() throws Exception {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        RegisteredClientRepository registeredClientRepository = Mockito.mock(RegisteredClientRepository.class);
        AuditEmitter auditEmitter = Mockito.mock(AuditEmitter.class);
        TenantAwareOAuth2AuthorizationConsentService service =
                new TenantAwareOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository, auditEmitter);

        // One consent row whose authorities are stored as SAS scope authorities (SCOPE_-prefixed, comma-joined).
        ResultSet row = Mockito.mock(ResultSet.class);
        when(row.getString("registered_client_id")).thenReturn("rc-1");
        when(row.getString("client_id")).thenReturn("acme-web");
        when(row.getString("authorities")).thenReturn("SCOPE_openid,SCOPE_profile,SCOPE_acme-api:read");

        // Drive the RowMapper the service passes to jdbcTemplate.query against our fake row.
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<ConsentView> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        List<ConsentView> consents = service.listConsents(UUID.randomUUID(), "user-sub");

        assertThat(consents).hasSize(1);
        ConsentView view = consents.get(0);
        assertThat(view.registeredClientId()).isEqualTo("rc-1");
        assertThat(view.clientId()).isEqualTo("acme-web");
        assertThat(view.scopes())
                .as("scopes are returned as bare names, not SCOPE_-prefixed authorities")
                .containsExactly("openid", "profile", "acme-api:read");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listConsentsHandlesBlankAuthoritiesAsEmptyScopes() throws Exception {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        TenantAwareOAuth2AuthorizationConsentService service = new TenantAwareOAuth2AuthorizationConsentService(
                jdbcTemplate, Mockito.mock(RegisteredClientRepository.class), Mockito.mock(AuditEmitter.class));

        ResultSet row = Mockito.mock(ResultSet.class);
        when(row.getString("registered_client_id")).thenReturn("rc-2");
        when(row.getString("client_id")).thenReturn("empty-client");
        when(row.getString("authorities")).thenReturn(null);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper<ConsentView>) invocation.getArgument(1)).mapRow(row, 0)));

        List<ConsentView> consents = service.listConsents(UUID.randomUUID(), "user-sub");

        assertThat(consents).hasSize(1);
        assertThat(consents.get(0).scopes()).isEmpty();
    }
}
