package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.service.SystemRoleNames;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the "Option A" auto-provisioned admin-console client end-to-end against live Postgres + Redis. Gated on
 * {@code -Dcloseauth.it.db.url=...} (self-skips in plain {@code mvn test}), same pattern as
 * {@link com.anterka.closeauthbackend.auth.AuthCodeFlowIntegrationTest}.
 *
 * <p>Covers, in order:
 * <ol>
 *   <li>tenant provisioning auto-creates {@code admin-console-{slug}} with exactly the locked-in properties;</li>
 *   <li>a real Authorization Code + PKCE round trip using ONLY the client_id (no secret anywhere, including no
 *       {@code Authorization} header on the token exchange) yields a token with {@code tenant_roles} including
 *       {@code TENANT_ADMIN};</li>
 *   <li><b>the load-bearing proof</b>: with that same browser session still valid, a SECOND {@code /oauth2/authorize}
 *       hit (same client, fresh PKCE challenge, no fresh login) is recognized via SSO with zero user interaction and
 *       yields a fresh code/token — proving the "no refresh_token grant needed" design actually holds.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class AdminConsoleClientProvisioningIntegrationTest {

    // Fixed port: LoginSuccessResponder's resume redirect is built from properties.getIssuerUrl() (an ABSOLUTE URL),
    // so issuer-url must equal this server's real bound address, exactly as AuthCodeFlowIntegrationTest requires.
    private static final int PORT = 19199;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("server.port", () -> PORT);
        registry.add("closeauth.issuer-url", () -> "http://localhost:" + PORT + "/closeauth");
        registry.add("closeauth.session.cookie.secure", () -> "false");
    }

    @Autowired TenantService tenantService;
    @Autowired UserService userService;
    @Autowired TenantRoleService tenantRoleService;
    @Autowired TenantRoleRepository tenantRoleRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired CloseAuthProperties properties;

    private static final String PASSWORD = "password123";
    private final ObjectMapper json = new ObjectMapper();

    private String base() {
        return "http://localhost:" + PORT + "/closeauth";
    }

    // ==================== 1. provisioning creates the client with exactly the locked-in properties ====================

    @Test
    void tenantProvisioningAutoCreatesAdminConsoleClientWithLockedInProperties() {
        String slug = "t-" + rnd();
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand(slug, "T"));

        String expectedClientId = "admin-console-" + slug;
        RegisteredClient client = registeredClientRepository.findByClientId(expectedClientId);

        assertThat(client).as("admin-console client should exist immediately after provisioning, PRE-activation")
                .isNotNull();
        // public: no secret, auth method NONE.
        assertThat(client.getClientSecret()).isNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        // PKCE required.
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        // trusted: consent skipped.
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        // scope: openid profile ONLY.
        assertThat(client.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        // redirect_uris: exactly the configured bff.admin-callback value.
        assertThat(client.getRedirectUris()).containsExactly(properties.getBff().getAdminCallback());
        // grant types: authorization_code ONLY — no refresh_token.
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(client.getAuthorizationGrantTypes()).doesNotContain(AuthorizationGrantType.REFRESH_TOKEN);

        assertThat(CloseAuthClientSettings.getTenantId(client)).isEqualTo(tenant.id());
    }

    // ==================== 2 & 3. real round trip + silent re-authorization ====================

    @Test
    void realAuthCodePkceRoundTripThenSilentReauthorizationWithNoRefreshTokenAndNoSecret() throws Exception {
        UUID tenantId = activeTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        String email = "admin-" + rnd() + "@x.com";
        UUID userId = userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                email, PASSWORD, "Admin", "User", null, UserStatus.ACTIVE)).id();

        // Assign TENANT_ADMIN.
        TenantRole tenantAdminRole = tenantRoleRepository.findByTenantIdAndName(tenantId, SystemRoleNames.TENANT_ADMIN)
                .orElseThrow(() -> new AssertionError("TENANT_ADMIN starter-pack role missing"));
        tenantRoleService.assignTenantRole(ctx, userId, tenantAdminRole.getId(), null);

        String clientId = adminConsoleClientId(tenantId);
        assertThat(registeredClientRepository.findByClientId(clientId))
                .as("admin-console client must exist for this tenant").isNotNull();

        String redirectUri = properties.getBff().getAdminCallback();
        HttpClient browser = browser();

        // ---- STEP A: full login → SSO-recognized resume → code → token (NO secret at any point). ----
        Pkce pkce1 = pkce();
        Map<String, String> authorizeParams1 = authorizeParams(clientId, redirectUri, pkce1);
        HttpResponse<String> authorize1 = authorize(browser, authorizeParams1);
        assertThat(authorize1.statusCode()).isEqualTo(302);
        assertThat(location(authorize1)).contains("/login");

        Map<String, String> loginForm = new java.util.LinkedHashMap<>(authorizeParams1);
        loginForm.put("email", email);
        loginForm.put("password", PASSWORD);
        HttpResponse<String> login = postForm(browser, base() + "/login", loginForm, null);
        assertThat(login.statusCode()).isEqualTo(302);
        String resume = location(login);
        assertThat(resume).contains("/oauth2/authorize");

        HttpResponse<String> resumed = get(browser, resume);
        assertThat(resumed.statusCode()).isEqualTo(302);
        String redirect1 = location(resumed);
        assertThat(redirect1).as("trusted client: no consent screen, code issued directly")
                .startsWith(redirectUri).contains("code=");
        String code1 = queryParam(redirect1, "code");

        // Token exchange: NO Authorization header (public client) — client_id is a plain form field instead.
        HttpResponse<String> tokenResp1 = postFormNoAuth(browser, base() + "/oauth2/token", Map.of(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "code", code1,
                "redirect_uri", redirectUri,
                "code_verifier", pkce1.verifier()));
        assertThat(tokenResp1.statusCode()).as("token exchange must succeed with NO client secret / auth header")
                .isEqualTo(200);
        JsonNode body1 = json.readTree(tokenResp1.body());
        String accessToken1 = body1.get("access_token").asText();
        assertThat(accessToken1).isNotBlank();
        assertThat(body1.has("refresh_token")).as("no refresh_token grant registered -> none issued").isFalse();

        JsonNode claims1 = decodeJwtPayload(accessToken1);
        assertThat(claims1.get("sub").asText()).isEqualTo(userId.toString());
        assertThat(claims1.get("tenant_id").asText()).isEqualTo(tenantId.toString());
        assertThat(claims1.get("client_id").asText()).isEqualTo(clientId);
        List<String> tenantRoles = new java.util.ArrayList<>();
        claims1.get("tenant_roles").forEach(n -> tenantRoles.add(n.asText()));
        assertThat(tenantRoles).contains(SystemRoleNames.TENANT_ADMIN);

        // ---- STEP B (THE proof): SAME browser/session, NO fresh login, a SECOND /oauth2/authorize with a FRESH
        // PKCE challenge on the SAME client. SSO must recognize the still-valid session with ZERO user interaction
        // and issue a fresh code/token — proving session longevity does not depend on a refresh_token grant. ----
        Pkce pkce2 = pkce();
        Map<String, String> authorizeParams2 = authorizeParams(clientId, redirectUri, pkce2);
        HttpResponse<String> authorize2 = authorize(browser, authorizeParams2);
        assertThat(authorize2.statusCode()).as("SSO must short-circuit straight to a code, never to /login")
                .isEqualTo(302);
        String redirect2 = location(authorize2);
        assertThat(redirect2).as("zero user interaction: no /login redirect on the second authorize")
                .doesNotContain("/login");
        assertThat(redirect2).startsWith(redirectUri).contains("code=");
        String code2 = queryParam(redirect2, "code");
        assertThat(code2).isNotEqualTo(code1);

        HttpResponse<String> tokenResp2 = postFormNoAuth(browser, base() + "/oauth2/token", Map.of(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "code", code2,
                "redirect_uri", redirectUri,
                "code_verifier", pkce2.verifier()));
        assertThat(tokenResp2.statusCode()).as("silent-reauth token exchange must also succeed, no secret")
                .isEqualTo(200);
        JsonNode body2 = json.readTree(tokenResp2.body());
        String accessToken2 = body2.get("access_token").asText();
        assertThat(accessToken2).isNotBlank().isNotEqualTo(accessToken1);
        assertThat(body2.has("refresh_token")).isFalse();
        JsonNode claims2 = decodeJwtPayload(accessToken2);
        assertThat(claims2.get("sub").asText()).isEqualTo(userId.toString());
    }

    private String adminConsoleClientId(UUID tenantId) {
        return "admin-console-" + tenantService.getTenantById(tenantId).slug();
    }

    // ============================ flow helpers ============================

    private Map<String, String> authorizeParams(String clientId, String redirectUri, Pkce pkce) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("scope", "openid profile");
        params.put("state", rnd());
        params.put("code_challenge", pkce.challenge());
        params.put("code_challenge_method", "S256");
        return params;
    }

    private HttpResponse<String> authorize(HttpClient browser, Map<String, String> params) throws Exception {
        StringBuilder query = new StringBuilder();
        params.forEach((k, v) -> {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(enc(k)).append('=').append(enc(v));
        });
        return get(browser, base() + "/oauth2/authorize?" + query);
    }

    // ============================ HTTP plumbing ============================

    private HttpClient browser() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager())
                .build();
    }

    private HttpResponse<String> get(HttpClient browser, String url) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(HttpClient browser, String url, Map<String, String> form, String authHeader)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Explicitly no {@code Authorization} header at all — proves the public client needs none. */
    private HttpResponse<String> postFormNoAuth(HttpClient browser, String url, Map<String, String> form)
            throws Exception {
        return postForm(browser, url, form, null);
    }

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    private static String formEncode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(k)).append('=').append(enc(v));
        });
        return sb.toString();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String queryParam(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("query param " + name + " not found in " + url);
    }

    private JsonNode decodeJwtPayload(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        return json.readTree(Base64.getUrlDecoder().decode(payload));
    }

    // ============================ PKCE ============================

    private record Pkce(String verifier, String challenge) {}

    private Pkce pkce() throws Exception {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return new Pkce(verifier, challenge);
    }

    // ============================ provisioning ============================

    private UUID activeTenant() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("t-" + rnd(), "T"));
        tenantService.activateTenant(tenant.id());
        return tenant.id();
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
