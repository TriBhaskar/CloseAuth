package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the revocation-check DECISION logic (mocked marker store). Focuses on the silent-bug spot: the
 * {@code iat}-vs-revocation-timestamp comparison — units (epoch seconds) and the boundary (`<=` revokes).
 */
class TokenRevocationServiceTest {

    private static final long REVOCATION_TIME = 1_700_000_000L; // epoch seconds

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private RevocationMarkerStore markerStore;
    private TokenRevocationService service;

    @BeforeEach
    void setUp() {
        markerStore = Mockito.mock(RevocationMarkerStore.class);
        service = new TokenRevocationService(markerStore, new CloseAuthProperties(),
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class)); // default access TTL = 5m
        when(markerStore.tenantRevocationEpochSeconds(any())).thenReturn(OptionalLong.empty());
        when(markerStore.userRevocationEpochSeconds(any(), any())).thenReturn(OptionalLong.empty());
    }

    @Test
    void tokenIssuedBeforeUserRevocationIsRevoked() {
        when(markerStore.userRevocationEpochSeconds(tenant, user)).thenReturn(OptionalLong.of(REVOCATION_TIME));
        assertThat(service.isRevoked(tenant, user, REVOCATION_TIME - 1)).isTrue();
    }

    @Test
    void tokenIssuedAfterUserRevocationIsNotRevoked() {
        when(markerStore.userRevocationEpochSeconds(tenant, user)).thenReturn(OptionalLong.of(REVOCATION_TIME));
        assertThat(service.isRevoked(tenant, user, REVOCATION_TIME + 1)).isFalse();
    }

    @Test
    void tokenIssuedAtTheSameSecondIsRevoked_safeBoundary() {
        when(markerStore.userRevocationEpochSeconds(tenant, user)).thenReturn(OptionalLong.of(REVOCATION_TIME));
        assertThat(service.isRevoked(tenant, user, REVOCATION_TIME)).isTrue(); // iat <= revocationTime → revoked
    }

    @Test
    void tenantMarkerRevokesAllUsersTokens() {
        when(markerStore.tenantRevocationEpochSeconds(tenant)).thenReturn(OptionalLong.of(REVOCATION_TIME));
        assertThat(service.isRevoked(tenant, user, REVOCATION_TIME - 1)).isTrue();
    }

    @Test
    void machineTokenWithoutUserIsStillCheckedAgainstTenantMarker() {
        when(markerStore.tenantRevocationEpochSeconds(tenant)).thenReturn(OptionalLong.of(REVOCATION_TIME));
        assertThat(service.isRevoked(tenant, null, REVOCATION_TIME - 1)).isTrue();
        verify(markerStore, never()).userRevocationEpochSeconds(any(), any()); // no user marker lookup for M2M
    }

    @Test
    void noMarkerMeansNotRevoked() {
        assertThat(service.isRevoked(tenant, user, REVOCATION_TIME)).isFalse();
    }

    @Test
    void revokeAllUserTokensWritesMarkerWithAccessTokenTtl() {
        service.revokeAllUserTokens(tenant, user);
        verify(markerStore).revokeUser(eq(tenant), eq(user), any(Instant.class), eq(Duration.ofMinutes(5)));
    }

    @Test
    void revokeAllTenantTokensWritesMarkerWithAccessTokenTtl() {
        service.revokeAllTenantTokens(tenant);
        verify(markerStore).revokeTenant(eq(tenant), any(Instant.class), eq(Duration.ofMinutes(5)));
    }

    // ---- platform-admin (tenant-less) revocation (§7.8) --------------------

    @Test
    void platformAdminTokenIssuedAtOrBeforeMarkerIsRevoked_safeBoundary() {
        UUID adminId = UUID.randomUUID();
        when(markerStore.platformAdminRevocationEpochSeconds(adminId)).thenReturn(OptionalLong.of(REVOCATION_TIME));
        assertThat(service.isPlatformAdminRevoked(adminId, REVOCATION_TIME)).isTrue();      // iat == revoke → revoked
        assertThat(service.isPlatformAdminRevoked(adminId, REVOCATION_TIME - 1)).isTrue();  // issued before → revoked
        assertThat(service.isPlatformAdminRevoked(adminId, REVOCATION_TIME + 1)).isFalse(); // issued after → valid
    }

    @Test
    void noPlatformAdminMarkerMeansNotRevoked() {
        UUID adminId = UUID.randomUUID();
        when(markerStore.platformAdminRevocationEpochSeconds(adminId)).thenReturn(OptionalLong.empty());
        assertThat(service.isPlatformAdminRevoked(adminId, REVOCATION_TIME)).isFalse();
    }

    @Test
    void revokePlatformAdminWritesSubKeyedMarkerWithPlatformAdminTokenTtl() {
        UUID adminId = UUID.randomUUID();
        service.revokePlatformAdminTokens(adminId);
        // Marker TTL is the platform-admin token TTL (5m default) so it outlives every token it can suppress.
        verify(markerStore).revokePlatformAdmin(eq(adminId), any(Instant.class), eq(Duration.ofMinutes(5)));
    }
}
