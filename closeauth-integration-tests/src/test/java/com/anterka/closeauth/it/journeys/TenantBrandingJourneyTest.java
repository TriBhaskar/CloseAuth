package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.AdminApiClient.ClientCredentials;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-10's tenant-branding journey, black-box. Branding has two deliberately-separate surfaces, never tested before:
 * the <b>public</b> {@code GET /branding?client_id} resolution (renders pre-login on the hosted pages — it must leak
 * nothing beyond the five presentation fields and must never reveal whether a {@code client_id} exists), and the
 * <b>authenticated admin</b> {@code GET}/{@code PUT /v1/tenants/{tid}/branding} management surface (with strict
 * write-validation as the first layer of the branding-injection defense).
 *
 * <p>The key assertion is the public endpoint's exhaustive five-fields-and-nothing-else leak check. Independent test
 * methods share the container stack; the default-baseline scenarios read a never-customized shared tenant, while every
 * mutating scenario provisions its own tenant so ordering never matters.
 */
class TenantBrandingJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";
    /** The exact, complete public BrandingView key set — nothing else may ever appear (the leak-nothing property). */
    private static final Set<String> BRANDING_FIELDS =
            Set.of("logoUrl", "primaryColor", "backgroundColor", "accentColor", "companyName");
    // Platform defaults (CloseAuthProperties.Branding): colors set, logo + company name deliberately null.
    private static final String DEFAULT_PRIMARY = "#4F46E5";
    private static final String DEFAULT_BACKGROUND = "#FFFFFF";
    private static final String DEFAULT_ACCENT = "#22D3EE";

    private static String platformToken;
    private static String defaultTenant;          // provisioned, never customized → resolves to platform defaults
    private static String defaultClientId;         // resolves to defaultTenant on the public endpoint
    private static String defaultTenantAdminToken; // a TENANT_ADMIN of defaultTenant (read-only use)

    @BeforeAll
    static void fixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();
        defaultTenant = admin.provisionActiveTenant(platformToken);
        ClientCredentials client = admin.registerConfidentialClient(platformToken, defaultTenant);
        defaultClientId = client.clientId();
        defaultTenantAdminToken = tenantAdminToken(defaultTenant, client);
    }

    // ===================== Part 1 — public branding resolution =====================

    @Test
    @DisplayName("Scenario 1: a default/unbranded tenant resolves to platform defaults (companyName null, not a brand)")
    void publicDefaultTenantReturnsPlatformDefaults() {
        Response response = publicBranding(defaultClientId);
        assertThat(response.statusCode()).isEqualTo(200);
        assertDefaultBranding(response);
    }

    @Test
    @DisplayName("Scenario 2: the public response contains EXACTLY the five fields and nothing else (leak-nothing)")
    void publicResponseContainsExactlyTheFiveFieldsAndNothingElse() {
        Map<String, Object> body = bodyMap(publicBranding(defaultClientId));
        assertThat(body.keySet())
                .as("the public branding response must expose only the five presentation fields — no tenantId, "
                        + "status, internal ids, or client details")
                .containsExactlyInAnyOrderElementsOf(BRANDING_FIELDS);
    }

    @Test
    @DisplayName("Scenario 3: the public endpoint requires no authentication (it renders before login)")
    void publicEndpointRequiresNoAuthentication() {
        // publicBranding sends NO Authorization header at all.
        Response response = RestAssured.given().queryParam("client_id", defaultClientId).get("/branding");
        assertThat(response.statusCode()).as("resolves with no bearer token").isEqualTo(200);
    }

    @Test
    @DisplayName("Scenario 4: an unknown client_id returns 200 with platform defaults — never a 404 or existence signal")
    void publicUnknownClientIdReturns200WithDefaultsNotAnExistenceSignal() {
        Response unknown = publicBranding("does-not-exist-" + Fixtures.suffix());
        assertThat(unknown.statusCode()).as("unknown client_id must not 404").isEqualTo(200);
        // Byte-for-byte identical to a known-but-unbranded tenant's response → no way to tell the two apart.
        assertDefaultBranding(unknown);
        assertThat(bodyMap(unknown).keySet()).containsExactlyInAnyOrderElementsOf(BRANDING_FIELDS);
    }

    @Test
    @DisplayName("Scenario 5: custom branding set via the admin PUT is reflected on the public endpoint")
    void publicReflectsCustomBrandingOnceSet() {
        AdminApiClient admin = adminApi();
        String tenant = admin.provisionActiveTenant(platformToken);
        ClientCredentials client = admin.registerConfidentialClient(platformToken, tenant);

        String logo = "https://cdn.example.com/" + Fixtures.suffix() + ".png";
        String company = "Acme " + Fixtures.suffix();
        admin.putJson(platformToken, "/v1/tenants/{tid}/branding",
                Map.of("logoUrl", logo, "primaryColor", "#101112", "backgroundColor", "#F0F0F0",
                        "accentColor", "#ABCDEF", "companyName", company), tenant)
                .then().statusCode(200);

        Response resolved = publicBranding(client.clientId());
        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(resolved.jsonPath().getString("logoUrl")).isEqualTo(logo);
        assertThat(resolved.jsonPath().getString("primaryColor")).isEqualTo("#101112");
        assertThat(resolved.jsonPath().getString("backgroundColor")).isEqualTo("#F0F0F0");
        assertThat(resolved.jsonPath().getString("accentColor")).isEqualTo("#ABCDEF");
        assertThat(resolved.jsonPath().getString("companyName")).isEqualTo(company);
        // Still exactly five fields even when fully branded.
        assertThat(bodyMap(resolved).keySet()).containsExactlyInAnyOrderElementsOf(BRANDING_FIELDS);
    }

    // ===================== Part 2 — authenticated admin branding management =====================

    @Test
    @DisplayName("Scenario 6: the admin GET returns the same resolved BrandingView shape as the public endpoint")
    void adminGetReturnsTheSameResolvedBrandingViewAsPublic() {
        Response response = adminApi().get(defaultTenantAdminToken, "/v1/tenants/{tid}/branding", defaultTenant);
        assertThat(response.statusCode()).isEqualTo(200);
        // Identical shape to the public view (backend returns the same BrandingView, default-resolved) — no admin-only
        // fields, so the same exhaustive key set and default values hold here too.
        assertThat(bodyMap(response).keySet()).containsExactlyInAnyOrderElementsOf(BRANDING_FIELDS);
        assertDefaultBranding(response);
    }

    @Test
    @DisplayName("Scenario 7+10: a valid admin PUT is reflected on a follow-up GET and emits TENANT_BRANDING_CHANGED")
    void adminPutValidBrandingReflectsAndIsAudited() {
        AdminApiClient admin = adminApi();
        String tenant = admin.provisionActiveTenant(platformToken);

        String logo = "https://assets.example.org/brand/" + Fixtures.suffix() + ".svg";
        String company = "Globex " + Fixtures.suffix();
        Response put = admin.putJson(platformToken, "/v1/tenants/{tid}/branding",
                Map.of("logoUrl", logo, "primaryColor", "#123ABC", "backgroundColor", "#FFFFFF",
                        "accentColor", "#00FF00", "companyName", company), tenant);
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(put.jsonPath().getString("primaryColor")).isEqualTo("#123ABC");

        // A follow-up GET reflects the change (persisted, not just echoed).
        Response get = admin.get(platformToken, "/v1/tenants/{tid}/branding", tenant);
        assertThat(get.jsonPath().getString("logoUrl")).isEqualTo(logo);
        assertThat(get.jsonPath().getString("companyName")).isEqualTo(company);
        assertThat(get.jsonPath().getString("accentColor")).isEqualTo("#00FF00");

        // Bonus (IT-8 machinery): the mutation is auditable. Audit is async (outbox → ~2s drain), so poll.
        assertThat(awaitAuditPresent(tenant, "TENANT_BRANDING_CHANGED"))
                .as("the branding change is recorded as TENANT_BRANDING_CHANGED").isTrue();
    }

    @Test
    @DisplayName("Scenario 8: the admin PUT rejects malformed colors and non-https / malformed logo URLs (400)")
    void adminPutRejectsMalformedColorsAndNonHttpsUrls() {
        AdminApiClient admin = adminApi();
        String tenant = admin.provisionActiveTenant(platformToken);

        // Color missing the '#' → bean validation (@Pattern) at the controller edge.
        Response noHash = putBranding(tenant, "primaryColor", "4F46E5");
        assertThat(noHash.statusCode()).isEqualTo(400);
        assertThat(AdminApiClient.problemCode(noHash)).isEqualTo("validation.failed");

        // Color with invalid length/characters → same.
        assertThat(putBranding(tenant, "backgroundColor", "#12345").statusCode()).isEqualTo(400);
        assertThat(AdminApiClient.problemCode(putBranding(tenant, "accentColor", "#GGGGGG"))).isEqualTo("validation.failed");

        // A non-https (http) logo URL → service-layer rejection (mixed-content defense).
        Response http = putBranding(tenant, "logoUrl", "http://example.com/logo.png");
        assertThat(http.statusCode()).isEqualTo(400);
        assertThat(AdminApiClient.problemCode(http)).isEqualTo("branding.logo_url_not_https");

        // A malformed URL (illegal character) → service-layer rejection.
        Response malformed = putBranding(tenant, "logoUrl", "https://bad url.example/logo.png");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(AdminApiClient.problemCode(malformed)).isEqualTo("branding.invalid_logo_url");
    }

    @Test
    @DisplayName("Scenario 9: branding endpoints are tenant-scoped both directions (foreign admin → 403)")
    void brandingEndpointsAreTenantScopedBothDirections() {
        AdminApiClient admin = adminApi();

        String target = admin.provisionActiveTenant(platformToken);
        ClientCredentials targetClient = admin.registerConfidentialClient(platformToken, target);
        String targetAdmin = tenantAdminToken(target, targetClient);

        String foreign = admin.provisionActiveTenant(platformToken);
        ClientCredentials foreignClient = admin.registerConfidentialClient(platformToken, foreign);
        String foreignAdmin = tenantAdminToken(foreign, foreignClient);

        // Foreign tenant admin cannot read OR write the target's branding.
        assertThat(admin.get(foreignAdmin, "/v1/tenants/{tid}/branding", target).statusCode())
                .as("foreign admin cannot GET another tenant's branding").isEqualTo(403);
        assertThat(admin.putJson(foreignAdmin, "/v1/tenants/{tid}/branding",
                Map.of("primaryColor", "#010203"), target).statusCode())
                .as("foreign admin cannot PUT another tenant's branding").isEqualTo(403);

        // Contrast: the target's own admin token works on its own tenant.
        assertThat(admin.get(targetAdmin, "/v1/tenants/{tid}/branding", target).statusCode())
                .as("a tenant admin reaches its own branding").isEqualTo(200);
    }

    // ---- helpers -----------------------------------------------------------

    /** The public branding endpoint, called with NO Authorization header (as the pre-login hosted page would). */
    private static Response publicBranding(String clientId) {
        return RestAssured.given().queryParam("client_id", clientId).get("/branding");
    }

    /** A single-field branding PUT (the other fields absent/null) — for the validation-rejection cases. */
    private static Response putBranding(String tenant, String field, String value) {
        return adminApi().putJson(platformToken, "/v1/tenants/{tid}/branding", Map.of(field, value), tenant);
    }

    private static void assertDefaultBranding(Response response) {
        // Read via the parsed body map: a JSON null field decodes to a Java null here, whereas jsonPath().getString(...)
        // coerces a JSON null to "" — so the map is the reliable way to assert the "defaults to null" fields.
        Map<String, Object> body = bodyMap(response);
        assertThat(body.get("primaryColor")).isEqualTo(DEFAULT_PRIMARY);
        assertThat(body.get("backgroundColor")).isEqualTo(DEFAULT_BACKGROUND);
        assertThat(body.get("accentColor")).isEqualTo(DEFAULT_ACCENT);
        assertUnset(body.get("logoUrl"), "no platform default logo");
        assertUnset(body.get("companyName"), "companyName defaults to unset, not a hardcoded platform brand");
    }

    /**
     * Asserts a branding field is the decided "unset" representation: the empty string {@code ""} — explicitly NOT
     * {@code null}. The platform defaults for {@code logoUrl}/{@code companyName} resolve to "" (application.yml binds
     * them via the {@code ${ENV:}} placeholder whose unset default is ""); this is the confirmed, accepted contract
     * (IT-10 / FEATURES_AND_USE_CASES §10). Asserting exactly "" locks it in, so a future accidental switch to null —
     * e.g. someone "fixing" this without realizing it was a deliberate choice — is caught here as a regression.
     */
    private static void assertUnset(Object value, String description) {
        assertThat(value).as(description).isEqualTo("");
    }

    private static Map<String, Object> bodyMap(Response response) {
        return response.jsonPath().getMap("$");
    }

    /** Creates a TENANT_ADMIN user in the tenant and logs it in via the given client → its access token. */
    private static String tenantAdminToken(String tenantId, ClientCredentials client) {
        AdminApiClient admin = adminApi();
        String email = Fixtures.email("brand-admin");
        String userId = admin.createActiveUser(platformToken, tenantId, email, PASSWORD);
        admin.assignTenantRole(platformToken, tenantId, userId, "TENANT_ADMIN");
        return oauthFlow().login(client.clientId(), client.secret(), email, PASSWORD).tokens().accessToken();
    }

    /** Polls the tenant audit query until at least one event of {@code eventType} has drained (2s outbox interval). */
    private static boolean awaitAuditPresent(String tenantId, String eventType) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Response q = adminApi().getQuery(platformToken, "/v1/tenants/{tid}/audit-events",
                    Map.of("event_type", eventType), tenantId);
            if (q.statusCode() == 200 && q.jsonPath().getLong("totalElements") >= 1) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }
}
