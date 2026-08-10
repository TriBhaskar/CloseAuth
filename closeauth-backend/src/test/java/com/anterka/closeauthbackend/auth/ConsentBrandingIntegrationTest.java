package com.anterka.closeauthbackend.auth;

import com.anterka.closeauthbackend.client.dto.ClientCreatedView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.resourceserver.dto.AddScopeCommand;
import com.anterka.closeauthbackend.resourceserver.dto.CreateResourceServerCommand;
import com.anterka.closeauthbackend.resourceserver.dto.ResourceServerView;
import com.anterka.closeauthbackend.resourceserver.service.ResourceServerService;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.dto.UpdateBrandingCommand;
import com.anterka.closeauthbackend.tenant.service.TenantBrandingService;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 6b-ii end-to-end against live Postgres + Redis: the OAuth consent flow (scope descriptions, requires_consent
 * auto-grant, persistence/no-reprompt, deny → access_denied) and the public branding resolution endpoint (only
 * non-sensitive fields). Gated on {@code -Dcloseauth.it.db.url}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class ConsentBrandingIntegrationTest {

    // A FIXED port, not RANDOM_PORT: post cross-origin-login fix, LoginSuccessResponder's resume redirect is built
    // from properties.getIssuerUrl() + the authorization endpoint path (an ABSOLUTE URL) — issuer-url must equal
    // this server's real bound address for that redirect to actually be reachable by this test's HTTP client.
    private static final int PORT = 9097;

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
        // A deliberately distinct, "foreign" origin standing in for the real BFF — proves the consent-required
        // redirect actually carries this configured, absolute value (not the old same-origin/relative
        // "/oauth2/consent" literal) and never needs to be reachable (see CLOSEAUTH_CONSENT_CROSS_ORIGIN_DESIGN.md).
        registry.add("closeauth.bff.consent-page", () -> "http://bff.example.invalid:8088/consent");
    }

    @Autowired TenantService tenantService;
    @Autowired UserService userService;
    @Autowired ClientRegistrationService clientRegistrationService;
    @Autowired ResourceServerService resourceServerService;
    @Autowired TenantBrandingService brandingService;

    private static final String REDIRECT = "http://127.0.0.1/callback";
    private static final String PASSWORD = "password123";
    private static final String BFF_CONSENT_PAGE = "http://bff.example.invalid:8088/consent";
    private final ObjectMapper json = new ObjectMapper();
    // UI-3c: the backend now generates each client's secret; capture per clientId rather than a shared constant.
    private final Map<String, String> clientSecrets = new HashMap<>();

    private String base() {
        return "http://localhost:" + PORT + "/closeauth";
    }

    // ============================ BRANDING ============================

    @Test
    void brandingResolutionExposesOnlyNonSensitiveFieldsWithDefaults() throws Exception {
        UUID tenantId = activeTenant();
        String clientId = trustedClient(TenantContext.of(tenantId));

        HttpResponse<String> response = get(client(), base() + "/branding?client_id=" + enc(clientId));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());

        // THE key security test: the public endpoint exposes ONLY these 5 branding fields — no tenant internals.
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "logoUrl", "primaryColor", "backgroundColor", "accentColor", "companyName");
        assertThat(body.get("primaryColor").asText()).isEqualTo("#4F46E5"); // platform default (tenant unset)
    }

    @Test
    void brandingReflectsTenantUpdateAndUnknownClientGetsDefaults() throws Exception {
        UUID tenantId = activeTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        String clientId = trustedClient(ctx);
        brandingService.updateBranding(ctx, new UpdateBrandingCommand(
                "https://cdn.example/logo.png", "#123456", null, null, "Acme"));

        JsonNode branded = json.readTree(get(client(), base() + "/branding?client_id=" + enc(clientId)).body());
        assertThat(branded.get("companyName").asText()).isEqualTo("Acme");
        assertThat(branded.get("primaryColor").asText()).isEqualTo("#123456");
        assertThat(branded.get("logoUrl").asText()).isEqualTo("https://cdn.example/logo.png");

        // Unknown client → platform defaults; never reveals whether the client exists, never any tenant internals.
        JsonNode unknown = json.readTree(get(client(), base() + "/branding?client_id=does-not-exist").body());
        assertThat(unknown.get("primaryColor").asText()).isEqualTo("#4F46E5");
        assertThat(unknown.get("companyName").isNull()).isTrue();
    }

    // ============================ CONSENT ============================

    @Test
    void consentShowsDescriptionsAutoGrantsRequiresConsentFalseAndPersists() throws Exception {
        UUID tenantId = activeTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        setupResourceServer(ctx); // "read" requires_consent=false, "write" requires_consent=true
        String email = "consent-" + rnd() + "@x.com";
        userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                email, PASSWORD, "F", "L", null, UserStatus.ACTIVE));
        String clientId = nonTrustedClient(ctx);
        String scope = "openid todomaster-api:read todomaster-api:write";

        HttpClient http = client();
        Pkce pkce = pkce();
        String state1 = rnd();
        // authorize → login → resume → consent required. The original authorize params (no session correlation
        // between /authorize and /login — see CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md) are carried explicitly on the
        // login form, mirroring what the BFF relay will forward.
        get(http, authorizeUrl(clientId, scope, state1, pkce)); // 302 → /login, carrying the query string
        HttpResponse<String> login = postForm(http, base() + "/login", loginForm(clientId, scope, state1, pkce, email));
        HttpResponse<String> resumed = get(http, location(login));
        String consentUrl = location(resumed);
        // The consent-redirect fix: target is now the configured, absolute bff.consent-page value (a deliberately
        // foreign, unreachable stand-in for the real BFF), with client_id/scope/state still appended automatically
        // by SAS. Fetch the SAME query directly against the backend's own real endpoint — standing in for the BFF's
        // proxy relay, which this backend-only test has no process for.
        assertThat(consentUrl).startsWith(BFF_CONSENT_PAGE + "?");
        String consentQuery = URI.create(consentUrl).getRawQuery();

        // The consent context: human descriptions + requires_consent flags.
        JsonNode contextBody = json.readTree(get(http, base() + "/oauth2/consent?" + consentQuery).body());
        assertThat(contextBody.get("clientName").asText()).isEqualTo(clientId);
        Map<String, JsonNode> scopes = byScope(contextBody.get("scopes"));
        assertThat(scopes.get("todomaster-api:read").get("description").asText()).isEqualTo("Read your to-do items");
        assertThat(scopes.get("todomaster-api:read").get("requiresConsent").asBoolean()).isFalse();
        assertThat(scopes.get("todomaster-api:write").get("requiresConsent").asBoolean()).isTrue();
        assertThat(scopes.get("openid").get("description").asText()).isEqualTo("Verify your identity"); // platform built-in

        // Approve only openid + write (NOT read); the customizer auto-grants read (requires_consent=false).
        String state = queryParam(consentUrl, "state");
        HttpResponse<String> submit = postFormRaw(http, base() + "/oauth2/authorize",
                "client_id=" + enc(clientId) + "&state=" + enc(state)
                        + "&scope=openid&scope=" + enc("todomaster-api:write"), null);
        assertThat(location(submit)).startsWith(REDIRECT).contains("code=");

        // The first token carries the explicitly-approved scopes; the auto-granted read is persisted to the consent.
        JsonNode claims = tokenClaims(http, clientId, queryParam(location(submit), "code"), pkce.verifier());
        assertThat(claims.get("scope").asText()).contains("openid").contains("todomaster-api:write");

        // No re-prompt: a second authorize for the same scopes skips consent (the saved consent already covers them,
        // INCLUDING the auto-granted requires_consent=false read) → code directly, and the token now carries read
        // WITHOUT the user ever approving it → the auto-grant is effective.
        Pkce pkce2 = pkce();
        HttpResponse<String> second = get(http, authorizeUrl(clientId, scope, pkce2));
        assertThat(location(second)).startsWith(REDIRECT).contains("code=");
        JsonNode claims2 = tokenClaims(http, clientId, queryParam(location(second), "code"), pkce2.verifier());
        assertThat(claims2.get("scope").asText())
                .contains("openid").contains("todomaster-api:write")
                .contains("todomaster-api:read"); // auto-granted (never approved) → the customizer's grant took effect
    }

    @Test
    void consentDenyIssuesAccessDeniedAndNoCode() throws Exception {
        UUID tenantId = activeTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        setupResourceServer(ctx);
        String email = "deny-" + rnd() + "@x.com";
        userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                email, PASSWORD, "F", "L", null, UserStatus.ACTIVE));
        String clientId = nonTrustedClient(ctx);

        HttpClient http = client();
        String scope = "openid todomaster-api:read todomaster-api:write";
        String state1 = rnd();
        Pkce pkce = pkce();
        get(http, authorizeUrl(clientId, scope, state1, pkce));
        HttpResponse<String> login = postForm(http, base() + "/login", loginForm(clientId, scope, state1, pkce, email));
        String consentUrl = location(get(http, location(login)));
        assertThat(consentUrl).startsWith(BFF_CONSENT_PAGE + "?");
        String state = queryParam(consentUrl, "state");

        // Deny = submit NO approved scopes. Even the auto-grantable "read" must NOT be granted.
        HttpResponse<String> deny = postFormRaw(http, base() + "/oauth2/authorize",
                "client_id=" + enc(clientId) + "&state=" + enc(state), null);
        assertThat(location(deny)).startsWith(REDIRECT).contains("error=access_denied");
        assertThat(location(deny)).doesNotContain("code=");
    }

    // ============================ helpers ============================

    private void setupResourceServer(TenantContext ctx) {
        ResourceServerView rs = resourceServerService.createResourceServer(ctx, new CreateResourceServerCommand(
                "todomaster-api", "TodoMaster API", "https://todomaster-" + rnd() + ".rs.example/api"));
        resourceServerService.addScope(ctx, rs.id(),
                new AddScopeCommand("read", "Read your to-do items", true, false));   // auto-grantable
        resourceServerService.addScope(ctx, rs.id(),
                new AddScopeCommand("write", "Modify your to-do items", false, true)); // needs consent
    }

    private HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(HttpClient http, String url, Map<String, String> form) throws Exception {
        return postFormRaw(http, url, formEncode(form), null);
    }

    private HttpResponse<String> postFormRaw(HttpClient http, String url, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode tokenClaims(HttpClient http, String clientId, String code, String verifier) throws Exception {
        HttpResponse<String> token = postFormRaw(http, base() + "/oauth2/token",
                "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT)
                        + "&code_verifier=" + enc(verifier), basic(clientId));
        assertThat(token.statusCode()).isEqualTo(200);
        String accessToken = json.readTree(token.body()).get("access_token").asText();
        return json.readTree(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
    }

    private static String location(HttpResponse<String> r) {
        return r.headers().firstValue("Location").orElse("");
    }

    private String basic(String clientId) {
        return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecrets.get(clientId)).getBytes(StandardCharsets.UTF_8));
    }

    private static String formEncode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
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
        throw new AssertionError("query param " + name + " not in " + url);
    }

    private String authorizeUrl(String clientId, String scope, Pkce pkce) {
        return authorizeUrl(clientId, scope, rnd(), pkce);
    }

    private String authorizeUrl(String clientId, String scope, String state, Pkce pkce) {
        return base() + "/oauth2/authorize?response_type=code&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(REDIRECT) + "&scope=" + enc(scope)
                + "&state=" + enc(state) + "&code_challenge=" + pkce.challenge() + "&code_challenge_method=S256";
    }

    /**
     * The login form for the cross-origin-safe {@code POST /login}: the original authorize params (no session
     * correlation to resume — see CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md) plus credentials.
     */
    private Map<String, String> loginForm(String clientId, String scope, String state, Pkce pkce, String email) {
        Map<String, String> form = new HashMap<>();
        form.put("response_type", "code");
        form.put("client_id", clientId);
        form.put("redirect_uri", REDIRECT);
        form.put("scope", scope);
        form.put("state", state);
        form.put("code_challenge", pkce.challenge());
        form.put("code_challenge_method", "S256");
        form.put("email", email);
        form.put("password", PASSWORD);
        return form;
    }

    private Map<String, JsonNode> byScope(JsonNode scopesArray) {
        Map<String, JsonNode> map = new HashMap<>();
        scopesArray.forEach(s -> map.put(s.get("scope").asText(), s));
        return map;
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    private record Pkce(String verifier, String challenge) {}

    private Pkce pkce() throws Exception {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
    }

    private UUID activeTenant() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("t-" + rnd(), "T"));
        tenantService.activateTenant(tenant.id());
        return tenant.id();
    }

    private String trustedClient(TenantContext ctx) {
        String clientId = "app-" + rnd();
        ClientCreatedView created = clientRegistrationService.registerClient(ctx, new RegisterClientCommand(
                clientId, clientId, false, List.of("authorization_code", "refresh_token"),
                List.of("openid", "profile"), List.of(REDIRECT), true, true));
        clientSecrets.put(clientId, created.clientSecret());
        return clientId;
    }

    private String nonTrustedClient(TenantContext ctx) {
        String clientId = "app-" + rnd();
        ClientCreatedView created = clientRegistrationService.registerClient(ctx, new RegisterClientCommand(
                clientId, clientId, false, List.of("authorization_code", "refresh_token"),
                List.of("openid", "todomaster-api:read", "todomaster-api:write"), List.of(REDIRECT), true, false));
        clientSecrets.put(clientId, created.clientSecret());
        return clientId;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
