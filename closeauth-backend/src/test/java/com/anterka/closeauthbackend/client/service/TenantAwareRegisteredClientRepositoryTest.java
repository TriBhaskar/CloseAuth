package com.anterka.closeauthbackend.client.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FE-4d: {@link TenantAwareRegisteredClientRepository#countByTenantId} — the console overview's Clients tile. */
class TenantAwareRegisteredClientRepositoryTest {

    @Test
    void countByTenantIdReturnsTheQueriedCount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM oauth2_registered_client WHERE tenant_id = ?"),
                eq(Integer.class), any(Object[].class)))
                .thenReturn(3);

        TenantAwareRegisteredClientRepository repository = new TenantAwareRegisteredClientRepository(jdbcTemplate);

        assertThat(repository.countByTenantId(tenantId)).isEqualTo(3);
        verify(jdbcTemplate).queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE tenant_id = ?", Integer.class, tenantId);
    }

    @Test
    void countByTenantIdReturnsZeroRatherThanNull() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);

        TenantAwareRegisteredClientRepository repository = new TenantAwareRegisteredClientRepository(jdbcTemplate);

        assertThat(repository.countByTenantId(UUID.randomUUID())).isZero();
    }

    // ---- existsByTenantIdAndClientId (ClientIdGenerator's collision check) ------------

    @Test
    void existsByTenantIdAndClientIdReturnsTrueWhenCountIsPositive() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM oauth2_registered_client WHERE tenant_id = ? AND client_id = ?"),
                eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        TenantAwareRegisteredClientRepository repository = new TenantAwareRegisteredClientRepository(jdbcTemplate);

        assertThat(repository.existsByTenantIdAndClientId(tenantId, "acme-app-abc12345")).isTrue();
    }

    @Test
    void existsByTenantIdAndClientIdReturnsFalseWhenCountIsZeroOrNull() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        TenantAwareRegisteredClientRepository repository = new TenantAwareRegisteredClientRepository(jdbcTemplate);

        assertThat(repository.existsByTenantIdAndClientId(UUID.randomUUID(), "unused-client-id")).isFalse();
    }

    // ---- client delete: deleteSasAuthorizationsFor / deleteSasConsentsFor / deleteByIdAndTenantId ----

    @Test
    void deleteSasAuthorizationsForIssuesTheExactDeleteStatement() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TenantAwareRegisteredClientRepository repository = new TenantAwareRegisteredClientRepository(jdbcTemplate);

        repository.deleteSasAuthorizationsFor("client-record-id");

        verify(jdbcTemplate).update(
                "DELETE FROM oauth2_authorization WHERE registered_client_id = ?", "client-record-id");
    }

    @Test
    void deleteSasConsentsForIssuesTheExactDeleteStatement() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TenantAwareRegisteredClientRepository repository = new TenantAwareRegisteredClientRepository(jdbcTemplate);

        repository.deleteSasConsentsFor("client-record-id");

        verify(jdbcTemplate).update(
                "DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?", "client-record-id");
    }

    @Test
    void deleteByIdAndTenantIdIssuesTheExactDeleteStatementScopedToTenant() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        TenantAwareRegisteredClientRepository repository = new TenantAwareRegisteredClientRepository(jdbcTemplate);

        repository.deleteByIdAndTenantId("client-record-id", tenantId);

        verify(jdbcTemplate).update(
                "DELETE FROM oauth2_registered_client WHERE id = ? AND tenant_id = ?", "client-record-id", tenantId);
    }
}
