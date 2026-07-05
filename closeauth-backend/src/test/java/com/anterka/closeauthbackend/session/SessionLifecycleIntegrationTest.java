package com.anterka.closeauthbackend.session;

import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.session.dto.CreateSessionCommand;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.entity.AuthServerSession;
import com.anterka.closeauthbackend.session.repository.AuthServerSessionRepository;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.anterka.closeauthbackend.token.entity.RefreshToken;
import com.anterka.closeauthbackend.token.enums.RefreshTokenStatus;
import com.anterka.closeauthbackend.token.repository.RefreshTokenRepository;
import com.anterka.closeauthbackend.token.service.RefreshTokenIssuance;
import com.anterka.closeauthbackend.token.service.RefreshTokenRotationService;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end session mechanism against live Postgres + Redis: the full context boots with Spring Session Redis active
 * (proving the infra wires), and the tenant-scoped create/validate/revoke-cascade behaves correctly across real
 * stores. Gated on {@code -Dcloseauth.it.db.url=...} (self-skips in plain {@code mvn test}); see 4a for the setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class SessionLifecycleIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
    }

    @Autowired AuthServerSessionService sessionService;
    @Autowired AuthServerSessionRepository sessionRepository;
    @Autowired TenantService tenantService;
    @Autowired UserService userService;
    @Autowired ClientRegistrationService clientRegistrationService;
    @Autowired RefreshTokenRotationService rotationService;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired TokenRevocationService tokenRevocationService;

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID newActiveTenant() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("t-" + rnd(), "T"));
        tenantService.activateTenant(tenant.id());
        return tenant.id();
    }

    private UUID newUser(TenantContext ctx) {
        return userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                "u-" + rnd() + "@x.com", "password123", "F", "L", null, UserStatus.ACTIVE)).id();
    }

    private String newClient(TenantContext ctx) {
        String clientId = "c-" + rnd();
        return clientRegistrationService.registerClient(ctx, new RegisterClientCommand(
                clientId, clientId, "secret", List.of("client_credentials"), List.of("read"), null, false, true)).id();
    }

    @Test
    void createValidateTenantScopeAndRevokeCascadeAgainstRealStores() {
        UUID tenantA = newActiveTenant();
        UUID tenantB = newActiveTenant();
        TenantContext ctxA = TenantContext.of(tenantA);
        UUID userA = newUser(ctxA);

        // --- create ---
        SessionView session = sessionService.createSession(
                new CreateSessionCommand(userA, tenantA, "10.0.0.1", "JUnit", false, "pwd"));
        String key = session.sessionKey();
        assertThat(key).isNotBlank();

        // hot store round-trips against real Redis: validate returns the session.
        assertThat(sessionService.validateSession(key, tenantA)).isPresent();

        // --- tenant scoping: the SAME cookie presented in tenant B is "no session". ---
        assertThat(sessionService.validateSession(key, tenantB)).isEmpty();

        // A second session in tenant B for a different user coexists and validates only against B.
        UUID userB = newUser(TenantContext.of(tenantB));
        SessionView sessionB = sessionService.createSession(new CreateSessionCommand(userB, tenantB, null, null, false, "pwd"));
        assertThat(sessionService.validateSession(sessionB.sessionKey(), tenantB)).isPresent();
        assertThat(sessionService.validateSession(sessionB.sessionKey(), tenantA)).isEmpty();

        // --- link a refresh-token family to session A (the Stage-6 wiring, exercised as a capability) ---
        RefreshToken rt = rotationService.recordInitialToken(userA, tenantA, newClient(ctxA),
                new RefreshTokenIssuance("h-" + rnd(), "openid", Instant.now().plus(14, ChronoUnit.DAYS), null, null));
        sessionService.linkRefreshFamilyToSession(rt.getFamilyId(), session.id());

        // --- revoke cascade: kills all four legs ---
        sessionService.revokeSession(key);

        // leg 1: Redis hot entry gone → no longer validates.
        assertThat(sessionService.validateSession(key, tenantA)).isEmpty();
        // leg 2: ledger row marked revoked.
        Optional<AuthServerSession> ledger = sessionRepository.findBySessionKey(key);
        assertThat(ledger).isPresent();
        assertThat(ledger.get().getRevokedAt()).isNotNull();
        // leg 3: the session's refresh family is REVOKED (4b-i).
        assertThat(refreshTokenRepository.findByTokenHash(rt.getTokenHash()).orElseThrow().getStatus())
                .isEqualTo(RefreshTokenStatus.REVOKED);
        // leg 4: an access-token revocation marker exists for the user (4b-ii) — a token issued now is revoked.
        assertThat(tokenRevocationService.isRevoked(tenantA, userA, Instant.now().getEpochSecond())).isTrue();

        // Isolation: session B (tenant B, user B) is completely unaffected.
        assertThat(sessionService.validateSession(sessionB.sessionKey(), tenantB)).isPresent();
        assertThat(tokenRevocationService.isRevoked(tenantB, userB, Instant.now().getEpochSecond())).isFalse();
    }

    @Test
    void listSessionsForUserReturnsActiveExcludesRevoked() {
        UUID tenantId = newActiveTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        UUID user = newUser(ctx);

        SessionView s1 = sessionService.createSession(new CreateSessionCommand(user, tenantId, null, null, false, "pwd"));
        sessionService.createSession(new CreateSessionCommand(user, tenantId, null, null, false, "pwd"));
        assertThat(sessionService.listSessionsForUser(tenantId, user)).hasSize(2);

        sessionService.revokeSession(s1.sessionKey());
        List<SessionView> afterRevoke = sessionService.listSessionsForUser(tenantId, user);
        assertThat(afterRevoke).hasSize(1);
        assertThat(afterRevoke).noneMatch(v -> v.id().equals(s1.id()));
    }
}
