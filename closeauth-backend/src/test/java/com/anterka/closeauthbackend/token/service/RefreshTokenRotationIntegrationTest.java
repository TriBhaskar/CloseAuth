package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.anterka.closeauthbackend.token.entity.RefreshToken;
import com.anterka.closeauthbackend.token.enums.RefreshTokenStatus;
import com.anterka.closeauthbackend.token.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed tests of the rotation ledger: real family lineage, family revocation, and isolation (the scenarios that
 * need actual rows and the modifying queries). Runs only with {@code -Dcloseauth.it.db.url=...} against an
 * already-running Postgres + Redis (self-skips in plain {@code mvn test}); see 4a's integration test for the setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class RefreshTokenRotationIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
    }

    @Autowired TenantService tenantService;
    @Autowired com.anterka.closeauthbackend.identity.service.UserService userService;
    @Autowired ClientRegistrationService clientRegistrationService;
    @Autowired RefreshTokenRotationService rotationService;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID newActiveTenant() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("T"));
        tenantService.activateTenant(tenant.id());
        return tenant.id();
    }

    private UUID newUser(TenantContext ctx) {
        return userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                "u-" + rnd() + "@x.com", "password123", "F", "L", null, UserStatus.ACTIVE)).id();
    }

    private String newClient(TenantContext ctx) {
        String clientName = "c-" + rnd();
        return clientRegistrationService.registerClient(ctx, new RegisterClientCommand(
                clientName, false, List.of("client_credentials"), List.of("read"), null, null, false, true)).client().id();
    }

    private RefreshTokenIssuance issuance(String hash) {
        return new RefreshTokenIssuance(hash, "openid", Instant.now().plus(14, ChronoUnit.DAYS), null, null);
    }

    private RotationOutcome rotate(String presentedHash, String newHash) {
        RotationOutcome outcome = rotationService.authorizeRotation(presentedHash);
        if (outcome.type() == RotationOutcome.Type.PROCEED) {
            rotationService.recordRotatedToken(outcome.parent(), issuance(newHash));
        }
        return outcome;
    }

    private RefreshTokenStatus status(String hash) {
        return refreshTokenRepository.findByTokenHash(hash).orElseThrow().getStatus();
    }

    // ---- Scenario 1 & 6: happy-path rotation + sliding window --------------

    @Test
    void happyPathRotationRecordsLineageAndSlidingWindow() {
        UUID tenantId = newActiveTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        UUID userId = newUser(ctx);
        String client = newClient(ctx);

        RefreshToken rt1 = rotationService.recordInitialToken(userId, tenantId, client, issuance("h1-" + rnd()));
        String h1 = rt1.getTokenHash();
        String h2 = "h2-" + rnd();

        RotationOutcome outcome = rotate(h1, h2);

        assertThat(outcome.type()).isEqualTo(RotationOutcome.Type.PROCEED);
        assertThat(status(h1)).isEqualTo(RefreshTokenStatus.USED);
        RefreshToken rt2 = refreshTokenRepository.findByTokenHash(h2).orElseThrow();
        assertThat(rt2.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(rt2.getFamilyId()).isEqualTo(rt1.getFamilyId());
        assertThat(rt2.getParentTokenId()).isEqualTo(rt1.getId());
        assertThat(rt2.getExpiresAt()).isAfter(Instant.now().plus(13, ChronoUnit.DAYS)); // ~14-day sliding window
    }

    // ---- Scenario 2: multi-step chain -------------------------------------

    @Test
    void multiStepChainKeepsOneFamilyWithCorrectLineage() {
        UUID tenantId = newActiveTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        RefreshToken rt1 = rotationService.recordInitialToken(newUser(ctx), tenantId, newClient(ctx), issuance("c1-" + rnd()));
        String h1 = rt1.getTokenHash();
        String h2 = "c2-" + rnd();
        String h3 = "c3-" + rnd();

        rotate(h1, h2);
        rotate(h2, h3);

        RefreshToken rt2 = refreshTokenRepository.findByTokenHash(h2).orElseThrow();
        RefreshToken rt3 = refreshTokenRepository.findByTokenHash(h3).orElseThrow();
        assertThat(status(h1)).isEqualTo(RefreshTokenStatus.USED);
        assertThat(status(h2)).isEqualTo(RefreshTokenStatus.USED);
        assertThat(rt3.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(rt2.getFamilyId()).isEqualTo(rt1.getFamilyId());
        assertThat(rt3.getFamilyId()).isEqualTo(rt1.getFamilyId());
        assertThat(rt3.getParentTokenId()).isEqualTo(rt2.getId());
    }

    // ---- Scenario 3 & 5: replay revokes whole family; tip then fails ------

    @Test
    void replayOfUsedTokenRevokesWholeFamilyAndTheLegitimateTipFails() {
        UUID tenantId = newActiveTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        RefreshToken rt1 = rotationService.recordInitialToken(newUser(ctx), tenantId, newClient(ctx), issuance("r1-" + rnd()));
        String h1 = rt1.getTokenHash();
        String h2 = "r2-" + rnd();
        rotate(h1, h2); // rt1 USED, rt2 ACTIVE

        // Replay the already-USED rt1 -> family compromised.
        RotationOutcome replay = rotate(h1, "r-should-not-exist");
        assertThat(replay.type()).isEqualTo(RotationOutcome.Type.REPLAY);
        assertThat(status(h1)).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(status(h2)).isEqualTo(RefreshTokenStatus.REVOKED); // the legitimate tip is killed too
        assertThat(refreshTokenRepository.findByTokenHash("r-should-not-exist")).isEmpty(); // no new token issued

        // The legitimate client's next refresh with rt2 now also fails (forcing full re-auth).
        RotationOutcome tip = rotationService.authorizeRotation(h2);
        assertThat(tip.type()).isEqualTo(RotationOutcome.Type.REPLAY);
    }

    // ---- Scenario 6: expired refresh token does NOT trip replay ----------

    @Test
    void expiredTokenFailsWithoutRevokingFamily() {
        UUID tenantId = newActiveTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        String h = "exp-" + rnd();
        rotationService.recordInitialToken(newUser(ctx), tenantId, newClient(ctx),
                new RefreshTokenIssuance(h, "openid", Instant.now().minus(1, ChronoUnit.HOURS), null, null));

        RotationOutcome outcome = rotationService.authorizeRotation(h);

        assertThat(outcome.type()).isEqualTo(RotationOutcome.Type.EXPIRED);
        assertThat(status(h)).isEqualTo(RefreshTokenStatus.EXPIRED); // lazily marked, not REVOKED
    }

    // ---- Scenario 8: cross-family isolation -------------------------------

    @Test
    void replayInOneFamilyDoesNotAffectAnotherFamilyOfTheSameUser() {
        UUID tenantId = newActiveTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        UUID userId = newUser(ctx);
        String client = newClient(ctx);

        RefreshToken famA = rotationService.recordInitialToken(userId, tenantId, client, issuance("a1-" + rnd()));
        RefreshToken famB = rotationService.recordInitialToken(userId, tenantId, client, issuance("b1-" + rnd()));
        rotate(famA.getTokenHash(), "a2-" + rnd());

        // replay in family A
        rotate(famA.getTokenHash(), "a-none");

        assertThat(status(famA.getTokenHash())).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(status(famB.getTokenHash())).isEqualTo(RefreshTokenStatus.ACTIVE); // family B untouched
    }

    // ---- Scenario 9: tenant isolation + revoke capabilities ---------------

    @Test
    void revokeAllUserFamiliesIsTenantScoped() {
        UUID tenantA = newActiveTenant();
        TenantContext ctxA = TenantContext.of(tenantA);
        UUID userA = newUser(ctxA);
        RefreshToken tokenA = rotationService.recordInitialToken(userA, tenantA, newClient(ctxA), issuance("ta-" + rnd()));

        UUID tenantB = newActiveTenant();
        TenantContext ctxB = TenantContext.of(tenantB);
        UUID userB = newUser(ctxB);
        RefreshToken tokenB = rotationService.recordInitialToken(userB, tenantB, newClient(ctxB), issuance("tb-" + rnd()));

        int revoked = rotationService.revokeAllUserFamilies(userA, tenantA);

        assertThat(revoked).isEqualTo(1);
        assertThat(status(tokenA.getTokenHash())).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(status(tokenB.getTokenHash())).isEqualTo(RefreshTokenStatus.ACTIVE); // tenant B untouched
    }
}
