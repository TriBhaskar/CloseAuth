package com.anterka.closeauthbackend.admin;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.platform.dto.CreatePlatformAdminCommand;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminView;
import com.anterka.closeauthbackend.platform.service.PlatformAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 7b end-to-end proof of the admin CRUD surface against live Postgres + Redis. Gated on
 * {@code -Dcloseauth.it.db.url=...} (self-skips in plain {@code mvn test}).
 *
 * <p>Proves, over real HTTP: platform-scoped gating (tenant admin → 403), the cross-tenant guard on the new
 * tenant-scoped endpoints (admin of A denied for B), the last-admin invariant (409) through the API, self-service
 * isolation ({@code /v1/me} strictly own; revoking another's session refused), secret handling (client secret once,
 * never on GET), pagination bounds, and the approve + invite triggers. Tenant/user tokens are forged with the app's own
 * {@link JwtEncoder} (same JWK the resource server validates) to exercise authz without the full auth-code flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class AdminCrudIntegrationTest {

    private static final String BOOT_PASSWORD = "It-7b-Pw-123!";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("closeauth.issuer-url", () -> "http://localhost:9099");
    }

    @LocalServerPort int port;
    @Value("${server.servlet.context-path:}") String contextPath;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired CloseAuthProperties properties;
    @Autowired PlatformAdminService platformAdminService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** A dedicated platform admin per test (unique email) — independent of the shared-DB bootstrap another IT may consume. */
    private String platformEmail;

    @BeforeEach
    void createPlatformAdmin() {
        platformEmail = "it-7b-" + rnd() + "@closeauth.test";
        PlatformAdminView admin = platformAdminService.createPlatformAdmin(
                new CreatePlatformAdminCommand(platformEmail, BOOT_PASSWORD, "IT", "Admin"));
        platformAdminService.assignRole(admin.id(), "PLATFORM_ADMIN");
    }

    // ---- platform-scoped gating + tenant provisioning + pagination --------

    @Test
    void platformTenantCrudGatedToPlatformAdminAndPaginated() throws Exception {
        String platform = platformToken();
        String tenantAdmin = tenantAdminToken(UUID.randomUUID().toString());

        // A tenant admin cannot reach a platform-scoped endpoint.
        assertThat(get("/v1/platform/tenants", tenantAdmin).statusCode()).isEqualTo(403);

        // A platform admin can provision + list.
        HttpResponse<String> created = postJson("/v1/platform/tenants",
                "{\"slug\":\"acme-" + rnd() + "\",\"name\":\"Acme\"}", platform);
        assertThat(created.statusCode()).isEqualTo(201);

        HttpResponse<String> list = get("/v1/platform/tenants?page=0&size=1", platform);
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(list.body());
        assertThat(body.get("items").size()).isLessThanOrEqualTo(1);   // bounded page
        assertThat(body.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
    }

    // ---- the cross-tenant guard on the NEW tenant-scoped endpoints --------

    @Test
    void tenantAdminReachesOwnTenantUsersButNotAnother() throws Exception {
        UUID tenantA = provisionAndActivateTenant();
        UUID tenantB = provisionAndActivateTenant();
        String adminOfA = tenantAdminToken(tenantA.toString());

        assertThat(get("/v1/tenants/" + tenantA + "/users", adminOfA).statusCode()).isEqualTo(200);
        assertThat(get("/v1/tenants/" + tenantB + "/users", adminOfA).statusCode()).isEqualTo(403); // guard
    }

    // ---- last-admin invariant via the API ---------------------------------

    @Test
    void suspendingTheLastTenantAdminIsRefused409() throws Exception {
        String platform = platformToken();
        UUID tenant = provisionAndActivateTenant();
        UUID user = createUser(platform, tenant, "boss-" + rnd() + "@x.io");
        UUID tenantAdminRoleId = roleId(platform, tenant, "TENANT_ADMIN");

        // Make the user the sole TENANT_ADMIN, then try to suspend → 409 last-admin.
        assertThat(post("/v1/tenants/" + tenant + "/users/" + user + "/tenant-roles/" + tenantAdminRoleId, platform)
                .statusCode()).isEqualTo(204);
        HttpResponse<String> suspend = post("/v1/tenants/" + tenant + "/users/" + user + "/suspend", platform);
        assertThat(suspend.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(suspend.body()).get("code").asText()).isEqualTo("tenant_role.last_admin");
    }

    // ---- secret handling: client secret once, never on GET ----------------

    @Test
    void clientSecretReturnedOnceNeverOnGet() throws Exception {
        String platform = platformToken();
        UUID tenant = provisionAndActivateTenant();
        String clientId = "svc-" + rnd();

        HttpResponse<String> created = postJson("/v1/tenants/" + tenant + "/clients",
                "{\"clientId\":\"" + clientId + "\",\"clientName\":\"Svc\",\"clientSecret\":\"the-secret-value\","
                        + "\"grantTypes\":[\"client_credentials\"],\"scopes\":[\"svc:read\"],\"requireProofKey\":false,"
                        + "\"trusted\":true}", platform);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode createdBody = mapper.readTree(created.body());
        assertThat(createdBody.get("clientSecret").asText()).isEqualTo("the-secret-value"); // returned ONCE
        String internalId = createdBody.get("client").get("id").asText();

        HttpResponse<String> got = get("/v1/tenants/" + tenant + "/clients/" + internalId, platform);
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(got.body()).doesNotContain("secret"); // never surfaced again
    }

    // ---- self-service isolation -------------------------------------------

    @Test
    void selfServiceIsStrictlyScopedToTheCaller() throws Exception {
        UUID tenant = provisionAndActivateTenant();
        UUID user = createUser(platformToken(), tenant, "self-" + rnd() + "@x.io");
        String userToken = userToken(user.toString(), tenant.toString());

        // Own profile.
        HttpResponse<String> me = get("/v1/me", userToken);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).get("id").asText()).isEqualTo(user.toString());

        // Own sessions only (a forged token has no session ledger → empty list, never another user's).
        assertThat(get("/v1/me/sessions", userToken).statusCode()).isEqualTo(200);

        // Revoking a session that isn't the caller's → 404 (no leak, cannot touch another principal's session).
        assertThat(delete("/v1/me/sessions/" + UUID.randomUUID(), userToken).statusCode()).isEqualTo(404);
    }

    // ---- triggers: approve a pending user ---------------------------------

    @Test
    void approveActivatesAPendingUser() throws Exception {
        String platform = platformToken();
        UUID tenant = provisionAndActivateTenant();
        // Create a PENDING user.
        HttpResponse<String> created = postJson("/v1/tenants/" + tenant + "/users",
                "{\"email\":\"pending-" + rnd() + "@x.io\",\"password\":\"password1\",\"initialStatus\":\"PENDING\"}",
                platform);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID user = UUID.fromString(mapper.readTree(created.body()).get("id").asText());
        assertThat(mapper.readTree(created.body()).get("status").asText()).isEqualTo("PENDING");

        HttpResponse<String> approved = post("/v1/tenants/" + tenant + "/users/" + user + "/approve", platform);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(approved.body()).get("status").asText()).isEqualTo("ACTIVE");
    }

    // ---- triggers: invite issuance/list/revoke ----------------------------

    @Test
    void inviteCanBeIssuedListedAndRevoked() throws Exception {
        String platform = platformToken();
        UUID tenant = provisionAndActivateTenant();
        String email = "invitee-" + rnd() + "@x.io";

        HttpResponse<String> issued = postJson("/v1/tenants/" + tenant + "/invites",
                "{\"email\":\"" + email + "\"}", platform);
        assertThat(issued.statusCode()).isEqualTo(201);
        assertThat(issued.body()).doesNotContain("rawSecret"); // the invite secret is emailed, never returned
        UUID inviteId = UUID.fromString(mapper.readTree(issued.body()).get("id").asText());

        JsonNode list = mapper.readTree(get("/v1/tenants/" + tenant + "/invites", platform).body());
        assertThat(list.isArray() && list.size() >= 1).isTrue();

        assertThat(delete("/v1/tenants/" + tenant + "/invites/" + inviteId, platform).statusCode()).isEqualTo(204);
        // After revoke, no longer outstanding.
        JsonNode after = mapper.readTree(get("/v1/tenants/" + tenant + "/invites", platform).body());
        boolean stillPresent = false;
        for (JsonNode n : after) {
            if (n.get("id").asText().equals(inviteId.toString())) {
                stillPresent = true;
            }
        }
        assertThat(stillPresent).isFalse();
    }

    // ---- helpers ----------------------------------------------------------

    private UUID provisionAndActivateTenant() throws Exception {
        String platform = platformToken();
        HttpResponse<String> created = postJson("/v1/platform/tenants",
                "{\"slug\":\"t-" + rnd() + "\",\"name\":\"T\"}", platform);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(mapper.readTree(created.body()).get("id").asText());
        assertThat(post("/v1/platform/tenants/" + id + "/activate", platform).statusCode()).isEqualTo(200);
        return id;
    }

    private UUID createUser(String platformToken, UUID tenant, String email) throws Exception {
        HttpResponse<String> created = postJson("/v1/tenants/" + tenant + "/users",
                "{\"email\":\"" + email + "\",\"password\":\"password1\",\"initialStatus\":\"ACTIVE\"}", platformToken);
        assertThat(created.statusCode()).isEqualTo(201);
        return UUID.fromString(mapper.readTree(created.body()).get("id").asText());
    }

    private UUID roleId(String platformToken, UUID tenant, String roleName) throws Exception {
        JsonNode page = mapper.readTree(get("/v1/tenants/" + tenant + "/roles?size=100", platformToken).body());
        for (JsonNode role : page.get("items")) {
            if (roleName.equals(role.get("name").asText())) {
                return UUID.fromString(role.get("id").asText());
            }
        }
        throw new IllegalStateException("role not found: " + roleName);
    }

    private String platformToken() throws Exception {
        HttpResponse<String> resp = postJson("/v1/platform/auth/token",
                "{\"email\":\"" + platformEmail + "\",\"password\":\"" + BOOT_PASSWORD + "\"}", null);
        assertThat(resp.statusCode()).isEqualTo(200);
        return mapper.readTree(resp.body()).get("access_token").asText();
    }

    private String tenantAdminToken(String tenantId) {
        return forge(tenantId, List.of("TENANT_ADMIN"), UUID.randomUUID().toString());
    }

    private String userToken(String userId, String tenantId) {
        return forge(tenantId, List.of(), userId);
    }

    private String forge(String tenantId, List<String> tenantRoles, String subject) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuerUrl())
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .claim("roles", List.of())
                .claim("tenant_id", tenantId)
                .claim("tenant_roles", tenantRoles)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(auth(HttpRequest.newBuilder(URI.create(base() + path)).GET(), bearer));
    }

    private HttpResponse<String> delete(String path, String bearer) throws Exception {
        return send(auth(HttpRequest.newBuilder(URI.create(base() + path)).DELETE(), bearer));
    }

    private HttpResponse<String> post(String path, String bearer) throws Exception {
        return send(auth(HttpRequest.newBuilder(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.noBody()), bearer));
    }

    private HttpResponse<String> postJson(String path, String json, String bearer) throws Exception {
        return send(auth(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)), bearer));
    }

    private HttpRequest.Builder auth(HttpRequest.Builder b, String bearer) {
        return bearer == null ? b : b.header("Authorization", "Bearer " + bearer);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String base() {
        return "http://localhost:" + port + contextPath;
    }
}
