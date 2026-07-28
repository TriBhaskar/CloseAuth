package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.AdminApiClient.ClientCredentials;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.Jwt;
import com.anterka.closeauth.it.support.OAuthFlowClient.LoginResult;
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
 * IT-9's tenant-lifecycle-cascade + platform-admin-CRUD journey, black-box.
 *
 * <p><b>Part 1 (tenant lifecycle)</b> targets tenant <em>suspension</em> — never before the actual subject of a test —
 * and answers the stage's headline open question: does suspending a tenant revoke the sessions/tokens of already-logged-
 * in users, or only block new operations? <b>Part 2 (platform-admin CRUD)</b> exercises the platform-admin management
 * surface directly and, critically, closes the Stage-7a loop externally for the first time: proving black-box that
 * suspending a platform admin instantly kills their already-minted token (admin API 401 + introspection {@code active:false}).
 *
 * <p>Sequential lifecycles are kept inside a single test method each (JUnit gives no cross-method order guarantee), and
 * every Part-2 scenario provisions its own admin, so nothing depends on execution order.
 */
class TenantLifecycleAndPlatformAdminJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";

    private static String platformToken;
    private static String introspectClientId;   // an always-active confidential client, only for RFC 7662 introspection
    private static String introspectClientSecret;

    @BeforeAll
    static void fixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();
        String introspectTenant = admin.provisionActiveTenant(platformToken);
        ClientCredentials introspect = admin.registerConfidentialClient(platformToken, introspectTenant);
        introspectClientId = introspect.clientId();
        introspectClientSecret = introspect.secret();
    }

    // ================= Part 1 — tenant lifecycle cascade =================

    @Test
    @DisplayName("Part 1: tenant suspension blocks new logins/writes AND revokes pre-existing sessions/tokens; reactivation restores")
    void tenantSuspensionLifecycle() {
        AdminApiClient admin = adminApi();
        String tenant = admin.provisionActiveTenant(platformToken);
        ClientCredentials client = admin.registerConfidentialClient(platformToken, tenant);
        String email = Fixtures.email("lifecycle-user");
        admin.createActiveUser(platformToken, tenant, email, PASSWORD);

        // -- Scenario 1: baseline — a real logged-in session + access & refresh tokens, all working.
        LoginResult pre = oauthFlow().login(client.clientId(), client.secret(), email, PASSWORD);
        String preAccess = pre.tokens().accessToken();
        String preRefresh = pre.tokens().refreshToken();
        assertThat(preAccess).isNotBlank();
        assertThat(preRefresh).as("a confidential client gets a refresh token").isNotBlank();
        assertThat(oauthFlow().authorizeOnly(client.clientId(), pre.session()).ssoRecognized())
                .as("pre-suspend session is recognized (SSO)").isTrue();
        assertThat(oauthFlow().isActive(client.clientId(), client.secret(), preAccess))
                .as("pre-suspend access token is active").isTrue();

        // -- Scenario 2: suspend the tenant.
        Response suspend = admin.post(platformToken, "/v1/platform/tenants/{id}/suspend", tenant);
        assertThat(suspend.statusCode()).isEqualTo(200);
        assertThat(suspend.jsonPath().getString("status")).isEqualTo("SUSPENDED");

        // -- Scenario 3: new logins now fail with the uniform enumeration-safe shape (tenant-status gate).
        Response blockedLogin = oauthFlow().attemptPasswordLogin(client.clientId(), email, PASSWORD);
        assertThat(blockedLogin.statusCode()).as("login blocked in a suspended tenant").isEqualTo(401);
        assertThat(blockedLogin.jsonPath().getString("error")).isEqualTo("invalid_credentials");

        // -- Scenario 4: within-tenant writes are blocked (requireActiveTenant → 403 tenant.not_active).
        Response blockedWrite = admin.postJson(platformToken, "/v1/tenants/{tid}/users",
                Map.of("email", Fixtures.email("blocked"), "password", PASSWORD, "initialStatus", "ACTIVE"), tenant);
        assertThat(blockedWrite.statusCode()).as("create-user blocked in a suspended tenant").isEqualTo(403);
        assertThat(AdminApiClient.problemCode(blockedWrite)).isEqualTo("tenant.not_active");

        // -- Scenario 5 (regression test for the tenant-suspension cascade fix): suspension REVOKES the pre-existing
        // session and tokens across all three legs — the SSO bypass, introspection, and refresh rotation are all closed.
        boolean sessionStillRecognized = oauthFlow().authorizeOnly(client.clientId(), pre.session()).ssoRecognized();
        boolean accessStillActive = oauthFlow().isActive(client.clientId(), client.secret(), preAccess);
        Response refresh = oauthFlow().refresh(client.clientId(), client.secret(), preRefresh);
        assertThat(sessionStillRecognized)
                .as("the pre-suspend session is no longer SSO-recognized after suspension (SSO bypass closed, 1b)")
                .isFalse();
        assertThat(accessStillActive)
                .as("the pre-suspend access token introspects inactive after suspension (revocation marker, 1a)")
                .isFalse();
        assertThat(refresh.statusCode())
                .as("the pre-suspend refresh token is rejected after suspension (rotation tenant gate, 1c)").isEqualTo(400);
        assertThat(refresh.jsonPath().getString("error"))
                .as("refresh rejection is a routine invalid_grant, not a replay/compromise").isEqualTo("invalid_grant");

        // -- Scenario 6: reactivation restores normal operation (the guard is dynamic, not a one-way lock).
        assertThat(admin.post(platformToken, "/v1/platform/tenants/{id}/activate", tenant).jsonPath().getString("status"))
                .isEqualTo("ACTIVE");
        assertThat(oauthFlow().login(client.clientId(), client.secret(), email, PASSWORD).tokens().accessToken())
                .as("a fresh login succeeds again after reactivation").isNotBlank();
        Response writeAfter = admin.postJson(platformToken, "/v1/tenants/{tid}/users",
                Map.of("email", Fixtures.email("after"), "password", PASSWORD, "initialStatus", "ACTIVE"), tenant);
        assertThat(writeAfter.statusCode()).as("within-tenant write succeeds again after reactivation").isEqualTo(201);
    }

    @Test
    @DisplayName("Part 1 scenario 7: soft-delete blocks logins/writes the same way suspension does")
    void softDeleteBlocksLikeSuspension() {
        AdminApiClient admin = adminApi();
        String tenant = admin.provisionActiveTenant(platformToken);
        ClientCredentials client = admin.registerConfidentialClient(platformToken, tenant);
        String email = Fixtures.email("softdelete-user");
        admin.createActiveUser(platformToken, tenant, email, PASSWORD);

        // Soft-delete (terminal DELETED state).
        Response delete = admin.delete(platformToken, "/v1/platform/tenants/{id}", tenant);
        assertThat(delete.statusCode()).isEqualTo(200);
        assertThat(delete.jsonPath().getString("status")).isEqualTo("DELETED");

        // Same blocking effect as suspension: login 401, write 403 tenant.not_active.
        Response blockedLogin = oauthFlow().attemptPasswordLogin(client.clientId(), email, PASSWORD);
        assertThat(blockedLogin.statusCode()).isEqualTo(401);
        assertThat(blockedLogin.jsonPath().getString("error")).isEqualTo("invalid_credentials");

        Response blockedWrite = admin.postJson(platformToken, "/v1/tenants/{tid}/users",
                Map.of("email", Fixtures.email("blocked"), "password", PASSWORD, "initialStatus", "ACTIVE"), tenant);
        assertThat(blockedWrite.statusCode()).isEqualTo(403);
        assertThat(AdminApiClient.problemCode(blockedWrite)).isEqualTo("tenant.not_active");
    }

    // ================= Part 2 — platform-admin CRUD =================

    @Test
    @DisplayName("Part 2 scenario 8: create a platform admin (201) and the response never leaks the password")
    void createPlatformAdminNeverLeaksPassword() {
        String email = Fixtures.email("padmin-create");
        Response create = adminApi().postJson(platformToken, "/v1/platform/admins",
                Map.of("email", email, "password", PASSWORD, "firstName", "Pat", "lastName", "Admin"));
        assertThat(create.statusCode()).isEqualTo(201);
        assertThat(create.jsonPath().getString("email")).isEqualTo(email);
        assertThat(create.jsonPath().getString("status")).isEqualTo("ACTIVE");
        assertThat(create.jsonPath().getString("id")).isNotBlank();
        // Write-once-secret discipline: neither the raw password nor any credential field appears in the response.
        assertThat(create.asString()).as("the raw password must not be echoed").doesNotContain(PASSWORD);
        assertThat(create.jsonPath().getString("password")).isNull();
        assertThat(create.jsonPath().getString("passwordHash")).isNull();
    }

    @Test
    @DisplayName("Part 2 scenario 9: duplicate platform-admin email is a 409 conflict")
    void duplicatePlatformAdminEmailConflict() {
        String email = Fixtures.email("padmin-dup");
        createPlatformAdmin(email);
        Response dup = adminApi().postJson(platformToken, "/v1/platform/admins",
                Map.of("email", email, "password", PASSWORD));
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(AdminApiClient.problemCode(dup)).isEqualTo("platform_admin.email_exists");
    }

    @Test
    @DisplayName("Part 2 scenario 10: a created platform admin appears in the list")
    void createdPlatformAdminIsListed() {
        String email = Fixtures.email("padmin-list");
        String adminId = createPlatformAdmin(email);
        List<String> ids = adminApi().getQuery(platformToken, "/v1/platform/admins", Map.of("size", 100))
                .jsonPath().getList("items.id");
        assertThat(ids).as("the new admin is listed").contains(adminId);
    }

    @Test
    @DisplayName("Part 2 scenarios 11-12: role assignment then revocation are observable in freshly minted token claims")
    void roleAssignmentAndRevocationVisibleInTokenClaims() {
        String email = Fixtures.email("padmin-roles");
        String adminId = createPlatformAdmin(email);

        // Assign PLATFORM_SUPPORT → a freshly minted token carries it.
        assertThat(adminApi().post(platformToken, "/v1/platform/admins/{id}/roles/{role}", adminId, "PLATFORM_SUPPORT")
                .statusCode()).isEqualTo(204);
        assertThat(rolesOf(mintToken(email))).as("assigned role appears in the token").contains("PLATFORM_SUPPORT");

        // Revoke it → a newly minted token no longer carries it.
        assertThat(adminApi().delete(platformToken, "/v1/platform/admins/{id}/roles/{role}", adminId, "PLATFORM_SUPPORT")
                .statusCode()).isEqualTo(204);
        assertThat(rolesOf(mintToken(email))).as("revoked role is gone from a fresh token").doesNotContain("PLATFORM_SUPPORT");
    }

    // NOTE — why there is NO black-box scenario for the last-platform-admin guard (platform_admin.last_admin / 409).
    // The guard fires only when the target is the SOLE active PLATFORM_ADMIN system-wide (active-only count <= 1). But
    // this shared container always has a permanent floor of one active PLATFORM_ADMIN: the bootstrap admin, which
    // PlatformAdminBootstrap creates and grants PLATFORM_ADMIN, and which every test authenticates through via
    // platformAdminToken(). So any admin THIS suite creates is never the last active holder — suspending/role-revoking
    // it always succeeds (200), as scenario 13 below incidentally shows (it suspends a created PLATFORM_ADMIN and gets
    // 200). The only way to trigger the 409 black-box would be to target the bootstrap itself, which we deliberately
    // never do (suspending it would lock the whole suite out, and the outcome would be order-dependent on any other
    // active PLATFORM_ADMIN). The 409 path is therefore proven at the backend unit layer instead — see
    // PlatformAdminServiceTest, which covers both enforcement paths (suspend + role-revoke) in both directions
    // (blocked when last active; allowed once a second active holder exists / when the holder is already suspended).
    @Test
    @DisplayName("Part 2 scenario 13: suspending a platform admin instantly kills its pre-existing token (7a, black-box)")
    void suspendKillsPreExistingPlatformToken() {
        String email = Fixtures.email("padmin-kill");
        String adminId = createPlatformAdmin(email);
        // Give it PLATFORM_ADMIN so the token is actually accepted on the gated platform surface.
        adminApi().post(platformToken, "/v1/platform/admins/{id}/roles/{role}", adminId, "PLATFORM_ADMIN");

        String token = mintToken(email);
        Response before = adminApi().get(token, "/v1/platform/me");
        assertThat(before.statusCode()).as("the token works before suspension").isEqualTo(200);
        assertThat(before.jsonPath().getString("sub")).isEqualTo(adminId);
        assertThat(oauthFlow().isActive(introspectClientId, introspectClientSecret, token))
                .as("the token introspects as active before suspension").isTrue();

        // Suspend → 7a writes the platform-admin revocation marker.
        assertThat(adminApi().post(platformToken, "/v1/platform/admins/{id}/suspend", adminId).statusCode()).isEqualTo(200);

        // The SAME pre-suspend token is now rejected on the admin API and introspects inactive — instantly, not at TTL.
        assertThat(adminApi().get(token, "/v1/platform/me").statusCode())
                .as("the pre-suspend token is rejected on the admin API after suspension").isEqualTo(401);
        assertThat(oauthFlow().isActive(introspectClientId, introspectClientSecret, token))
                .as("the pre-suspend token introspects as inactive after suspension").isFalse();
    }

    @Test
    @DisplayName("Part 2 scenarios 14-15: a suspended admin cannot mint a token; reactivation restores minting")
    void suspendedAdminCannotMintUntilReactivated() {
        String email = Fixtures.email("padmin-mint");
        String adminId = createPlatformAdmin(email);

        adminApi().post(platformToken, "/v1/platform/admins/{id}/suspend", adminId);
        Response suspended = mintTokenResponse(email);
        assertThat(suspended.statusCode()).as("a suspended admin cannot mint a token").isEqualTo(401);
        assertThat(suspended.jsonPath().getString("detail")).as("uniform enumeration-safe failure").isNotNull();

        adminApi().post(platformToken, "/v1/platform/admins/{id}/activate", adminId);
        Response reactivated = mintTokenResponse(email);
        assertThat(reactivated.statusCode()).as("minting works again after reactivation").isEqualTo(200);
        assertThat(reactivated.jsonPath().getString("access_token")).isNotBlank();
    }

    // ---- helpers -----------------------------------------------------------

    private static String createPlatformAdmin(String email) {
        return adminApi().postJson(platformToken, "/v1/platform/admins", Map.of("email", email, "password", PASSWORD))
                .then().statusCode(201).extract().path("id");
    }

    private static Response mintTokenResponse(String email) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", PASSWORD))
                .post("/v1/platform/auth/token");
    }

    private static String mintToken(String email) {
        return mintTokenResponse(email).then().statusCode(200).extract().path("access_token");
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesOf(String token) {
        return (List<String>) Jwt.claims(token).get("roles");
    }
}
