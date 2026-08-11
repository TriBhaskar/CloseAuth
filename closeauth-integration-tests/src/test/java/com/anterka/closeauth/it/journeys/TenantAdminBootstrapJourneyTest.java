package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.Emails;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.MailpitMessage;
import com.anterka.closeauth.it.support.OAuthFlowClient;
import com.anterka.closeauth.it.support.OAuthFlowClient.PkcePair;
import com.anterka.closeauth.it.support.OAuthFlowClient.TokenResponse;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 of the tenant-onboarding design ({@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md} §2.4–§2.9): the ISSUANCE
 * half. {@link TempCredentialRotationJourneyTest} (Phase 2) proves the rotation GATE against a row constructed
 * directly via SQL; this class proves the first real, product-driven path to that row — platform-admin bootstrap
 * and reissue — end to end through both on-ramps to a real access token. The two classes deliberately don't
 * duplicate each other's coverage: this one asserts issuance-specific behavior (the 409s, the email, the DB row
 * bootstrap itself produces) and reuses the gate's own proof by driving it through {@code /login} and
 * {@code /password-rotation/confirm} rather than re-asserting the gate's internals.
 */
class TenantAdminBootstrapJourneyTest extends IntegrationTest {

    private static final String NEW_PASSWORD = "Brand-New-Real-Pw-1!";

    @Test
    @DisplayName("Bootstrap creates an ACTIVE TENANT_ADMIN with a pending temp credential and emails a link, never the password")
    void bootstrapCreatesActiveAdminWithPendingRotationAndEmailsTheLink() {
        String platformToken = platformAdminToken();
        String tenantId = adminApi().provisionActiveTenant(platformToken);
        String email = Fixtures.email("bootstrap-admin");

        Response bootstrap = adminApi().bootstrapTenantAdmin(platformToken, tenantId, email);
        assertThat(bootstrap.statusCode()).isEqualTo(201);
        String userId = bootstrap.jsonPath().getString("user.id");
        String temporaryPassword = bootstrap.jsonPath().getString("temporaryPassword");
        assertThat(userId).isNotBlank();
        assertThat(temporaryPassword).isNotBlank();
        assertThat(bootstrap.jsonPath().getString("user.status")).isEqualTo("ACTIVE");

        var credentialRow = db().queryOne(
                "SELECT must_change_password, temp_credential_expires_at FROM user_identities "
                        + "WHERE user_id = ?::uuid AND idp_type = 'LOCAL_PASSWORD'", userId);
        assertThat(credentialRow).isPresent();
        assertThat(credentialRow.get().get("must_change_password")).isEqualTo(true);
        assertThat(credentialRow.get().get("temp_credential_expires_at")).isNotNull();

        var adminRoleRow = db().queryOne(
                "SELECT 1 FROM user_tenant_roles utr JOIN tenant_roles tr ON tr.id = utr.tenant_role_id "
                        + "WHERE utr.user_id = ?::uuid AND utr.tenant_id = ?::uuid AND tr.name = 'TENANT_ADMIN'",
                userId, tenantId);
        assertThat(adminRoleRow).as("bootstrap must grant TENANT_ADMIN in the same transaction").isPresent();

        MailpitMessage message = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        assertThat(message.text())
                .as("the onboarding email must carry a link, never the temporary password itself")
                .doesNotContain(temporaryPassword);
        assertThat(Emails.linkToken(message.text())).isNotBlank();
    }

    @Test
    @DisplayName("On-ramp 1 (emailed link): confirming the mailed token establishes a real session")
    void onRampEmailedLinkEstablishesARealSession() {
        String platformToken = platformAdminToken();
        String tenantId = adminApi().provisionActiveTenant(platformToken);
        var client = adminApi().registerConfidentialClient(platformToken, tenantId);
        String email = Fixtures.email("onramp-email");

        Response bootstrap = adminApi().bootstrapTenantAdmin(platformToken, tenantId, email);
        assertThat(bootstrap.statusCode()).isEqualTo(201);
        String userId = bootstrap.jsonPath().getString("user.id");

        MailpitMessage message = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        String token = Emails.linkToken(message.text());

        Response confirm = oauthFlow().confirmPasswordRotation(token, NEW_PASSWORD, client.clientId(), null);
        assertThat(confirm.statusCode()).isEqualTo(302);
        assertThat(confirm.getCookie("CLOSEAUTH_SESSION"))
                .as("confirming the emailed link must establish a real session").isNotBlank();

        var credentialRow = db().queryOne(
                "SELECT must_change_password, temp_credential_expires_at FROM user_identities "
                        + "WHERE user_id = ?::uuid AND idp_type = 'LOCAL_PASSWORD'", userId);
        assertThat(credentialRow.get().get("must_change_password")).isEqualTo(false);
        assertThat(credentialRow.get().get("temp_credential_expires_at")).isNull();

        // The temp password is now dead; the newly-set one works.
        assertThat(oauthFlow().attemptPasswordLogin(client.clientId(), email, NEW_PASSWORD).statusCode()).isEqualTo(302);
    }

    @Test
    @DisplayName("On-ramp 2 (temp-password login): resumes the interrupted authorize request all the way to a token")
    void onRampTempPasswordLoginResumesToARealToken() {
        String platformToken = platformAdminToken();
        String tenantId = adminApi().provisionActiveTenant(platformToken);
        var client = adminApi().registerConfidentialClient(platformToken, tenantId);
        String email = Fixtures.email("onramp-login");

        Response bootstrap = adminApi().bootstrapTenantAdmin(platformToken, tenantId, email);
        assertThat(bootstrap.statusCode()).isEqualTo(201);
        String temporaryPassword = bootstrap.jsonPath().getString("temporaryPassword");

        Map<String, String> jar = new HashMap<>();
        PkcePair pkce = OAuthFlowClient.pkce();
        String state = "st-" + UUID.randomUUID();

        Response init = RestAssured.given().cookies(jar).redirects().follow(false).accept("text/html")
                .queryParam("response_type", "code")
                .queryParam("client_id", client.clientId())
                .queryParam("redirect_uri", OAuthFlowClient.REDIRECT_URI)
                .queryParam("scope", "openid")
                .queryParam("code_challenge", pkce.challenge())
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", state)
                .get("/oauth2/authorize");
        jar.putAll(init.getCookies());
        assertThat(init.statusCode()).isEqualTo(302);
        assertThat(init.getHeader("Location")).contains("/login");

        Response rotationRedirect = RestAssured.given().cookies(jar).redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("email", email).formParam("password", temporaryPassword).formParam("client_id", client.clientId())
                .post("/login");
        jar.putAll(rotationRedirect.getCookies());
        assertThat(rotationRedirect.statusCode()).isEqualTo(302);
        assertThat(rotationRedirect.getCookie("CLOSEAUTH_SESSION")).isNull();
        String rotationLocation = rotationRedirect.getHeader("Location");
        assertThat(rotationLocation).contains("/password-rotation");
        String rotationToken = queryParam(rotationLocation, "token");
        String authorizeQuery = queryParam(rotationLocation, "authorize_query");

        Response confirmResp = oauthFlow().confirmPasswordRotation(rotationToken, NEW_PASSWORD, client.clientId(), authorizeQuery);
        assertThat(confirmResp.statusCode()).isEqualTo(302);
        jar.putAll(confirmResp.getCookies());
        String resumeLocation = confirmResp.getHeader("Location");
        assertThat(resumeLocation).contains("/oauth2/authorize");

        Response authorized = RestAssured.given().cookies(jar).redirects().follow(false)
                .urlEncodingEnabled(false).accept("text/html").get(resumeLocation);
        assertThat(authorized.statusCode()).isEqualTo(302);
        String callback = authorized.getHeader("Location");
        assertThat(callback).startsWith(OAuthFlowClient.REDIRECT_URI);
        String code = queryParam(callback, "code");
        assertThat(code).isNotBlank();

        TokenResponse tokens = oauthFlow().exchange(client.clientId(), client.secret(), code, pkce.verifier());
        assertThat(tokens.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("Bootstrapping a tenant that already has an active admin is refused")
    void secondBootstrapOnTheSameTenantIsRefused() {
        String platformToken = platformAdminToken();
        String tenantId = adminApi().provisionActiveTenant(platformToken);

        Response first = adminApi().bootstrapTenantAdmin(platformToken, tenantId, Fixtures.email("first-admin"));
        assertThat(first.statusCode()).isEqualTo(201);

        Response second = adminApi().bootstrapTenantAdmin(platformToken, tenantId, Fixtures.email("second-admin"));
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(AdminApiClient.problemCode(second)).isEqualTo("tenant_onboarding.admin_already_exists");
    }

    @Test
    @DisplayName("Reissue on an un-rotated user invalidates the old temp password and the old emailed token")
    void reissueInvalidatesThePriorCredentialAndToken() {
        String platformToken = platformAdminToken();
        String tenantId = adminApi().provisionActiveTenant(platformToken);
        var client = adminApi().registerConfidentialClient(platformToken, tenantId);
        String email = Fixtures.email("reissue-target");

        Response bootstrap = adminApi().bootstrapTenantAdmin(platformToken, tenantId, email);
        assertThat(bootstrap.statusCode()).isEqualTo(201);
        String userId = bootstrap.jsonPath().getString("user.id");
        String firstPassword = bootstrap.jsonPath().getString("temporaryPassword");
        MailpitMessage firstMessage = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        String firstToken = Emails.linkToken(firstMessage.text());

        Response reissue = adminApi().reissueOnboardingCredential(platformToken, tenantId, userId);
        assertThat(reissue.statusCode()).isEqualTo(200);
        String secondPassword = reissue.jsonPath().getString("temporaryPassword");
        assertThat(secondPassword).isNotEqualTo(firstPassword);

        // The old temp password no longer works; the new one produces the rotation redirect.
        assertThat(oauthFlow().attemptPasswordLogin(client.clientId(), email, firstPassword).statusCode()).isEqualTo(401);
        Response secondLogin = oauthFlow().attemptPasswordLogin(client.clientId(), email, secondPassword);
        assertThat(secondLogin.statusCode()).isEqualTo(302);
        assertThat(secondLogin.getHeader("Location")).contains("/password-rotation");

        // The FIRST emailed token was invalidated by the reissue (invalidate-then-issue, §2.3) — confirming it fails.
        Response confirmWithStaleToken = oauthFlow().confirmPasswordRotation(firstToken, "Whatever-Pw-1!", client.clientId(), null);
        assertThat(confirmWithStaleToken.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("Reissue on a user who already completed rotation is refused")
    void reissueOnAnAlreadyRotatedUserIsRefused() {
        String platformToken = platformAdminToken();
        String tenantId = adminApi().provisionActiveTenant(platformToken);
        var client = adminApi().registerConfidentialClient(platformToken, tenantId);
        String email = Fixtures.email("already-rotated");

        Response bootstrap = adminApi().bootstrapTenantAdmin(platformToken, tenantId, email);
        assertThat(bootstrap.statusCode()).isEqualTo(201);
        String userId = bootstrap.jsonPath().getString("user.id");
        MailpitMessage message = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        String token = Emails.linkToken(message.text());
        assertThat(oauthFlow().confirmPasswordRotation(token, NEW_PASSWORD, client.clientId(), null).statusCode())
                .isEqualTo(302);

        Response reissue = adminApi().reissueOnboardingCredential(platformToken, tenantId, userId);
        assertThat(reissue.statusCode()).isEqualTo(409);
        assertThat(AdminApiClient.problemCode(reissue)).isEqualTo("tenant_onboarding.no_pending_temp_credential");
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
