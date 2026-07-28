package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.Emails;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.Jwt;
import com.anterka.closeauth.it.support.MailpitMessage;
import com.anterka.closeauth.it.support.OAuthFlowClient.AuthorizeOutcome;
import com.anterka.closeauth.it.support.OAuthFlowClient.LoginResult;
import com.anterka.closeauth.it.support.OAuthFlowClient.SessionState;
import com.anterka.closeauth.it.support.OAuthFlowClient.TokenResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-4's one-time-token email flows, black-box: <b>magic-link login</b> (email → captured link token → consume →
 * session with the right {@code amr}/{@code idp} claims → SSO) and <b>password reset</b> (enumeration-safe request →
 * captured token → confirm → old password dies / new works → the prior session and its tokens are actually revoked).
 * Both reuse IT-1's Mailpit capture and IT-2's {@code OAuthFlowClient}.
 *
 * <p>Independent test methods share the container stack and a tenant/two-client fixture set; each creates its own
 * unique user.
 */
class MagicLinkAndPasswordResetJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";

    private static String platformToken;
    private static String tenant;
    private static String client1;
    private static String secret1;
    private static String client2;

    @BeforeAll
    static void fixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();
        tenant = admin.provisionActiveTenant(platformToken);
        var c1 = admin.registerConfidentialClient(platformToken, tenant);
        client1 = c1.clientId();
        secret1 = c1.secret();
        client2 = admin.registerConfidentialClient(platformToken, tenant).clientId();
    }

    // ---- Part 2: magic-link login -----------------------------------------

    @Test
    @DisplayName("Magic-link: request → captured email → consume → session with amr=magic_link, idp=LOCAL_PASSWORD → SSO")
    void magicLinkLoginEstablishesSessionWithCorrectClaims() {
        // An ACTIVE user with a LOCAL_PASSWORD identity (admin-created with a password).
        String email = Fixtures.email("magic");
        adminApi().createActiveUser(platformToken, tenant, email, PASSWORD);

        // Enumeration-safe request (light touch): real + non-existent, identical response shape.
        Response real = oauthFlow().requestMagicLink(email, client1);
        Response ghost = oauthFlow().requestMagicLink(Fixtures.email("ghost"), client1);
        assertThat(real.statusCode()).isEqualTo(200);
        assertThat(ghost.statusCode()).isEqualTo(200);
        assertThat(real.asString()).isEqualTo(ghost.asString());

        // Capture the magic-link email and extract the token from the link.
        MailpitMessage message = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        String token = Emails.linkToken(message.text());

        // Consume → session established (a CLOSEAUTH_SESSION cookie, like a password login).
        SessionState session = oauthFlow().consumeMagicLink(token, client1);
        assertThat(session.sessionKey()).as("magic-link consume sets CLOSEAUTH_SESSION").isNotBlank();

        // Obtain a token via the flow from the magic-link session; assert amr (method) vs idp (identity).
        AuthorizeOutcome outcome = oauthFlow().authorizeOnly(client1, session);
        assertThat(outcome.ssoRecognized()).as("magic-link session recognized at /authorize").isTrue();
        TokenResponse tokens = oauthFlow().exchange(client1, secret1, outcome.code(), outcome.codeVerifier());

        Map<String, Object> claims = Jwt.claims(tokens.accessToken());
        @SuppressWarnings("unchecked")
        List<String> amr = (List<String>) claims.get("amr");
        assertThat(amr).as("amr reflects the magic-link method").contains("magic_link");
        assertThat(claims.get("idp")).as("idp is still the underlying identity, not the method").isEqualTo("LOCAL_PASSWORD");

        // A magic-link session is indistinguishable from a password-derived one: SSO works on a 2nd same-tenant client.
        assertThat(oauthFlow().authorizeOnly(client2, session).ssoRecognized())
                .as("SSO from the magic-link session recognized on a second client").isTrue();
    }

    // ---- Part 3: password reset -------------------------------------------

    @Test
    @DisplayName("Password reset: enumeration-safe request → confirm → old pw fails/new works → prior session+tokens revoked")
    void passwordResetRotatesCredentialsAndRevokesPriorSession() {
        String email = Fixtures.email("reset");
        adminApi().createActiveUser(platformToken, tenant, email, PASSWORD);

        // Establish a real pre-reset session + refresh family + access token — what the reset cascade must revoke.
        LoginResult preReset = oauthFlow().login(client1, secret1, email, PASSWORD);
        String preSessionKey = preReset.session().sessionKey();
        String preAccessToken = preReset.tokens().accessToken();
        assertThat(preReset.tokens().refreshToken()).as("a refresh token was issued pre-reset").isNotBlank();

        // Enumeration-safety (a real security property here): real + non-existent → identical status AND body.
        Response real = oauthFlow().requestPasswordReset(email, client1);
        Response ghost = oauthFlow().requestPasswordReset(Fixtures.email("ghost-reset"), client1);
        assertThat(real.statusCode()).isEqualTo(200);
        assertThat(ghost.statusCode()).isEqualTo(200);
        assertThat(real.asString()).as("reset response is identical whether or not the account exists").isEqualTo(ghost.asString());

        // Capture the reset email, extract the token, confirm with a new password.
        MailpitMessage message = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        String token = Emails.linkToken(message.text());
        String newPassword = "N3w-Reset-Pw!";
        assertThat(oauthFlow().confirmPasswordReset(token, newPassword, client1).statusCode()).isEqualTo(200);

        // Old password rejected; new password works end-to-end.
        Response oldLogin = oauthFlow().attemptPasswordLogin(client1, email, PASSWORD);
        assertThat(oldLogin.statusCode()).as("old password rejected after reset").isEqualTo(401);
        assertThat(oldLogin.jsonPath().getString("error")).isEqualTo("invalid_credentials");
        assertThat(oauthFlow().login(client1, secret1, email, newPassword).tokens().accessToken())
                .as("new password logs in").isNotBlank();

        // THE critical assertion: the pre-reset session and its tokens were killed by the post-reset cascade.
        Map<String, Object> sessionRow = db()
                .queryOne("select id, revoked_at from auth_server_sessions where session_key = ?", preSessionKey)
                .orElseThrow(() -> new AssertionError("no pre-reset auth_server_sessions row"));
        assertThat(sessionRow.get("revoked_at")).as("pre-reset session revoked in DB").isNotNull();

        UUID sessionLedgerId = (UUID) sessionRow.get("id");
        Map<String, Object> refresh = db()
                .queryOne("select count(*) as total, count(*) filter (where status <> 'REVOKED') as not_revoked "
                        + "from refresh_tokens where session_id = ?", sessionLedgerId)
                .orElseThrow(() -> new AssertionError("no pre-reset refresh_tokens rows"));
        assertThat(asLong(refresh.get("total"))).as("the pre-reset session had a refresh token").isGreaterThanOrEqualTo(1);
        assertThat(asLong(refresh.get("not_revoked"))).as("pre-reset refresh family is REVOKED").isZero();

        // Access token now rejected — via introspection (local /v1/** validation would NOT catch it; see IT-2 §4).
        assertThat(oauthFlow().isActive(client1, secret1, preAccessToken))
                .as("pre-reset access token inactive after reset").isFalse();
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }
}
