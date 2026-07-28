package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.Jwt;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-3's admin-surface / RBAC journey, black-box: the platform-admin token shape, the <b>cross-tenant admin guard</b>
 * (a tenant admin can never reach another tenant's data — the admin-surface equivalent of IT-2's cross-tenant SSO
 * refusal, and the most important assertion here), and the <b>last-tenant-admin invariant</b> (blocks removal of the
 * sole admin via BOTH the suspend and the direct role-revoke paths; allows it once a second admin exists).
 *
 * <p>Scenarios are independent test methods sharing the container stack. Scenarios 2–3 only READ or make blocked (409,
 * no-op) calls against the shared Tenant A, so they don't disturb each other; scenario 4, which mutates, uses its own
 * fresh tenant.
 */
class CoreAdminRbacGuardJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";
    private static final String LAST_ADMIN_CODE = "tenant_role.last_admin";

    private static String platformToken;
    private static String tenantA;
    private static String tenantB;
    private static String tenantAdminTokenA;   // a Tenant-A user holding TENANT_ADMIN, logged in
    private static String adminUserAId;
    private static String adminUserAEmail;

    @BeforeAll
    static void provisionFixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();

        // --- Tenant A: a client, a TENANT_ADMIN user, and a plain (TENANT_MEMBER-by-default) user ---
        tenantA = admin.provisionActiveTenant(platformToken);
        var clientA = admin.registerConfidentialClient(platformToken, tenantA);
        adminUserAEmail = Fixtures.email("admin-a");
        adminUserAId = admin.createActiveUser(platformToken, tenantA, adminUserAEmail, PASSWORD);
        admin.assignTenantRole(platformToken, tenantA, adminUserAId, "TENANT_ADMIN");
        admin.createActiveUser(platformToken, tenantA, Fixtures.email("member-a"), PASSWORD); // default role only

        // --- Tenant B: its own client + its own TENANT_ADMIN user (independent from A) ---
        tenantB = admin.provisionActiveTenant(platformToken);
        admin.registerConfidentialClient(platformToken, tenantB);
        String adminUserBId = admin.createActiveUser(platformToken, tenantB, Fixtures.email("admin-b"), PASSWORD);
        admin.assignTenantRole(platformToken, tenantB, adminUserBId, "TENANT_ADMIN");

        // A tenant-admin token = log in the TENANT_ADMIN-holding user of Tenant A.
        tenantAdminTokenA = oauthFlow().login(clientA.clientId(), clientA.secret(), adminUserAEmail, PASSWORD)
                .tokens().accessToken();
    }

    // ---- Scenario 1: platform-admin token shape ---------------------------

    @Test
    @DisplayName("Scenario 1: platform-admin token has PLATFORM_ADMIN, token_use=platform_admin, and NO tenant_id")
    void platformAdminTokenShape() {
        Map<String, Object> claims = Jwt.claims(platformToken);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get("roles");
        assertThat(roles).as("roles claim").contains("PLATFORM_ADMIN");
        assertThat(claims.get("token_use")).isEqualTo("platform_admin");
        assertThat(claims)
                .as("absence of tenant_id is the platform-level signal")
                .doesNotContainKey("tenant_id");
    }

    // ---- Scenario 2: the cross-tenant admin guard (THE key assertion) -----

    @Test
    @DisplayName("Scenario 2: a tenant admin reaches only its own tenant's data; a platform admin transcends")
    void crossTenantAdminGuard() {
        // Tenant-A admin → 200 on Tenant A, and it really is Tenant A's data.
        Response ownTenant = adminApi().get(tenantAdminTokenA, "/v1/tenants/{tid}/users", tenantA);
        assertThat(ownTenant.statusCode()).isEqualTo(200);
        assertThat(ownTenant.jsonPath().getList("items.email")).contains(adminUserAEmail);

        // SAME Tenant-A admin token → 403 on Tenant B (never reaches the data).
        Response otherTenant = adminApi().get(tenantAdminTokenA, "/v1/tenants/{tid}/users", tenantB);
        assertThat(otherTenant.statusCode())
                .as("a Tenant-A admin must NEVER read another tenant's users")
                .isEqualTo(403);

        // Platform admin transcends tenant scoping → 200 on BOTH.
        assertThat(adminApi().get(platformToken, "/v1/tenants/{tid}/users", tenantA).statusCode()).isEqualTo(200);
        assertThat(adminApi().get(platformToken, "/v1/tenants/{tid}/users", tenantB).statusCode()).isEqualTo(200);
    }

    // ---- Scenario 3: last-admin blocks removal of the sole admin ----------

    @Test
    @DisplayName("Scenario 3: removing the sole TENANT_ADMIN is blocked (409) on both suspend and role-revoke paths")
    void lastAdminBlocksRemovalOfSoleAdmin() {
        // Path 1 — suspend the sole admin (UserService.transition guard).
        Response suspend = adminApi().post(platformToken, "/v1/tenants/{tid}/users/{uid}/suspend", tenantA, adminUserAId);
        assertThat(suspend.statusCode()).isEqualTo(409);
        assertThat(AdminApiClient.problemCode(suspend)).isEqualTo(LAST_ADMIN_CODE);

        // Path 2 — directly revoke the TENANT_ADMIN role (TenantRoleService.revokeTenantRole guard).
        String roleId = adminApi().tenantRoleId(platformToken, tenantA, "TENANT_ADMIN");
        Response revoke = adminApi()
                .delete(platformToken, "/v1/tenants/{tid}/users/{uid}/tenant-roles/{rid}", tenantA, adminUserAId, roleId);
        assertThat(revoke.statusCode()).isEqualTo(409);
        assertThat(AdminApiClient.problemCode(revoke)).isEqualTo(LAST_ADMIN_CODE);
    }

    // ---- Scenario 4: removal allowed once a second admin exists -----------

    @Test
    @DisplayName("Scenario 4: once a second TENANT_ADMIN exists, the first can be suspended")
    void lastAdminAllowsRemovalOnceSecondExists() {
        AdminApiClient admin = adminApi();
        // Own fresh tenant so this mutation doesn't disturb the shared fixtures.
        String tenant = admin.provisionActiveTenant(platformToken);
        String firstId = admin.createActiveUser(platformToken, tenant, Fixtures.email("first"), PASSWORD);
        admin.assignTenantRole(platformToken, tenant, firstId, "TENANT_ADMIN");

        // Precondition: with the first as the SOLE admin, suspend is blocked.
        Response blocked = admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/suspend", tenant, firstId);
        assertThat(blocked.statusCode()).as("sole admin cannot be suspended").isEqualTo(409);
        assertThat(AdminApiClient.problemCode(blocked)).isEqualTo(LAST_ADMIN_CODE);

        // Add a SECOND admin, then the first CAN be suspended.
        String secondId = admin.createActiveUser(platformToken, tenant, Fixtures.email("second"), PASSWORD);
        admin.assignTenantRole(platformToken, tenant, secondId, "TENANT_ADMIN");

        Response ok = admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/suspend", tenant, firstId);
        assertThat(ok.statusCode()).as("with a second admin, the first can be suspended").isEqualTo(200);
        assertThat(ok.jsonPath().getString("status")).isEqualTo("SUSPENDED");

        // Confirm final state. NOTE: suspending does NOT revoke the role, so both users still HOLD TENANT_ADMIN
        // (2 holders), but only the second is ACTIVE — the tenant retains exactly one working admin.
        assertThat(admin.get(platformToken, "/v1/tenants/{tid}/users/{uid}", tenant, firstId).jsonPath()
                .getString("status")).isEqualTo("SUSPENDED");

        Map<String, Object> counts = db().queryOne(
                        "select count(*) as total_admins, "
                                + "count(*) filter (where u.status = 'ACTIVE') as active_admins "
                                + "from user_tenant_roles utr "
                                + "join tenant_roles tr on tr.id = utr.tenant_role_id "
                                + "join users u on u.id = utr.user_id "
                                + "where utr.tenant_id = ? and tr.name = 'TENANT_ADMIN'",
                        UUID.fromString(tenant))
                .orElseThrow(() -> new AssertionError("admin-count query returned no row"));
        assertThat(asLong(counts.get("total_admins"))).as("both users still hold TENANT_ADMIN (suspend ≠ revoke)").isEqualTo(2);
        assertThat(asLong(counts.get("active_admins"))).as("exactly one ACTIVE admin remains (the second)").isEqualTo(1);
    }

    // ---- Scenario 5: sequential-suspension gap is closed (IT-4 regression) --

    @Test
    @DisplayName("Scenario 5: suspending down to the last ACTIVE admin is blocked; reactivation restores standing")
    void sequentialSuspensionBlockedAndReactivationRestores() {
        AdminApiClient admin = adminApi();
        String tenant = admin.provisionActiveTenant(platformToken);
        String firstId = admin.createActiveUser(platformToken, tenant, Fixtures.email("adm1"), PASSWORD);
        admin.assignTenantRole(platformToken, tenant, firstId, "TENANT_ADMIN");
        String secondId = admin.createActiveUser(platformToken, tenant, Fixtures.email("adm2"), PASSWORD);
        admin.assignTenantRole(platformToken, tenant, secondId, "TENANT_ADMIN");

        // Suspend the first → allowed (a second ACTIVE admin still exists).
        assertThat(admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/suspend", tenant, firstId).statusCode())
                .as("first suspension allowed (second admin active)").isEqualTo(200);

        // Suspend the second → now the LAST ACTIVE holder (the first still holds a DORMANT role) → BLOCKED. Under the
        // old all-status count this incorrectly succeeded (2 role rows), orphaning the tenant — the exact fixed gap.
        Response blocked = admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/suspend", tenant, secondId);
        assertThat(blocked.statusCode()).as("suspending the last ACTIVE admin is blocked").isEqualTo(409);
        assertThat(AdminApiClient.problemCode(blocked)).isEqualTo(LAST_ADMIN_CODE);

        // Reactivate the first → succeeds, restoring them as an active admin WITHOUT re-assigning the role
        // (the assignment was never revoked, only dormant).
        assertThat(admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/activate", tenant, firstId).jsonPath()
                .getString("status")).as("reactivation restores ACTIVE status").isEqualTo("ACTIVE");

        // With two ACTIVE admins again, the second CAN now be suspended — active-only counting tracks status both ways.
        assertThat(admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/suspend", tenant, secondId).statusCode())
                .as("suspension allowed again once the reactivated admin restores a second active holder").isEqualTo(200);
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }
}
