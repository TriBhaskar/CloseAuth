package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.AdminApiClient.ClientCredentials;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-7's application-tier RBAC + Resource-Server/scope-management journey, black-box. The tenant tier (last-admin,
 * cross-tenant guard) was proven in IT-3/IT-4; this stage targets the never-directly-tested application tier:
 * standalone Resource Servers, their scope catalog with its immutability/conflict rules, RS-scoped application roles,
 * and — the key assertion — the <b>cross-RS scope-bundle rejection</b> that keeps one RS's roles from bundling another
 * RS's scopes. Pure admin-API composition: no interactive OAuth flow is driven (role/scope effects in a token are
 * deferred to a future stage, per the stage plan). Assignment side-effects are proven via a direct
 * {@code user_application_roles} DB check, since no admin GET exposes a user's application-role assignments.
 *
 * <p>Independent test methods share the container stack. Mutation-heavy Part-1 scenarios create their own RS; Part-2
 * shares two distinct RSes ({@code rsMain}, {@code rsOther}) so cross-RS rejection has two real resource servers.
 */
class AppRbacAndResourceServerJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";

    private static String platformToken;
    private static String tenant;
    private static String userId;
    private static String rsMain;         // an RS whose role bundles its own scopes
    private static String rsMainReadScope;
    private static String rsOther;        // a distinct RS — its scope must be rejected from rsMain's role
    private static String rsOtherReadScope;

    @BeforeAll
    static void fixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();
        tenant = admin.provisionActiveTenant(platformToken);
        userId = admin.createActiveUser(platformToken, tenant, Fixtures.email("rbac-user"), PASSWORD);

        rsMain = admin.createResourceServer(platformToken, tenant, Fixtures.slug("orders"), "Orders API", audience("orders"));
        rsMainReadScope = admin.addScope(platformToken, tenant, rsMain, "read", "Read orders", false, true);
        admin.addScope(platformToken, tenant, rsMain, "write", "Write orders", true, false);

        rsOther = admin.createResourceServer(platformToken, tenant, Fixtures.slug("billing"), "Billing API", audience("billing"));
        rsOtherReadScope = admin.addScope(platformToken, tenant, rsOther, "read", "Read billing", false, false);
    }

    // ===================== Part 1 — RS & scope management =====================

    @Test
    @DisplayName("Scenario 1: create a standalone resource server (is_auto_created=false)")
    void createStandaloneResourceServer() {
        String slug = Fixtures.slug("inventory");
        String aud = audience("inventory");
        Response response = adminApi().postJson(platformToken, "/v1/tenants/{tid}/resource-servers",
                Map.of("slug", slug, "name", "Inventory API", "audienceIdentifier", aud), tenant);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.jsonPath().getString("slug")).isEqualTo(slug);
        assertThat(response.jsonPath().getString("name")).isEqualTo("Inventory API");
        assertThat(response.jsonPath().getString("audienceIdentifier")).isEqualTo(aud);
        assertThat(response.jsonPath().getBoolean("autoCreated"))
                .as("an explicitly-created RS is not auto-created").isFalse();
    }

    @Test
    @DisplayName("Scenario 2: slug + audience uniqueness are enforced (tenant-scoped slug, global audience)")
    void slugAndAudienceUniqueness() {
        AdminApiClient admin = adminApi();
        String slug = Fixtures.slug("catalog");
        String aud = audience("catalog");
        admin.createResourceServer(platformToken, tenant, slug, "Catalog API", aud); // baseline

        // Same slug in the SAME tenant → 409 slug_conflict.
        Response dupSlug = admin.postJson(platformToken, "/v1/tenants/{tid}/resource-servers",
                Map.of("slug", slug, "name", "Catalog Two", "audienceIdentifier", audience("catalog2")), tenant);
        assertThat(dupSlug.statusCode()).isEqualTo(409);
        assertThat(AdminApiClient.problemCode(dupSlug)).isEqualTo("resource_server.slug_conflict");

        // Same audience (globally unique) with a different slug → 409 audience_conflict.
        Response dupAud = admin.postJson(platformToken, "/v1/tenants/{tid}/resource-servers",
                Map.of("slug", Fixtures.slug("catalog3"), "name", "Catalog Three", "audienceIdentifier", aud), tenant);
        assertThat(dupAud.statusCode()).isEqualTo(409);
        assertThat(AdminApiClient.problemCode(dupAud)).isEqualTo("resource_server.audience_conflict");

        // Same slug in a DIFFERENT tenant → allowed (slug uniqueness is tenant-scoped).
        String otherTenant = admin.provisionActiveTenant(platformToken);
        Response otherTenantSameSlug = admin.postJson(platformToken, "/v1/tenants/{tid}/resource-servers",
                Map.of("slug", slug, "name", "Catalog Elsewhere", "audienceIdentifier", audience("catalog-elsewhere")),
                otherTenant);
        assertThat(otherTenantSameSlug.statusCode())
                .as("the same slug is free in another tenant").isEqualTo(201);
    }

    @Test
    @DisplayName("Scenario 3: audience is immutable on update; name/slug are mutable")
    void audienceImmutableNameSlugMutable() {
        AdminApiClient admin = adminApi();
        String originalAud = audience("shipping");
        String rsId = admin.createResourceServer(platformToken, tenant, Fixtures.slug("shipping"), "Shipping API", originalAud);

        // PATCH mutable fields AND attempt to sneak in a new audienceIdentifier (structurally absent from the update
        // command → ignored by Jackson). Succeeds; name/slug change; audience must be unchanged.
        String newName = "Shipping API v2";
        String newSlug = Fixtures.slug("shipping2");
        Response patch = admin.patchJson(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}",
                Map.of("name", newName, "slug", newSlug, "audienceIdentifier", "https://evil.example/hijacked"),
                tenant, rsId);
        assertThat(patch.statusCode()).isEqualTo(200);
        assertThat(patch.jsonPath().getString("name")).as("name is mutable").isEqualTo(newName);
        assertThat(patch.jsonPath().getString("slug")).as("slug is mutable").isEqualTo(newSlug);
        assertThat(patch.jsonPath().getString("audienceIdentifier"))
                .as("audience is immutable — the attempted change is ignored").isEqualTo(originalAud);

        // Re-read to be sure it persisted, not just echoed.
        Response reread = admin.get(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}", tenant, rsId);
        assertThat(reread.jsonPath().getString("audienceIdentifier")).isEqualTo(originalAud);
    }

    @Test
    @DisplayName("Scenario 4: scope CRUD — add, duplicate→409, update mutable fields, scope_name immutable, delete")
    void scopeCrudAndImmutability() {
        AdminApiClient admin = adminApi();
        String rsId = admin.createResourceServer(platformToken, tenant, Fixtures.slug("payments"), "Payments API", audience("payments"));

        // Add a scope.
        String scopeId = admin.addScope(platformToken, tenant, rsId, "refund", "Issue refunds", true, false);

        // Duplicate scope_name on the same RS → 409 scope_conflict.
        Response dup = admin.postJson(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/scopes",
                Map.of("scopeName", "refund", "description", "dup", "isDefault", false, "requiresConsent", false),
                tenant, rsId);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(AdminApiClient.problemCode(dup)).isEqualTo("resource_server.scope_conflict");

        // Update mutable fields AND attempt to rename scope_name (structurally absent → ignored).
        Response patch = admin.patchJson(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/scopes/{sid}",
                Map.of("description", "Issue partial refunds", "isDefault", true, "requiresConsent", false,
                        "scopeName", "renamed"),
                tenant, rsId, scopeId);
        assertThat(patch.statusCode()).isEqualTo(200);
        assertThat(patch.jsonPath().getString("description")).isEqualTo("Issue partial refunds");
        assertThat(patch.jsonPath().getBoolean("isDefault")).isTrue();
        assertThat(patch.jsonPath().getString("scopeName"))
                .as("scope_name is immutable — the attempted rename is ignored").isEqualTo("refund");

        // Delete the scope → 204.
        Response delete = admin.delete(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/scopes/{sid}",
                tenant, rsId, scopeId);
        assertThat(delete.statusCode()).isEqualTo(204);
        assertThat(scopeNames(admin, rsId)).as("the deleted scope is gone").doesNotContain("refund");
    }

    // ===================== Part 2 — application-tier RBAC =====================

    @Test
    @DisplayName("Scenario 5: create an application role (is_system=false) and bundle a same-RS scope")
    void createRoleAndBundleSameRsScope() {
        AdminApiClient admin = adminApi();
        String roleId = admin.createApplicationRole(platformToken, tenant, rsMain, "EDITOR-" + Fixtures.suffix());

        Response role = admin.get(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/roles/{roleId}",
                tenant, rsMain, roleId);
        assertThat(role.statusCode()).isEqualTo(200);
        assertThat(role.jsonPath().getBoolean("isSystem"))
                .as("application roles are never system by default").isFalse();

        // Bundle a scope from the SAME RS → 204.
        Response add = admin.post(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/roles/{roleId}/scopes/{sid}",
                tenant, rsMain, roleId, rsMainReadScope);
        assertThat(add.statusCode()).isEqualTo(204);

        // The role's scope bundle now lists it.
        Response scopes = admin.get(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/roles/{roleId}/scopes",
                tenant, rsMain, roleId);
        assertThat(scopes.jsonPath().getList("items.id")).contains(rsMainReadScope);
    }

    @Test
    @DisplayName("Scenario 6: bundling a scope from a DIFFERENT resource server is rejected (the key assertion)")
    void crossRsScopeBundlingRejected() {
        AdminApiClient admin = adminApi();
        String roleId = admin.createApplicationRole(platformToken, tenant, rsMain, "VIEWER-" + Fixtures.suffix());

        // rsOtherReadScope belongs to rsOther, but the role belongs to rsMain → mismatch.
        Response add = admin.post(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/roles/{roleId}/scopes/{sid}",
                tenant, rsMain, roleId, rsOtherReadScope);
        assertThat(add.statusCode())
                .as("a role for RS-A must not bundle RS-B's scope (VALIDATION → 400)").isEqualTo(400);
        assertThat(AdminApiClient.problemCode(add)).isEqualTo("application_role.scope_rs_mismatch");
    }

    @Test
    @DisplayName("Scenario 7+8: assign an application role to a user, then revoke — proven via user_application_roles")
    void assignAndRevokeApplicationRole() {
        AdminApiClient admin = adminApi();
        String roleId = admin.createApplicationRole(platformToken, tenant, rsMain, "MEMBER-" + Fixtures.suffix());

        // Assign → 204. No admin GET exposes a user's application-role assignments, so prove the side effect in the DB.
        Response assign = admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/application-roles/{roleId}",
                tenant, userId, roleId);
        assertThat(assign.statusCode()).isEqualTo(204);
        assertThat(assignmentCount(roleId)).as("assignment row created").isEqualTo(1);

        // Assign again → idempotent 204, still exactly one row.
        assertThat(admin.post(platformToken, "/v1/tenants/{tid}/users/{uid}/application-roles/{roleId}",
                tenant, userId, roleId).statusCode()).isEqualTo(204);
        assertThat(assignmentCount(roleId)).as("re-assign is idempotent (no duplicate row)").isEqualTo(1);

        // Revoke → 204, row gone.
        Response revoke = admin.delete(platformToken, "/v1/tenants/{tid}/users/{uid}/application-roles/{roleId}",
                tenant, userId, roleId);
        assertThat(revoke.statusCode()).isEqualTo(204);
        assertThat(assignmentCount(roleId)).as("assignment row removed").isZero();

        // Revoke again → idempotent 204.
        assertThat(admin.delete(platformToken, "/v1/tenants/{tid}/users/{uid}/application-roles/{roleId}",
                tenant, userId, roleId).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("Scenario 10: a foreign tenant admin cannot reach this tenant's RS/role endpoints (403)")
    void tenantScopingGuardsThisEndpointFamily() {
        AdminApiClient admin = adminApi();

        // Stand up a second tenant with a logged-in TENANT_ADMIN (its token is scoped to ITS tenant only).
        String foreignTenant = admin.provisionActiveTenant(platformToken);
        ClientCredentials foreignClient = admin.registerConfidentialClient(platformToken, foreignTenant);
        String foreignAdminEmail = Fixtures.email("foreign-admin");
        String foreignAdminId = admin.createActiveUser(platformToken, foreignTenant, foreignAdminEmail, PASSWORD);
        admin.assignTenantRole(platformToken, foreignTenant, foreignAdminId, "TENANT_ADMIN");
        String foreignToken = oauthFlow().login(foreignClient.clientId(), foreignClient.secret(),
                foreignAdminEmail, PASSWORD).tokens().accessToken();

        // The foreign admin token must NOT reach rsMain's tenant RS or role endpoints.
        assertThat(admin.get(foreignToken, "/v1/tenants/{tid}/resource-servers", tenant).statusCode())
                .as("foreign tenant admin cannot list this tenant's resource servers").isEqualTo(403);
        assertThat(admin.get(foreignToken, "/v1/tenants/{tid}/resource-servers/{rsId}/roles", tenant, rsMain).statusCode())
                .as("foreign tenant admin cannot list this tenant's application roles").isEqualTo(403);

        // Sanity: the platform admin transcends tenant scoping on the same endpoints.
        assertThat(admin.get(platformToken, "/v1/tenants/{tid}/resource-servers", tenant).statusCode()).isEqualTo(200);
    }

    // ---- helpers -----------------------------------------------------------

    private static long assignmentCount(String roleId) {
        // user_id / application_role_id are uuid columns → bind UUID params (the Db helper maps them to uuid).
        Map<String, Object> row = db().queryOne(
                        "select count(*) as c from user_application_roles where user_id = ? and application_role_id = ?",
                        java.util.UUID.fromString(userId), java.util.UUID.fromString(roleId))
                .orElseThrow(() -> new AssertionError("count query returned no row"));
        return ((Number) row.get("c")).longValue();
    }

    private static List<String> scopeNames(AdminApiClient admin, String rsId) {
        return admin.get(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/scopes", tenant, rsId)
                .jsonPath().getList("items.scopeName");
    }

    /** A globally-unique https audience URI for a resource server fixture. */
    private static String audience(String base) {
        return "https://" + base + "-" + Fixtures.suffix() + ".rs.closeauth.test";
    }
}
