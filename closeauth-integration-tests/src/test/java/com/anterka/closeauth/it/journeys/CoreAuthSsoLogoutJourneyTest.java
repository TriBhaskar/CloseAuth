package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.Jwt;
import com.anterka.closeauth.it.support.OAuthFlowClient.AuthorizeOutcome;
import com.anterka.closeauth.it.support.OAuthFlowClient.LoginResult;
import com.anterka.closeauth.it.support.OAuthFlowClient.TokenResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-2's core interactive-auth journey, driven black-box through the real OAuth2 Authorization Code + PKCE flow
 * ({@link OAuthFlowClient}): password login issues correct tenant-scoped tokens; SSO is recognized on a second client
 * in the SAME tenant; SSO is REFUSED for a client in a DIFFERENT tenant (the tenant-isolation proof — the single most
 * important assertion here); and logout actually kills the session and its tokens (verified in the DB and via
 * introspection).
 *
 * <p>Fixtures are admin-created once (registration/verification is already proven by IT-1, so an ACTIVE user is created
 * directly). Each scenario is an independent test that logs in fresh — they share the container stack, not per-test
 * session state, so there is no ordering coupling.
 */
class CoreAuthSsoLogoutJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";

    private static String adminToken;
    private static String tenantA;
    private static String tenantB;
    private static String clientA1;
    private static String secretA1;
    private static String clientA2;
    private static String secretA2;
    private static String clientB1;
    private static String secretB1;
    private static String userId;
    private static String userEmail;

    @BeforeAll
    static void provisionFixtures() {
        // Runs after IntegrationTest.bootStack() (superclass @BeforeAll) — stack is up, REST Assured points at the app.
        AdminApiClient admin = adminApi();
        adminToken = platformAdminToken();

        tenantA = admin.provisionActiveTenant(adminToken);
        tenantB = admin.provisionActiveTenant(adminToken);

        var a1 = admin.registerConfidentialClient(adminToken, tenantA);
        clientA1 = a1.clientId();
        secretA1 = a1.secret();
        var a2 = admin.registerConfidentialClient(adminToken, tenantA);
        clientA2 = a2.clientId();
        secretA2 = a2.secret();
        var b1 = admin.registerConfidentialClient(adminToken, tenantB);
        clientB1 = b1.clientId();
        secretB1 = b1.secret();

        userEmail = Fixtures.email("user");
        userId = admin.createActiveUser(adminToken, tenantA, userEmail, PASSWORD);
    }

    // ---- Scenario 1: login issues correct tokens --------------------------

    @Test
    @DisplayName("Scenario 1: password login establishes a session and issues correctly-shaped Tenant-A tokens")
    void loginEstablishesSessionAndCorrectTokens() {
        LoginResult login = oauthFlow().login(clientA1, secretA1, userEmail, PASSWORD);

        assertThat(login.tokens().accessToken()).as("access token issued").isNotBlank();
        assertThat(login.tokens().refreshToken()).as("refresh token issued (confidential client)").isNotBlank();
        assertThat(login.session().sessionKey()).as("CLOSEAUTH_SESSION cookie set").isNotBlank();

        Map<String, Object> claims = Jwt.claims(login.tokens().accessToken());
        assertThat(claims.get("sub")).as("sub is the user UUID, not the email").isEqualTo(userId);
        assertThat(claims.get("sub")).isNotEqualTo(userEmail);
        assertThat(claims.get("tenant_id")).as("tenant_id is Tenant A").isEqualTo(tenantA);
    }

    // ---- Scenario 2: SSO recognized, same tenant --------------------------

    @Test
    @DisplayName("Scenario 2: SSO is recognized on a second client in the same tenant (no re-login)")
    void ssoRecognizedOnSecondClientSameTenant() {
        LoginResult login = oauthFlow().login(clientA1, secretA1, userEmail, PASSWORD);

        AuthorizeOutcome outcome = oauthFlow().authorizeOnly(clientA2, login.session());
        assertThat(outcome.ssoRecognized()).as("SSO must recognize the Tenant-A session on client A2").isTrue();
        assertThat(outcome.location()).as("must NOT bounce to /login").doesNotContain("/login");
        assertThat(outcome.code()).as("an authorization code is issued directly").isNotBlank();

        // The SSO-issued code exchanges into a token that is still correctly Tenant-A shaped.
        TokenResponse tokens = oauthFlow().exchange(clientA2, secretA2, outcome.code(), outcome.codeVerifier());
        Map<String, Object> claims = Jwt.claims(tokens.accessToken());
        assertThat(claims.get("tenant_id")).isEqualTo(tenantA);
        assertThat(claims.get("sub")).isEqualTo(userId);
    }

    // ---- Scenario 3: SSO NOT recognized, cross-tenant (THE key assertion) --

    @Test
    @DisplayName("Scenario 3: SSO is REFUSED for a client in a different tenant (tenant isolation)")
    void ssoNotRecognizedCrossTenant() {
        LoginResult login = oauthFlow().login(clientA1, secretA1, userEmail, PASSWORD);

        // Present the SAME Tenant-A session to a Tenant-B client. It must NOT be honored.
        AuthorizeOutcome outcome = oauthFlow().authorizeOnly(clientB1, login.session());
        assertThat(outcome.ssoRecognized())
                .as("a Tenant-A session must NEVER authorize a Tenant-B client (cross-tenant session leak)")
                .isFalse();
        assertThat(outcome.location()).as("must redirect to /login (re-auth required)").contains("/login");
        assertThat(outcome.code()).as("no authorization code issued across tenants").isNull();
    }

    // ---- Scenario 4: logout kills the session and its tokens --------------

    @Test
    @DisplayName("Scenario 4: logout revokes the session, its refresh family, and its access token")
    void logoutKillsSessionAndTokens() {
        LoginResult login = oauthFlow().login(clientA1, secretA1, userEmail, PASSWORD);
        String sessionKey = login.session().sessionKey();
        String accessToken = login.tokens().accessToken();

        // Sanity: before logout the access token introspects as active.
        assertThat(oauthFlow().isActive(clientA1, secretA1, accessToken)).as("token active before logout").isTrue();

        oauthFlow().logout(clientA1, login.session());

        // (a) SSO no longer recognizes the (now-revoked) session — re-auth required.
        AuthorizeOutcome after = oauthFlow().authorizeOnly(clientA1, login.session());
        assertThat(after.ssoRecognized()).as("logged-out session must not be recognized").isFalse();
        assertThat(after.location()).contains("/login");

        // (b) DB: the session ledger row is marked revoked.
        Map<String, Object> sessionRow = db()
                .queryOne("select id, revoked_at from auth_server_sessions where session_key = ?", sessionKey)
                .orElseThrow(() -> new AssertionError("no auth_server_sessions row for the session key"));
        assertThat(sessionRow.get("revoked_at")).as("auth_server_sessions.revoked_at set").isNotNull();

        // (c) DB: every refresh token in the session's family is REVOKED (not ACTIVE).
        UUID sessionLedgerId = (UUID) sessionRow.get("id");
        Map<String, Object> refresh = db()
                .queryOne("select count(*) as total, "
                        + "count(*) filter (where status <> 'REVOKED') as not_revoked "
                        + "from refresh_tokens where session_id = ?", sessionLedgerId)
                .orElseThrow(() -> new AssertionError("refresh_tokens count query returned no row"));
        assertThat(asLong(refresh.get("total"))).as("a refresh token was issued for the session").isGreaterThanOrEqualTo(1);
        assertThat(asLong(refresh.get("not_revoked"))).as("all of the session's refresh tokens are REVOKED").isZero();

        // (d) The access token is now rejected. NOTE: the admin resource server (/v1/**) validates JWTs locally and
        // does NOT consult the user revocation marker (revocation is enforced at introspection + the short TTL — the
        // documented stateless-token posture), so we assert rejection via /oauth2/introspect, which DOES consult it.
        assertThat(oauthFlow().isActive(clientA1, secretA1, accessToken)).as("access token rejected after logout").isFalse();
    }

    // ---- fixtures ----------------------------------------------------------

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }
}
