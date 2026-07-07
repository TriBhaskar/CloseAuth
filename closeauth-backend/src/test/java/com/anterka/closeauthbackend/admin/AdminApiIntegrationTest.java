package com.anterka.closeauthbackend.admin;

import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.platform.dto.CreatePlatformAdminCommand;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminView;
import com.anterka.closeauthbackend.platform.service.PlatformAdminService;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 7a end-to-end proof of the admin auth/authz model against live Postgres + Redis. Gated on
 * {@code -Dcloseauth.it.db.url=...} (self-skips in plain {@code mvn test}), matching the Stage 6a IT convention.
 *
 * <p>Proves, over real HTTP: (1) the platform-admin token path — {@code POST /v1/platform/auth/token} mints a token
 * whose shape has {@code roles=[PLATFORM_ADMIN]} and <b>no {@code tenant_id}</b> — and that it opens the platform-scoped
 * {@code /v1/platform/me}; (2) a missing token → 401 problem+json and bad credentials → a uniform 401; (3) the
 * <b>cross-tenant admin guard</b> — a tenant admin of A reaches {@code /v1/tenants/A/admin-ping} but is 403 for B, and a
 * tenant admin is 403 on the platform endpoint; (4) a platform admin may administer any tenant. Tenant-admin tokens are
 * forged with the app's own {@link JwtEncoder} (same JWK the resource server validates) so the authz layer is exercised
 * without driving the full auth-code flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class AdminApiIntegrationTest {

    private static final String BOOTSTRAP_EMAIL = "it-platform-admin@closeauth.test";
    private static final String BOOTSTRAP_PASSWORD = "It-Bootstrap-Pw-123!";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("closeauth.issuer-url", () -> "http://localhost:9099");
        // The bootstrap credential creates the initial platform admin at startup (idempotent across re-runs).
        registry.add("closeauth.platform-admin.bootstrap-email", () -> BOOTSTRAP_EMAIL);
        registry.add("closeauth.platform-admin.bootstrap-password", () -> BOOTSTRAP_PASSWORD);
    }

    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:}")
    String contextPath;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired CloseAuthProperties properties;
    @Autowired PlatformAdminService platformAdminService;
    @Autowired TenantService tenantService;
    @Autowired ClientRegistrationService clientRegistrationService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    // ---- platform-admin token path + platform-scoped gate -----------------

    @Test
    void platformAdminTokenHasCorrectShapeAndOpensPlatformMe() throws Exception {
        String token = obtainPlatformToken();

        // Token shape: platform roles present, NO tenant_id (its absence is the platform signal).
        JsonNode payload = decodeJwtPayload(token);
        assertThat(payload.get("token_use").asText()).isEqualTo("platform_admin");
        assertThat(payload.has("tenant_id")).isFalse();
        assertThat(readStrings(payload.get("roles"))).contains("PLATFORM_ADMIN");

        HttpResponse<String> me = get("/v1/platform/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(me.body());
        assertThat(body.get("email").asText()).isEqualTo(BOOTSTRAP_EMAIL);
        assertThat(readStrings(body.get("roles"))).contains("PLATFORM_ADMIN");
    }

    @Test
    void missingTokenYieldsProblemJson401() throws Exception {
        HttpResponse<String> me = get("/v1/platform/me", null);
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
    }

    @Test
    void badCredentialsYieldUniform401() throws Exception {
        HttpResponse<String> resp = postJson("/v1/platform/auth/token",
                "{\"email\":\"" + BOOTSTRAP_EMAIL + "\",\"password\":\"totally-wrong\"}", null);
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(resp.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        assertThat(mapper.readTree(resp.body()).get("code").asText()).isEqualTo("invalid_credentials");
    }

    @Test
    void tenantAdminIsForbiddenOnPlatformEndpoint() throws Exception {
        String tenantAdmin = forgeTenantAdminToken(UUID.randomUUID().toString());
        HttpResponse<String> me = get("/v1/platform/me", tenantAdmin);
        assertThat(me.statusCode()).isEqualTo(403); // a tenant admin never passes the platform gate
        assertThat(me.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
    }

    // ---- the cross-tenant admin guard -------------------------------------

    @Test
    void tenantAdminReachesOwnTenantButIsForbiddenForAnother() throws Exception {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        String tokenForA = forgeTenantAdminToken(tenantA);

        assertThat(get("/v1/tenants/" + tenantA + "/admin-ping", tokenForA).statusCode()).isEqualTo(200);
        assertThat(get("/v1/tenants/" + tenantB + "/admin-ping", tokenForA).statusCode()).isEqualTo(403); // guard
    }

    @Test
    void platformAdminMayAdministerAnyTenant() throws Exception {
        String token = obtainPlatformToken();
        String anyTenant = UUID.randomUUID().toString();
        assertThat(get("/v1/tenants/" + anyTenant + "/admin-ping", token).statusCode()).isEqualTo(200);
    }

    // ---- instant revocation (§7.8: the highest-value credential MUST be killable within the TTL) -----------

    @Test
    void platformAdminTokenIsInstantlyRevocableOnSuspend() throws Exception {
        // A DEDICATED admin so suspending it never disturbs the bootstrap admin the other tests rely on.
        String email = "revoke-" + rnd() + "@closeauth.test";
        String password = "Revoke-Me-Pw-123!";
        PlatformAdminView admin = platformAdminService.createPlatformAdmin(
                new CreatePlatformAdminCommand(email, password, "Revoke", "Me"));
        platformAdminService.assignRole(admin.id(), "PLATFORM_ADMIN");

        String token = login(email, password);
        String[] client = introspectionClient();

        // Before suspend: the token works on the admin API AND introspects active.
        assertThat(get("/v1/platform/me", token).statusCode()).isEqualTo(200);
        assertThat(introspect(client[0], client[1], token).get("active")).isEqualTo(Boolean.TRUE);

        // Suspending writes the sub-keyed platform-admin revocation marker.
        platformAdminService.suspend(admin.id());

        // After suspend: the admin API REJECTS the still-unexpired token (401) — killed within the TTL, not at expiry.
        assertThat(get("/v1/platform/me", token).statusCode()).isEqualTo(401);
        // And introspection now reports it inactive, with NO claim leakage (RFC 7662).
        Map<String, Object> after = introspect(client[0], client[1], token);
        assertThat(after.get("active")).isEqualTo(Boolean.FALSE);
        assertThat(after).doesNotContainKeys("sub", "token_use", "exp", "iat");
    }

    // ---- helpers ----------------------------------------------------------

    private String obtainPlatformToken() throws Exception {
        return login(BOOTSTRAP_EMAIL, BOOTSTRAP_PASSWORD);
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> resp = postJson("/v1/platform/auth/token",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null);
        assertThat(resp.statusCode()).isEqualTo(200);
        return mapper.readTree(resp.body()).get("access_token").asText();
    }

    /** Forges a tenant-admin-shaped token signed by the app's JWK (what the resource server validates). */
    private String forgeTenantAdminToken(String tenantId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuerUrl())
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .claim("roles", List.of())                       // no platform roles
                .claim("tenant_id", tenantId)
                .claim("tenant_roles", List.of("TENANT_ADMIN"))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode decodeJwtPayload(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        byte[] decoded = Base64.getUrlDecoder().decode(payload);
        return mapper.readTree(new String(decoded, StandardCharsets.UTF_8));
    }

    private List<String> readStrings(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }

    /** Registers a confidential client (able to authenticate to {@code /oauth2/introspect}). @return {clientId, secret}. */
    private String[] introspectionClient() {
        String clientId = "introspect-" + rnd();
        String secret = "introspect-secret-value";
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("acme-" + rnd(), "Acme"));
        tenantService.activateTenant(tenant.id());
        clientRegistrationService.registerClient(TenantContext.of(tenant.id()), new RegisterClientCommand(
                clientId, "Introspect Client", secret, List.of("client_credentials"),
                List.of("introspect:read"), null, false, true));
        return new String[]{clientId, secret};
    }

    private Map<String, Object> introspect(String clientId, String secret, String token) throws Exception {
        String body = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base() + "/oauth2/introspect"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.readValue(resp.body(), Map.class);
        return map;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String base() {
        return "http://localhost:" + port + contextPath; // context path (e.g. /closeauth) prefixes every route
    }
}
