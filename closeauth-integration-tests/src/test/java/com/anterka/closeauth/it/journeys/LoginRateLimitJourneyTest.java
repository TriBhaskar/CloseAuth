package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.AdminApiClient.ClientCredentials;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Login rate limiting (Phase 0 of the tenant-onboarding effort — see {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md}
 * §2.1a): {@code LoginPolicyService} now throttles per-account (tenant + email) password-login attempts. The one
 * property that actually matters black-box is <b>enumeration-safety under throttling</b> — a rate-limited refusal
 * must be byte-identical to an ordinary bad-password 401, in both directions (a limiter that returns a distinct
 * status/body, or that lets a correct password bypass the limit, would reopen the account-existence oracle
 * {@code LoginController} otherwise closes).
 *
 * <p>Deliberately its own class, not folded into {@code CoreAuthSsoLogoutJourneyTest} or
 * {@code TenantLifecycleAndPlatformAdminJourneyTest} — both already perform several successful logins against one
 * shared fixture email within the rate-limit window, and this journey deliberately exhausts a bucket for the
 * duration of the window; sharing a fixture would put those journeys at risk.
 *
 * <p>{@code ATTEMPT_LIMIT} mirrors {@code closeauth.security.max-login-attempts}' shipped default (5) — the suite
 * does not override it, so this exercises the same limit production actually ships with, not a test-only value.
 */
class LoginRateLimitJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";
    private static final String WRONG_PASSWORD = "definitely-wrong";
    private static final int ATTEMPT_LIMIT = 5;

    private static String platformToken;
    private static String tenant;
    private static String client1;

    @BeforeAll
    static void fixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();
        tenant = admin.provisionActiveTenant(platformToken);
        client1 = admin.registerConfidentialClient(platformToken, tenant).clientId();
    }

    @Test
    @DisplayName("Exhausted-budget refusal is byte-identical to a bad password — both directions")
    void rateLimitedRefusalIsIndistinguishableFromInvalidCredentials() {
        String email = Fixtures.email("ratelimit");
        adminApi().createActiveUser(platformToken, tenant, email, PASSWORD);

        // Reference: an ordinary bad-password 401 (attempt #1 of the budget).
        Response reference = oauthFlow().attemptPasswordLogin(client1, email, WRONG_PASSWORD);
        assertThat(reference.statusCode()).isEqualTo(401);
        String referenceBody = reference.asString();

        // Burn the rest of the budget (attempts #2..#ATTEMPT_LIMIT) — still ordinary bad-password failures.
        for (int attempt = 2; attempt <= ATTEMPT_LIMIT; attempt++) {
            Response bad = oauthFlow().attemptPasswordLogin(client1, email, WRONG_PASSWORD);
            assertThat(bad.statusCode()).as("attempt #%d is still a plain credential failure", attempt).isEqualTo(401);
        }

        // Direction 1: budget now exhausted. A further WRONG password is refused with the SAME body as the reference.
        Response overLimitWrong = oauthFlow().attemptPasswordLogin(client1, email, WRONG_PASSWORD);
        assertThat(overLimitWrong.statusCode()).isEqualTo(401);
        assertThat(overLimitWrong.getContentType()).isEqualTo(reference.getContentType());
        assertThat(overLimitWrong.asString())
                .as("rate-limited body is identical to a plain bad-password body")
                .isEqualTo(referenceBody);

        // Direction 2 — the load-bearing one: the CORRECT password, submitted while over budget, is ALSO refused,
        // with the identical body. Proves the limiter gates before the credential check runs — a valid credential
        // neither escapes the limit nor produces a response distinguishable from a wrong one.
        Response overLimitCorrect = oauthFlow().attemptPasswordLogin(client1, email, PASSWORD);
        assertThat(overLimitCorrect.statusCode()).isEqualTo(401);
        assertThat(overLimitCorrect.asString())
                .as("even the CORRECT password is refused identically once the account is throttled")
                .isEqualTo(referenceBody);
    }

    @Test
    @DisplayName("Per-account keying: a different email in the same tenant is unaffected")
    void limiterDoesNotThrottleOtherAccountsInTheSameTenant() {
        String throttled = Fixtures.email("ratelimit-throttled");
        String other = Fixtures.email("ratelimit-other");
        adminApi().createActiveUser(platformToken, tenant, throttled, PASSWORD);
        adminApi().createActiveUser(platformToken, tenant, other, PASSWORD);

        exhaustBudget(client1, throttled);
        assertThat(oauthFlow().attemptPasswordLogin(client1, throttled, PASSWORD).statusCode())
                .as("the throttled account is refused even with its correct password").isEqualTo(401);

        // A different account, same tenant, same client: unaffected — a normal successful login (302).
        assertThat(oauthFlow().attemptPasswordLogin(client1, other, PASSWORD).statusCode())
                .as("a different account's login is not throttled by another account's attempts").isEqualTo(302);
    }

    @Test
    @DisplayName("Per-tenant keying: the same email in a different tenant is unaffected")
    void limiterDoesNotThrottleTheSameEmailInADifferentTenant() {
        String email = Fixtures.email("ratelimit-crosstenant");
        adminApi().createActiveUser(platformToken, tenant, email, PASSWORD);

        String otherTenant = adminApi().provisionActiveTenant(platformToken);
        ClientCredentials otherClient = adminApi().registerConfidentialClient(platformToken, otherTenant);
        adminApi().createActiveUser(platformToken, otherTenant, email, PASSWORD);

        exhaustBudget(client1, email);
        assertThat(oauthFlow().attemptPasswordLogin(client1, email, PASSWORD).statusCode())
                .as("throttled in the original tenant").isEqualTo(401);

        // Same email, a different tenant, a different client — a fresh bucket, logs in normally.
        assertThat(oauthFlow().attemptPasswordLogin(otherClient.clientId(), email, PASSWORD).statusCode())
                .as("the same email in a different tenant is not throttled").isEqualTo(302);
    }

    /** Drives one more than {@link #ATTEMPT_LIMIT} bad-password attempts, guaranteeing the bucket is exhausted. */
    private void exhaustBudget(String clientId, String email) {
        for (int attempt = 0; attempt < ATTEMPT_LIMIT + 1; attempt++) {
            oauthFlow().attemptPasswordLogin(clientId, email, WRONG_PASSWORD);
        }
    }
}
