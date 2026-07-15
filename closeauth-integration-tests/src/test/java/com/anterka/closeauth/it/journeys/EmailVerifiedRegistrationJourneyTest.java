package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.MailpitMessage;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * IT-1's single, plumbing-proving journey: self-registration in {@code EMAIL_VERIFIED} mode → read the verification
 * code from the email Mailpit captured → confirm → login → verify the final state directly in Postgres.
 *
 * <p>It is intentionally small but touches every piece of harness plumbing: booting the real app image networked with
 * Postgres/Redis/Mailpit, driving it over HTTP, capturing a real outbound email and extracting a code from its body
 * (no backdoor), and a direct read-only JDBC assertion. A failure here should read as "the harness is wrong", not "an
 * app bug".
 */
class EmailVerifiedRegistrationJourneyTest extends IntegrationTest {

    private static final Pattern SIX_DIGIT_CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Test
    @DisplayName("EMAIL_VERIFIED self-registration: register → capture emailed code → verify → login → ACTIVE in DB")
    void registersVerifiesAndLogsIn() {
        String adminToken = platformAdminToken();

        // 1. Provision a fresh tenant and activate it (platform-admin API).
        String slug = Fixtures.slug("acme");
        String tenantId = given()
                .auth().oauth2(adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("slug", slug, "name", "Acme " + slug))
                .when().post("/v1/platform/tenants")
                .then().statusCode(201)
                .body("slug", equalTo(slug))
                .body("status", equalTo("PROVISIONING"))
                .extract().path("id");

        given()
                .auth().oauth2(adminToken)
                .when().post("/v1/platform/tenants/{id}/activate", tenantId)
                .then().statusCode(200)
                .body("status", equalTo("ACTIVE"));

        // 2. Set the tenant's registration mode to EMAIL_VERIFIED (explicit, not relying on the platform default).
        given()
                .auth().oauth2(adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("mode", "EMAIL_VERIFIED"))
                .when().put("/v1/tenants/{tid}/registration-config", tenantId)
                .then().statusCode(200)
                .body("mode", equalTo("EMAIL_VERIFIED"));

        // 3. Register a client — the interactive flows resolve tenant context from client_id.
        String clientId = Fixtures.clientId("web");
        registerClient(adminToken, tenantId, clientId);

        // 4. Self-register a user → PENDING, verification email dispatched.
        String email = Fixtures.email("alice");
        String password = "Sup3r-Secret-Pw!";
        given()
                .contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("password", password)
                .formParam("client_id", clientId)
                .when().post("/register")
                .then().statusCode(200)
                .body("userId", not(emptyOrNullString()))
                .body("status", equalTo("PENDING"))
                .body("mode", equalTo("EMAIL_VERIFIED"))
                .body("emailVerificationSent", equalTo(true));

        // 5. Capture the verification email from Mailpit and extract the 6-digit code from its body (no backdoor).
        MailpitMessage message = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        assertThat(message.recipients()).contains(email);
        String code = extractSixDigitCode(message.text());

        // 6. Confirm verification with the extracted code → activates the account.
        given()
                .contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("code", code)
                .formParam("client_id", clientId)
                .when().post("/verify-email/confirm")
                .then().statusCode(200);

        // 7. Log in with the same credentials → 302 back into the OAuth flow, with a session cookie set.
        given()
                .redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("password", password)
                .formParam("client_id", clientId)
                .when().post("/login")
                .then().statusCode(302)
                .header("Set-Cookie", containsString("CLOSEAUTH_SESSION"));

        // 8. The real proof: read the user row directly from Postgres (read-only) — ACTIVE and email-verified.
        Map<String, Object> user = db()
                .queryOne("select status, email_verified from users where email = ?", email)
                .orElseThrow(() -> new AssertionError("Expected a users row for " + email + " but found none"));
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat(user.get("email_verified")).isEqualTo(true);
    }

    private void registerClient(String adminToken, String tenantId, String clientId) {
        // Map.of rejects null values; clientSecret is null for a public (PKCE) client.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("clientName", "Web App " + clientId);
        body.put("clientSecret", null);
        body.put("grantTypes", List.of("authorization_code", "refresh_token"));
        body.put("scopes", List.of("openid"));
        body.put("redirectUris", List.of("http://localhost:12345/callback"));
        body.put("requireProofKey", true);
        body.put("trusted", true);

        given()
                .auth().oauth2(adminToken)
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/v1/tenants/{tid}/clients", tenantId)
                .then().statusCode(201);
    }

    private static String extractSixDigitCode(String body) {
        assertThat(body).as("verification email body").isNotNull();
        Matcher matcher = SIX_DIGIT_CODE.matcher(body);
        assertThat(matcher.find()).as("email body should contain a 6-digit verification code").isTrue();
        return matcher.group(1);
    }
}
