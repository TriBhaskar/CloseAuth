package com.anterka.closeauthbackend.admin.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security centerpiece of Stage 7a — the centralized admin authorization logic, tested in isolation. Proves the
 * platform vs tenant tiering and — critically — the <b>cross-tenant admin guard</b> (a tenant admin of A is denied for
 * B), from both directions.
 */
class AdminAuthorizationTest {

    private static final String TENANT_A = UUID.randomUUID().toString();
    private static final String TENANT_B = UUID.randomUUID().toString();

    private final AdminAuthorization authz = new AdminAuthorization();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- platform-scoped gate ---------------------------------------------

    @Test
    void platformAdminHasThePlatformRole() {
        authenticate(platformAdminToken());
        assertThat(authz.hasPlatformRole("PLATFORM_ADMIN")).isTrue();
    }

    @Test
    void tenantAdminDoesNotHaveThePlatformRole() {
        authenticate(tenantAdminToken(TENANT_A)); // a tenant admin must never pass a platform-scoped gate
        assertThat(authz.hasPlatformRole("PLATFORM_ADMIN")).isFalse();
    }

    @Test
    void noAuthenticationHasNoAccess() {
        assertThat(authz.hasPlatformRole("PLATFORM_ADMIN")).isFalse();
        assertThat(authz.hasTenantAccess(TENANT_A)).isFalse();
    }

    // ---- tenant-scoped gate (the cross-tenant guard) ----------------------

    @Test
    void platformAdminCanAdministerAnyTenant() {
        authenticate(platformAdminToken());
        assertThat(authz.hasTenantAccess(TENANT_A)).isTrue();
        assertThat(authz.hasTenantAccess(TENANT_B)).isTrue();
    }

    @Test
    void tenantAdminCanAdministerOwnTenantButNotAnother() {
        authenticate(tenantAdminToken(TENANT_A));
        assertThat(authz.hasTenantAccess(TENANT_A)).isTrue();  // own tenant
        assertThat(authz.hasTenantAccess(TENANT_B)).isFalse(); // THE cross-tenant admin guard — denied for B
    }

    @Test
    void tenantAdminOfBIsDeniedForA() {
        authenticate(tenantAdminToken(TENANT_B)); // the other direction
        assertThat(authz.hasTenantAccess(TENANT_A)).isFalse();
        assertThat(authz.hasTenantAccess(TENANT_B)).isTrue();
    }

    @Test
    void tenantMemberWithoutTenantAdminIsDeniedEvenForOwnTenant() {
        Jwt jwt = jwtBuilder()
                .claim("roles", List.of())
                .claim("tenant_id", TENANT_A)
                .claim("tenant_roles", List.of("TENANT_MEMBER")) // not TENANT_ADMIN
                .build();
        authenticate(jwt);
        assertThat(authz.hasTenantAccess(TENANT_A)).isFalse();
    }

    // ---- helpers ----------------------------------------------------------

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private Jwt platformAdminToken() {
        // Platform token shape: platform roles, NO tenant_id.
        return jwtBuilder().subject(UUID.randomUUID().toString()).claim("roles", List.of("PLATFORM_ADMIN")).build();
    }

    private Jwt tenantAdminToken(String tenantId) {
        // User token shape: tenant_id + tenant_roles, empty platform roles.
        return jwtBuilder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of())
                .claim("tenant_id", tenantId)
                .claim("tenant_roles", List.of("TENANT_ADMIN"))
                .build();
    }

    private Jwt.Builder jwtBuilder() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
    }
}
