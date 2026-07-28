package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient.ClientCredentials;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.Jwt;
import com.anterka.closeauth.it.support.OAuthFlowClient.AuthorizeOutcome;
import com.anterka.closeauth.it.support.OAuthFlowClient.LoginResult;
import com.anterka.closeauth.it.support.OAuthFlowClient.TokenResponse;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-11's protocol-completeness journey, black-box — the roadmap closer. The one item that matters most: every prior
 * stage only <em>decoded</em> JWT claims (no signature check); this stage <b>cryptographically verifies</b> a real
 * token's RS256 signature against the published JWKS (with a tampered-token negative control to prove the check has
 * teeth). Plus the remaining thin-coverage protocol surface: discovery, {@code /userinfo}, RFC 7009 {@code /oauth2/revoke},
 * the refresh grant's clean positive path, the sequential-replay whole-family revocation proof, the OTP attempt-lockout
 * at its real configured threshold, and a light look at SAS's stock {@code /connect/logout}.
 *
 * <p>Independent test methods share the container stack + one trusted {@code openid profile} client; each token-lifecycle
 * scenario creates its own user so refresh families / revocation markers never cross methods.
 */
class ProtocolCompletenessJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";
    /** {@code closeauth.one-time-token.verify-max-attempts-per-window} (application.yml; not overridden in docker). */
    private static final int VERIFY_MAX_ATTEMPTS = 5;

    private static String platformToken;
    private static String tenant;
    private static String clientId;
    private static String clientSecret;

    @BeforeAll
    static void fixtures() {
        platformToken = platformAdminToken();
        tenant = adminApi().provisionActiveTenant(platformToken);
        // A trusted confidential client registered for openid+profile (profile is needed for the /userinfo scenario;
        // trusted so the extra scope is granted without a consent screen). Confidential → refresh tokens are issued.
        clientId = Fixtures.clientId("proto");
        ClientCredentials client = adminApi().registerClient(platformToken, tenant, clientId, clientId, true,
                List.of("openid", "profile"));
        clientSecret = client.secret();
    }

    // ===================== Part 1 — real signature verification =====================

    @Test
    @DisplayName("Scenario 1: a real token's RS256 signature verifies against the published JWKS; a tampered one fails")
    void signatureVerifiesAgainstJwksAndTamperedTokenFails() throws Exception {
        String token = login(freshUser().email()).tokens().accessToken();

        Response jwks = RestAssured.given().get("/oauth2/jwks");
        assertThat(jwks.statusCode()).isEqualTo(200);
        JWKSet jwkSet = JWKSet.parse(jwks.asString());

        SignedJWT jwt = SignedJWT.parse(token);
        String kid = jwt.getHeader().getKeyID();
        assertThat(kid).as("the token header carries a kid").isNotBlank();
        RSAKey signingKey = (RSAKey) jwkSet.getKeyByKeyId(kid);
        assertThat(signingKey).as("the JWKS publishes the token's signing key, matched by kid").isNotNull();

        RSASSAVerifier verifier = new RSASSAVerifier(signingKey.toRSAPublicKey());
        assertThat(jwt.verify(verifier))
                .as("the genuine token's signature verifies against the published public key").isTrue();

        // Negative control: flip one character of the signature segment → verification must fail (the check has teeth).
        SignedJWT tampered = SignedJWT.parse(tamperSignature(token));
        assertThat(tampered.verify(verifier)).as("a tampered signature must NOT verify").isFalse();
    }

    @Test
    @DisplayName("Scenario 2: the discovery document is present and self-consistent; jwks_uri points at /oauth2/jwks")
    void discoveryDocumentIsSelfConsistent() {
        Response disc = RestAssured.given().get("/.well-known/openid-configuration");
        assertThat(disc.statusCode()).isEqualTo(200);

        String issuer = disc.jsonPath().getString("issuer");
        assertThat(issuer).as("issuer present").isNotBlank();
        // Endpoints are self-consistent: all built on the same issuer base, and jwks_uri is the endpoint used in step 1.
        assertThat(disc.jsonPath().getString("authorization_endpoint")).isEqualTo(issuer + "/oauth2/authorize");
        assertThat(disc.jsonPath().getString("token_endpoint")).isEqualTo(issuer + "/oauth2/token");
        String jwksUri = disc.jsonPath().getString("jwks_uri");
        assertThat(jwksUri).isEqualTo(issuer + "/oauth2/jwks");
        assertThat(jwksUri).as("jwks_uri points at the JWKS endpoint verified in scenario 1").endsWith("/oauth2/jwks");
    }

    // ===================== Part 2 — /userinfo and /oauth2/revoke =====================

    @Test
    @DisplayName("Scenario 3: /userinfo returns the user's sub for a valid token; rejects no/invalid token with 401")
    void userinfoWorksAndRejectsUnauthenticated() {
        UserRef user = freshUser();
        // A token carrying openid+profile: establish the session, then authorize for the wider scope (trusted → no consent).
        LoginResult login = login(user.email());
        AuthorizeOutcome authorized = oauthFlow().authorize(clientId, login.session(), "openid profile");
        assertThat(authorized.ssoRecognized()).isTrue();
        TokenResponse tokens = oauthFlow().exchange(clientId, clientSecret, authorized.code(), authorized.codeVerifier());

        Response userinfo = RestAssured.given().auth().oauth2(tokens.accessToken()).get("/userinfo");
        assertThat(userinfo.statusCode()).isEqualTo(200);
        assertThat(userinfo.jsonPath().getString("sub")).as("sub is the known user").isEqualTo(user.userId());

        // Reject unauthenticated callers with 401. NOTE: send `Accept: application/json` (a real API client) — a
        // browser-style request (default `Accept: */*`) instead matches the SAS chain's text/html entry point and is
        // redirected (302) to /login rather than 401'd. The 401 is the API contract; the redirect is the UI affordance.
        assertThat(RestAssured.given().accept(ContentType.JSON).get("/userinfo").statusCode())
                .as("no token → 401 for a JSON API client").isEqualTo(401);
        assertThat(RestAssured.given().accept(ContentType.JSON).auth().oauth2("garbage.invalid.token").get("/userinfo")
                .statusCode()).as("garbage token → 401").isEqualTo(401);
    }

    @Test
    @DisplayName("Scenario 4: /oauth2/revoke (RFC 7009) revokes a refresh token, is 200 even when already invalid, kills reuse")
    void revokeRefreshTokenPerRfc7009() {
        TokenResponse tokens = login(freshUser().email()).tokens();
        String refresh = tokens.refreshToken();
        assertThat(refresh).isNotBlank();

        assertThat(oauthFlow().revoke(clientId, clientSecret, refresh, "refresh_token").statusCode())
                .as("revocation succeeds").isEqualTo(200);
        // RFC 7009: revoking an already-invalid token STILL returns 200 (no enumeration oracle).
        assertThat(oauthFlow().revoke(clientId, clientSecret, refresh, "refresh_token").statusCode())
                .as("re-revoking an already-invalid token is still 200 (no oracle)").isEqualTo(200);

        // The revoked refresh token no longer works.
        Response reuse = oauthFlow().refresh(clientId, clientSecret, refresh);
        assertThat(reuse.statusCode()).as("a revoked refresh token cannot rotate").isEqualTo(400);
        assertThat(reuse.jsonPath().getString("error")).isEqualTo("invalid_grant");
    }

    // ===================== Part 3 — refresh positive path & sequential replay =====================

    @Test
    @DisplayName("Scenario 5: the refresh grant's positive path issues a genuinely new, valid, correctly-claimed token")
    void refreshPositivePath() {
        UserRef user = freshUser();
        LoginResult login = login(user.email());
        String access1 = login.tokens().accessToken();
        String refresh1 = login.tokens().refreshToken();

        Response refreshed = oauthFlow().refresh(clientId, clientSecret, refresh1);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String access2 = refreshed.jsonPath().getString("access_token");
        String refresh2 = refreshed.jsonPath().getString("refresh_token");

        assertThat(access2).as("a new access token was issued").isNotBlank();
        assertThat(access2).as("it is a genuinely different token, not a no-op").isNotEqualTo(access1);
        assertThat(refresh2).as("rotation issues a new refresh token too").isNotEqualTo(refresh1);

        Map<String, Object> claims = Jwt.claims(access2);
        assertThat(claims.get("sub")).as("correctly claimed: sub").isEqualTo(user.userId());
        assertThat(claims.get("tenant_id")).as("correctly claimed: tenant_id").isEqualTo(tenant);
        assertThat(oauthFlow().isActive(clientId, clientSecret, access2))
                .as("the new access token is genuinely valid (introspects active)").isTrue();
    }

    @Test
    @DisplayName("Scenario 6: sequential replay of RT1 revokes the WHOLE family — RT2 (the legit child) also dies")
    void sequentialReplayKillsTheWholeFamily() {
        String rt1 = login(freshUser().email()).tokens().refreshToken();

        // Rotate RT1 -> RT2 (a normal, legitimate refresh).
        Response rotate = oauthFlow().refresh(clientId, clientSecret, rt1);
        assertThat(rotate.statusCode()).isEqualTo(200);
        String rt2 = rotate.jsonPath().getString("refresh_token");
        assertThat(rt2).isNotBlank();

        // Present RT1 again (now USED) → replay → rejected, and this revokes the ENTIRE family.
        Response replay = oauthFlow().refresh(clientId, clientSecret, rt1);
        assertThat(replay.statusCode()).as("replaying the consumed RT1 is rejected").isEqualTo(400);
        assertThat(replay.jsonPath().getString("error")).isEqualTo("invalid_grant");

        // The key proof: RT2 — the legitimate child, never itself replayed — is now ALSO dead.
        Response child = oauthFlow().refresh(clientId, clientSecret, rt2);
        assertThat(child.statusCode())
                .as("a replay kills the whole family: the legit child RT2 no longer works either").isEqualTo(400);
        assertThat(child.jsonPath().getString("error")).isEqualTo("invalid_grant");
    }

    // ===================== Part 4 — rate-limit / lockout =====================

    @Test
    @DisplayName("Scenario 7-8: the verify attempt-lockout trips at the configured threshold (5 → 400s, 6th → 429)")
    void verifyAttemptLockoutTripsAtConfiguredThreshold() {
        UserRef user = freshUser();

        // Request a real verification code for the target (always 200; the code is emailed).
        Response request = RestAssured.given().contentType(ContentType.URLENC)
                .formParam("email", user.email()).formParam("client_id", clientId).post("/verify-email/request");
        assertThat(request.statusCode()).isEqualTo(200);

        // Submit VERIFY_MAX_ATTEMPTS wrong codes — each within the window is a generic 400 (never reveals why).
        for (int attempt = 1; attempt <= VERIFY_MAX_ATTEMPTS; attempt++) {
            assertThat(confirm(user.email(), "000000").statusCode())
                    .as("attempt " + attempt + " within the limit → generic 400").isEqualTo(400);
        }
        // The next attempt is past the limit → the brute-force lockout returns 429.
        assertThat(confirm(user.email(), "000000").statusCode())
                .as("attempt " + (VERIFY_MAX_ATTEMPTS + 1) + " past the limit → 429 lockout").isEqualTo(429);
    }

    // ===================== Part 5 — /connect/logout (light touch) =====================

    @Test
    @DisplayName("Scenario 10: SAS's stock /connect/logout is a real, handled endpoint (light exploratory)")
    void connectLogoutLightExploration() {
        Response resp = RestAssured.given().redirects().follow(false).get("/connect/logout");
        // FINDING (light exploration): SAS's stock OIDC RP-initiated logout is wired and reachable, but a bare
        // GET /connect/logout with no parameters is rejected with 400 Bad Request (it expects a valid logout request,
        // e.g. an `id_token_hint` / an authenticated session). CloseAuth's own hand-written POST /logout (IT-2/IT-9)
        // owns the security-relevant tenant-scoped revoke cascade; this endpoint is SAS default and not relied upon.
        assertThat(resp.statusCode()).as("/connect/logout is a real, handled endpoint (not 404, not a 5xx)")
                .isNotEqualTo(404);
        assertThat(resp.statusCode()).isLessThan(500);
    }

    // ---- helpers -----------------------------------------------------------

    private static LoginResult login(String email) {
        return oauthFlow().login(clientId, clientSecret, email, PASSWORD);
    }

    private static UserRef freshUser() {
        String email = Fixtures.email("proto");
        String userId = adminApi().createActiveUser(platformToken, tenant, email, PASSWORD);
        return new UserRef(userId, email);
    }

    private static Response confirm(String email, String code) {
        return RestAssured.given().contentType(ContentType.URLENC)
                .formParam("email", email).formParam("code", code).formParam("client_id", clientId)
                .post("/verify-email/confirm");
    }

    /** Flips one character of the JWT's signature segment to a different (still base64url-valid) character. */
    private static String tamperSignature(String token) {
        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        char flipped = (first == 'A') ? 'B' : 'A';
        parts[2] = flipped + parts[2].substring(1);
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private record UserRef(String userId, String email) {
    }
}
