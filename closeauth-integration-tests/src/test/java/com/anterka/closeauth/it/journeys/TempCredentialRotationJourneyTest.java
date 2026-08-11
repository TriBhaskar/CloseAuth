package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.Emails;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.MailpitMessage;
import com.anterka.closeauth.it.support.OAuthFlowClient;
import com.anterka.closeauth.it.support.OAuthFlowClient.PkcePair;
import com.anterka.closeauth.it.support.OAuthFlowClient.SessionState;
import com.anterka.closeauth.it.support.OAuthFlowClient.TokenResponse;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 of the tenant-onboarding design ({@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md} §2.2): forced password
 * rotation. <b>Nothing in the product yet SETS {@code user_identities.must_change_password}</b> — issuance is phase
 * 3. Every fixture here sets it directly via {@link com.anterka.closeauth.it.support.Db#execute}, which is exactly
 * the point: the property under test — <i>while the flag is true, no session, authorization code, access token,
 * refresh token, or session cookie is ever issued for that user, by any path</i> — has to be provable today against
 * a row nothing in the product can create yet.
 *
 * <p>Deliberately its own class with its own fixtures (mirrors {@code LoginRateLimitJourneyTest}'s reasoning): it
 * mutates credential-lifecycle state directly, which no other journey does.
 */
class TempCredentialRotationJourneyTest extends IntegrationTest {

    private static final String TEMP_PASSWORD = "Temp-Onboarding-Pw-1!";
    private static final String NEW_PASSWORD = "Brand-New-Real-Pw-2!";

    private static String platformToken;
    private static String tenant;
    private static String client1;
    private static String secret1;

    @BeforeAll
    static void fixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();
        tenant = admin.provisionActiveTenant(platformToken);
        var c1 = admin.registerConfidentialClient(platformToken, tenant);
        client1 = c1.clientId();
        secret1 = c1.secret();
    }

    @Test
    @DisplayName("Correct temp password → rotation redirect ONLY: no session cookie, no code; SSO still requires login afterward")
    void correctTempPasswordYieldsOnlyTheRotationRedirect() {
        String email = Fixtures.email("rot-login");
        String userId = adminApi().createActiveUser(platformToken, tenant, email, TEMP_PASSWORD);
        markPendingRotation(userId, Instant.now().plus(Duration.ofDays(7)));

        Response resp = oauthFlow().attemptPasswordLogin(client1, email, TEMP_PASSWORD);

        assertThat(resp.statusCode()).isEqualTo(302);
        String location = resp.getHeader("Location");
        assertThat(location).contains("/password-rotation").contains("token=");
        assertThat(resp.getCookie("CLOSEAUTH_SESSION"))
                .as("a rotation redirect must never set the session cookie — identity proven, access withheld")
                .isNull();

        // Whatever (if anything) this response's cookie jar contains, it cannot SSO — no session was established.
        SessionState afterRotationRedirect = new SessionState(Map.copyOf(resp.getCookies()));
        assertThat(oauthFlow().authorizeOnly(client1, afterRotationRedirect).loginRequired())
                .as("no session was established by the rotation redirect").isTrue();
    }

    @Test
    @DisplayName("Magic link refuses a pending-rotation user: consume 302s to /login, no session")
    void magicLinkRefusesPendingRotationUser() {
        String email = Fixtures.email("rot-magic");
        String userId = adminApi().createActiveUser(platformToken, tenant, email, TEMP_PASSWORD);
        markPendingRotation(userId, Instant.now().plus(Duration.ofDays(7)));

        oauthFlow().requestMagicLink(email, client1);
        MailpitMessage message = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        String token = Emails.linkToken(message.text());

        SessionState session = oauthFlow().consumeMagicLink(token, client1);

        assertThat(session.sessionKey())
                .as("magic-link must refuse a pending-rotation user exactly like password login does").isNull();
    }

    @Test
    @DisplayName("Confirm with a valid token establishes the session and resumes the interrupted login exactly")
    void confirmWithValidTokenEstablishesSessionAndResumes() {
        String email = Fixtures.email("rot-confirm");
        String userId = adminApi().createActiveUser(platformToken, tenant, email, TEMP_PASSWORD);
        markPendingRotation(userId, Instant.now().plus(Duration.ofDays(7)));

        Map<String, String> jar = new HashMap<>();
        PkcePair pkce = OAuthFlowClient.pkce();
        String state = "st-" + UUID.randomUUID();

        // 1. Unauthenticated /authorize → 302 to /login (mirrors OAuthFlowClient.login()'s own step 1).
        Response init = RestAssured.given().cookies(jar).redirects().follow(false).accept("text/html")
                .queryParam("response_type", "code")
                .queryParam("client_id", client1)
                .queryParam("redirect_uri", OAuthFlowClient.REDIRECT_URI)
                .queryParam("scope", "openid")
                .queryParam("code_challenge", pkce.challenge())
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", state)
                .get("/oauth2/authorize");
        jar.putAll(init.getCookies());
        assertThat(init.statusCode()).isEqualTo(302);
        assertThat(init.getHeader("Location")).contains("/login");

        // 2. POST /login with the temp password → a ROTATION redirect, not a session (the only difference from an
        //    ordinary login() at this step). Same bare 3-param shape OAuthFlowClient.login() itself uses, so
        //    buildAuthorizeQuery(request) computes the identical value it would for an ordinary successful login.
        Response rotationRedirect = RestAssured.given().cookies(jar).redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("email", email).formParam("password", TEMP_PASSWORD).formParam("client_id", client1)
                .post("/login");
        jar.putAll(rotationRedirect.getCookies());
        assertThat(rotationRedirect.statusCode()).isEqualTo(302);
        assertThat(rotationRedirect.getCookie("CLOSEAUTH_SESSION")).isNull();
        String rotationLocation = rotationRedirect.getHeader("Location");
        assertThat(rotationLocation).contains("/password-rotation");
        String rotationToken = queryParam(rotationLocation, "token");
        String authorizeQuery = queryParam(rotationLocation, "authorize_query");
        assertThat(rotationToken).isNotBlank();

        // 3. Confirm rotation — stateless (the token alone proves everything needed); establishes the session HERE.
        Response confirmResp = oauthFlow().confirmPasswordRotation(rotationToken, NEW_PASSWORD, client1, authorizeQuery);
        assertThat(confirmResp.statusCode()).isEqualTo(302);
        String sessionCookie = confirmResp.getCookie("CLOSEAUTH_SESSION");
        assertThat(sessionCookie).as("confirm must establish a real session — the first one in this whole flow").isNotBlank();
        jar.putAll(confirmResp.getCookies());
        String resumeLocation = confirmResp.getHeader("Location");
        assertThat(resumeLocation).contains("/oauth2/authorize");

        // 4. Follow the resume URL with the now-established session → 302 to the client callback with a code
        //    (identical mechanics to OAuthFlowClient.login()'s own step 3 — urlEncodingEnabled(false) for the
        //    same already-encoded-query reason documented there).
        Response authorized = RestAssured.given().cookies(jar).redirects().follow(false)
                .urlEncodingEnabled(false).accept("text/html").get(resumeLocation);
        assertThat(authorized.statusCode()).isEqualTo(302);
        String callback = authorized.getHeader("Location");
        assertThat(callback).startsWith(OAuthFlowClient.REDIRECT_URI);
        String code = queryParam(callback, "code");
        assertThat(code).isNotBlank();

        TokenResponse tokens = oauthFlow().exchange(client1, secret1, code, pkce.verifier());
        assertThat(tokens.accessToken()).isNotBlank();

        var row = db().queryOne(
                "SELECT must_change_password, temp_credential_expires_at FROM user_identities "
                        + "WHERE user_id = ?::uuid AND idp_type = 'LOCAL_PASSWORD'", userId);
        assertThat(row).isPresent();
        assertThat(row.get().get("must_change_password")).as("rotation must clear the flag").isEqualTo(false);
        assertThat(row.get().get("temp_credential_expires_at")).as("rotation must clear the expiry").isNull();
    }

    @Test
    @DisplayName("A token minted for user A cannot rotate user B's credential")
    void tokenCannotRotateADifferentUsersCredential() {
        String emailA = Fixtures.email("rot-a");
        String emailB = Fixtures.email("rot-b");
        String userIdA = adminApi().createActiveUser(platformToken, tenant, emailA, TEMP_PASSWORD);
        String userIdB = adminApi().createActiveUser(platformToken, tenant, emailB, TEMP_PASSWORD);
        markPendingRotation(userIdA, Instant.now().plus(Duration.ofDays(7)));
        markPendingRotation(userIdB, Instant.now().plus(Duration.ofDays(7)));

        Response respA = oauthFlow().attemptPasswordLogin(client1, emailA, TEMP_PASSWORD);
        String tokenA = queryParam(respA.getHeader("Location"), "token");

        Response confirm = oauthFlow().confirmPasswordRotation(tokenA, NEW_PASSWORD, client1, null);
        assertThat(confirm.statusCode()).isEqualTo(302);

        // A's new password works; A's OLD temp password no longer does.
        assertThat(oauthFlow().attemptPasswordLogin(client1, emailA, NEW_PASSWORD).statusCode()).isEqualTo(302);
        assertThat(oauthFlow().attemptPasswordLogin(client1, emailA, TEMP_PASSWORD).statusCode()).isEqualTo(401);

        // B is untouched: B's temp password STILL produces a rotation redirect — B was never rotated by A's token.
        Response respB = oauthFlow().attemptPasswordLogin(client1, emailB, TEMP_PASSWORD);
        assertThat(respB.statusCode()).isEqualTo(302);
        assertThat(respB.getHeader("Location")).contains("/password-rotation");
        assertThat(oauthFlow().attemptPasswordLogin(client1, emailB, NEW_PASSWORD).statusCode())
                .as("B's password must NOT have been changed to A's new password").isEqualTo(401);
    }

    @Test
    @DisplayName("Expired temp credential + correct password is indistinguishable from a wrong password")
    void expiredTempCredentialIsTheUniformFailure() {
        String email = Fixtures.email("rot-expired");
        String userId = adminApi().createActiveUser(platformToken, tenant, email, TEMP_PASSWORD);
        markPendingRotation(userId, Instant.now().minusSeconds(60));

        // Reference: an ordinary bad-password 401 against a different, unrelated account.
        String reference = Fixtures.email("rot-reference");
        adminApi().createActiveUser(platformToken, tenant, reference, TEMP_PASSWORD);
        Response referenceResp = oauthFlow().attemptPasswordLogin(client1, reference, "definitely-wrong");
        assertThat(referenceResp.statusCode()).isEqualTo(401);

        // The CORRECT (but expired) temp password must be refused identically — same status, content-type, body.
        Response expiredResp = oauthFlow().attemptPasswordLogin(client1, email, TEMP_PASSWORD);
        assertThat(expiredResp.statusCode()).isEqualTo(401);
        assertThat(expiredResp.getContentType()).isEqualTo(referenceResp.getContentType());
        assertThat(expiredResp.asString())
                .as("an expired-but-correct temp credential is refused identically to a wrong password")
                .isEqualTo(referenceResp.asString());
    }

    // ---- helpers ------------------------------------------------------------

    /** Sets the flag by hand — see the class javadoc: nothing in the product does this yet. */
    private void markPendingRotation(String userId, Instant expiresAt) {
        int updated = db().execute(
                "UPDATE user_identities SET must_change_password = true, temp_credential_expires_at = ? "
                        + "WHERE user_id = ?::uuid AND idp_type = 'LOCAL_PASSWORD'",
                Timestamp.from(expiresAt), userId);
        assertThat(updated).as("exactly one LOCAL_PASSWORD identity marked pending rotation").isEqualTo(1);
    }

    private static String queryParam(String url, String name) {
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(name)) {
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
