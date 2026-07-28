package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.AdminApiClient.ClientCredentials;
import com.anterka.closeauth.it.support.Emails;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.MailpitMessage;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-5's registration-modes journey, black-box: proves the three registration modes not covered by IT-1's
 * {@code EMAIL_VERIFIED} — <b>OPEN</b> (immediate activation), <b>ADMIN_APPROVED</b> (PENDING until an admin approves),
 * and <b>INVITE_ONLY</b> (admin-issued single-use invite required) — end to end against the running app. Together with
 * IT-1 this closes registration-mode coverage to <b>4/4</b>.
 *
 * <p>Each scenario is an independent test method sharing the container stack; each provisions its own fresh tenant with
 * the mode set (so the modes never interfere), one trusted confidential client, and unique user emails.
 */
class RegistrationModesJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";

    private static String platformToken;

    @BeforeAll
    static void fixtures() {
        platformToken = platformAdminToken();
    }

    // ---- Scenario 1: OPEN — immediate activation --------------------------

    @Test
    @DisplayName("OPEN: register → ACTIVE immediately (email unverified), login works at once, duplicate email → 409")
    void openModeActivatesImmediatelyAndRejectsDuplicate() {
        Fixture fx = tenantInMode("OPEN");
        String email = Fixtures.email("open");

        Response reg = oauthFlow().register(email, PASSWORD, fx.clientId());
        assertThat(reg.statusCode()).as("OPEN registration succeeds").isEqualTo(200);
        assertThat(reg.jsonPath().getString("status")).as("OPEN activates immediately").isEqualTo("ACTIVE");
        assertThat(reg.jsonPath().getString("mode")).isEqualTo("OPEN");
        assertThat(reg.jsonPath().getBoolean("emailVerificationSent"))
                .as("OPEN sends no verification email").isFalse();

        // DB: ACTIVE, but email_verified is INDEPENDENT of status — OPEN never verifies the address, so it stays false.
        Map<String, Object> row = db()
                .queryOne("select status, email_verified from users where email = ?", email)
                .orElseThrow(() -> new AssertionError("no user row for " + email));
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("email_verified"))
                .as("OPEN activates without verifying the email — ACTIVE and email_verified are independent")
                .isEqualTo(false);

        // Login works immediately — no verification step.
        assertThat(oauthFlow().login(fx.clientId(), fx.secret(), email, PASSWORD).tokens().accessToken())
                .as("an OPEN-registered user logs in at once").isNotBlank();

        // Duplicate email in the same tenant → 409 user.email_conflict (proven directly here for the first time).
        Response dup = oauthFlow().register(email, PASSWORD, fx.clientId());
        assertThat(dup.statusCode()).as("re-registering the same email is a conflict").isEqualTo(409);
        assertThat(AdminApiClient.problemCode(dup)).isEqualTo("user.email_conflict");
    }

    // ---- Scenario 2: ADMIN_APPROVED — PENDING until approved --------------

    @Test
    @DisplayName("ADMIN_APPROVED: register → PENDING, login blocked, approve → ACTIVE, login works, re-approve → 409")
    void adminApprovedModeGatesLoginUntilApproval() {
        Fixture fx = tenantInMode("ADMIN_APPROVED");
        String email = Fixtures.email("approve");

        Response reg = oauthFlow().register(email, PASSWORD, fx.clientId());
        assertThat(reg.statusCode()).isEqualTo(200);
        assertThat(reg.jsonPath().getString("status")).as("ADMIN_APPROVED holds the user PENDING").isEqualTo("PENDING");
        assertThat(reg.jsonPath().getString("mode")).isEqualTo("ADMIN_APPROVED");
        String userId = reg.jsonPath().getString("userId");

        // Login before approval fails with the same uniform enumeration-safe shape as a bad password (IT-2).
        Response before = oauthFlow().attemptPasswordLogin(fx.clientId(), email, PASSWORD);
        assertThat(before.statusCode()).as("a PENDING user cannot log in").isEqualTo(401);
        assertThat(before.jsonPath().getString("error")).isEqualTo("invalid_credentials");

        // Approve via the platform-admin token.
        Response approve = adminApi()
                .post(platformToken, "/v1/tenants/{tid}/users/{uid}/approve", fx.tenant(), userId);
        assertThat(approve.statusCode()).as("approve succeeds").isEqualTo(200);
        assertThat(approve.jsonPath().getString("status")).isEqualTo("ACTIVE");

        // Now login works.
        assertThat(oauthFlow().login(fx.clientId(), fx.secret(), email, PASSWORD).tokens().accessToken())
                .as("an approved user logs in").isNotBlank();

        // Re-approving a now-ACTIVE (non-PENDING) user → 409 user.not_pending.
        Response reApprove = adminApi()
                .post(platformToken, "/v1/tenants/{tid}/users/{uid}/approve", fx.tenant(), userId);
        assertThat(reApprove.statusCode()).as("approve is only valid on a PENDING user").isEqualTo(409);
        assertThat(AdminApiClient.problemCode(reApprove)).isEqualTo("user.not_pending");
    }

    // ---- Scenario 3: INVITE_ONLY — issue / consume / reuse / revoke -------

    @Test
    @DisplayName("INVITE_ONLY: no-invite → 403, invite (no raw secret) → register ACTIVE, reuse → 403, revoke blocks use")
    void inviteOnlyModeRequiresSingleUseInvite() {
        Fixture fx = tenantInMode("INVITE_ONLY");
        String email = Fixtures.email("invite");

        // Registration without an invite is refused outright.
        Response noInvite = oauthFlow().register(email, PASSWORD, fx.clientId());
        assertThat(noInvite.statusCode()).as("INVITE_ONLY refuses registration with no invite").isEqualTo(403);
        assertThat(AdminApiClient.problemCode(noInvite)).isEqualTo("registration.invite_required");

        // Issue an invite — 201, and the raw secret is NEVER in the response body (emailed only, like a client secret).
        Response issued = adminApi()
                .postJson(platformToken, "/v1/tenants/{tid}/invites", Map.of("email", email), fx.tenant());
        assertThat(issued.statusCode()).as("invite issuance").isEqualTo(201);
        String issuedBody = issued.asString();
        assertThat(issued.jsonPath().getString("email")).isEqualTo(email);

        // Retrieve the invite email and extract the token from the ?invite= link (NOT ?token= — invites use their own param).
        MailpitMessage inviteMail = mailpit().waitForMessageTo(email, Duration.ofSeconds(30));
        String inviteToken = Emails.linkParam(inviteMail.text(), "invite");
        assertThat(inviteToken).isNotBlank();
        assertThat(issuedBody)
                .as("the raw invite secret must NOT appear in the issuance response — it is delivered only by email")
                .doesNotContain(inviteToken);

        // Register with the valid invite → ACTIVE immediately (invite consumption is the activation step).
        Response withInvite = oauthFlow().register(email, PASSWORD, fx.clientId(), inviteToken);
        assertThat(withInvite.statusCode()).as("registration with a valid invite succeeds").isEqualTo(200);
        assertThat(withInvite.jsonPath().getString("status")).as("invite consumption activates the user").isEqualTo("ACTIVE");
        assertThat(withInvite.jsonPath().getString("mode")).isEqualTo("INVITE_ONLY");

        // Reuse the now-consumed invite → 403 registration.invalid_invite (single-use: consume fails BEFORE any
        // duplicate-email check, so this is an invite rejection, not a 409 conflict).
        Response reuse = oauthFlow().register(email, PASSWORD, fx.clientId(), inviteToken);
        assertThat(reuse.statusCode()).as("a consumed invite cannot be reused").isEqualTo(403);
        assertThat(AdminApiClient.problemCode(reuse)).isEqualTo("registration.invalid_invite");

        // List + revoke: issue a second invite, confirm it's listed outstanding, revoke it, then its token is dead.
        String email2 = Fixtures.email("invite2");
        Response issued2 = adminApi()
                .postJson(platformToken, "/v1/tenants/{tid}/invites", Map.of("email", email2), fx.tenant());
        assertThat(issued2.statusCode()).isEqualTo(201);
        String inviteId = issued2.jsonPath().getString("id");

        List<String> outstandingIds = adminApi()
                .get(platformToken, "/v1/tenants/{tid}/invites", fx.tenant())
                .jsonPath().getList("id");
        assertThat(outstandingIds).as("the outstanding invite is listed").contains(inviteId);

        assertThat(adminApi().delete(platformToken, "/v1/tenants/{tid}/invites/{iid}", fx.tenant(), inviteId).statusCode())
                .as("revoke returns 204").isEqualTo(204);

        MailpitMessage inviteMail2 = mailpit().waitForMessageTo(email2, Duration.ofSeconds(30));
        String revokedToken = Emails.linkParam(inviteMail2.text(), "invite");
        Response afterRevoke = oauthFlow().register(email2, PASSWORD, fx.clientId(), revokedToken);
        assertThat(afterRevoke.statusCode()).as("a revoked invite cannot be used to register").isEqualTo(403);
        assertThat(AdminApiClient.problemCode(afterRevoke)).isEqualTo("registration.invalid_invite");
    }

    // ---- fixtures ----------------------------------------------------------

    /** A fresh tenant put in {@code mode}, with one trusted confidential client for driving {@code /register} + login. */
    private static Fixture tenantInMode(String mode) {
        AdminApiClient admin = adminApi();
        String tenant = admin.provisionActiveTenant(platformToken);
        admin.setRegistrationMode(platformToken, tenant, mode);
        ClientCredentials client = admin.registerConfidentialClient(platformToken, tenant);
        return new Fixture(tenant, client.clientId(), client.secret());
    }

    private record Fixture(String tenant, String clientId, String secret) {
    }
}
