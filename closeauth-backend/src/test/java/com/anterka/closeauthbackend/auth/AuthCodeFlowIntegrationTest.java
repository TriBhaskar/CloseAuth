package com.anterka.closeauthbackend.auth;

import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.session.entity.AuthServerSession;
import com.anterka.closeauthbackend.session.repository.AuthServerSessionRepository;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.anterka.closeauthbackend.token.entity.RefreshToken;
import com.anterka.closeauthbackend.token.enums.RefreshTokenStatus;
import com.anterka.closeauthbackend.token.repository.RefreshTokenRepository;
import com.anterka.closeauthbackend.token.service.RefreshTokenHasher;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Stage 6a flagship: the full auth-code + PKCE loop end-to-end against live Postgres + Redis, proving the whole
 * token/session machine runs for a real user and that the 8 deferred seams are closed. Gated on
 * {@code -Dcloseauth.it.db.url=...} (self-skips in plain {@code mvn test}).
 *
 * <p>Driven over real HTTP with a cookie-jar {@link HttpClient} that does NOT auto-follow redirects (so each 302 is
 * asserted): {@code GET /authorize} → {@code /login} → {@code POST /login} → resume {@code /authorize} (SSO) → code →
 * {@code POST /token}. Covers: {@code sub}=user UUID, real {@code idp}, tenant/scope claims, refresh-root recording +
 * rotation lineage, session linkage + ip/ua, introspection round-trip (closes 4b-ii D1), tenant-scoped SSO consult,
 * logout cascade, and cross-tenant authentication refusal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class AuthCodeFlowIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("closeauth.issuer-url", () -> "http://localhost:9099");
        // Plain-HTTP test: don't mark the session cookie Secure (else the java.net cookie jar may drop it).
        registry.add("closeauth.session.cookie.secure", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired TenantService tenantService;
    @Autowired UserService userService;
    @Autowired ClientRegistrationService clientRegistrationService;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired AuthServerSessionRepository sessionRepository;
    @Autowired TokenRevocationService tokenRevocationService;

    private static final String SECRET = "client-secret-value";
    private static final String REDIRECT = "http://127.0.0.1/callback";
    private static final String PASSWORD = "password123";
    private final ObjectMapper json = new ObjectMapper();

    private String base() {
        return "http://localhost:" + port + "/closeauth";
    }

    // ============================ FLAGSHIP ============================

    @Test
    void fullAuthCodeFlowIssuesCorrectUserTokenRecordsRefreshRootRotatesAndIntrospects() throws Exception {
        UUID tenantId = activeTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        UUID userId = user(ctx, "flagship-" + rnd() + "@x.com");
        String clientId = authCodeClient(ctx);

        HttpClient browser = browser();
        Tokens tokens = runAuthCodeFlow(browser, clientId, "openid profile");

        // ---- §12 access-token claims ----
        JsonNode claims = decodeJwtPayload(tokens.accessToken());
        assertThat(claims.get("sub").asText()).isEqualTo(userId.toString());       // SEAM 1: sub = user UUID (not username)
        assertThat(claims.get("idp").asText()).isEqualTo("LOCAL_PASSWORD");          // SEAM 4: real idp from user_identities
        assertThat(claims.get("amr").get(0).asText()).isEqualTo("pwd");              // 6b-i: password login → amr=[pwd]
        assertThat(claims.get("tenant_id").asText()).isEqualTo(tenantId.toString());
        assertThat(claims.get("client_id").asText()).isEqualTo(clientId);
        assertThat(claims.get("scope").asText()).contains("openid");

        // ---- SEAM 8: introspection round-trip on a USER token (closes 4b-ii D1) ----
        JsonNode introspection = introspect(browser, clientId, tokens.accessToken());
        assertThat(introspection.get("active").asBoolean()).isTrue();
        assertThat(introspection.get("sub").asText()).isEqualTo(userId.toString());

        // ---- SEAM 3 & 5: refresh-root recorded, session-linked, ip/ua populated ----
        RefreshToken root = refreshTokenRepository.findByTokenHash(RefreshTokenHasher.sha256Hex(tokens.refreshToken()))
                .orElseThrow(() -> new AssertionError("refresh-token family root was not recorded"));
        assertThat(root.getUserId()).isEqualTo(userId);
        assertThat(root.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(root.getSessionId()).isNotNull();                                 // SEAM 3: linked to the session
        assertThat(root.getIpAddress()).isNotBlank();                                // SEAM 5: request context threaded
        assertThat(root.getUserAgent()).isNotBlank();
        // The linked session belongs to the same user/tenant.
        AuthServerSession session = sessionRepository.findById(root.getSessionId()).orElseThrow();
        assertThat(session.getUserId()).isEqualTo(userId);
        assertThat(session.getTenantId()).isEqualTo(tenantId);

        // ---- rotation lineage: refresh grant rotates, parent USED, child ACTIVE same family, session preserved ----
        Tokens rotated = refresh(browser, clientId, tokens.refreshToken());
        assertThat(status(tokens.refreshToken())).isEqualTo(RefreshTokenStatus.USED);
        RefreshToken child = refreshTokenRepository.findByTokenHash(
                RefreshTokenHasher.sha256Hex(rotated.refreshToken())).orElseThrow();
        assertThat(child.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(child.getFamilyId()).isEqualTo(root.getFamilyId());
        assertThat(child.getParentTokenId()).isEqualTo(root.getId());
        assertThat(child.getSessionId()).isEqualTo(root.getSessionId());             // session linkage inherited
    }

    // ==================== SSO CONSULT (tenant-scoped) ====================

    @Test
    void ssoConsultSkipsLoginForSameTenantAndReAuthsCrossTenant() throws Exception {
        UUID tenantA = activeTenant();
        TenantContext ctxA = TenantContext.of(tenantA);
        user(ctxA, "sso-" + rnd() + "@x.com");
        String clientA1 = authCodeClient(ctxA);
        String clientA2 = authCodeClient(ctxA);

        UUID tenantB = activeTenant();
        String clientB = authCodeClient(TenantContext.of(tenantB));

        HttpClient browser = browser();
        // Establish a tenant-A session via a full login on clientA1.
        runAuthCodeFlow(browser, clientA1, "openid");

        // Same tenant, different client → SSO: login is skipped, a code is issued directly.
        HttpResponse<String> sameTenant = authorize(browser, clientA2, "openid", pkce());
        assertThat(sameTenant.statusCode()).isEqualTo(302);
        assertThat(location(sameTenant)).startsWith(REDIRECT).contains("code=");

        // Different tenant → NOT recognized (tenant-scoped): redirected to login for fresh auth.
        HttpResponse<String> crossTenant = authorize(browser, clientB, "openid", pkce());
        assertThat(crossTenant.statusCode()).isEqualTo(302);
        assertThat(location(crossTenant)).contains("/login");
    }

    // ==================== LOGOUT CASCADE ====================

    @Test
    void logoutRevokesSessionRefreshFamilyAndAccessTokens() throws Exception {
        UUID tenantId = activeTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        UUID userId = user(ctx, "logout-" + rnd() + "@x.com");
        String clientId = authCodeClient(ctx);

        HttpClient browser = browser();
        Tokens tokens = runAuthCodeFlow(browser, clientId, "openid");
        RefreshToken root = refreshTokenRepository.findByTokenHash(
                RefreshTokenHasher.sha256Hex(tokens.refreshToken())).orElseThrow();
        UUID sessionId = root.getSessionId();

        // Logout.
        HttpResponse<String> logout = postForm(browser, base() + "/logout", Map.of(), null);
        assertThat(logout.statusCode()).isEqualTo(204);

        // Cascade: session ledger revoked, refresh family revoked, access-token marker written.
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(status(tokens.refreshToken())).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(tokenRevocationService.isRevoked(tenantId, userId, Instant.now().getEpochSecond())).isTrue();

        // The now-dead session cookie no longer grants SSO: a new /authorize goes to login.
        HttpResponse<String> afterLogout = authorize(browser, clientId, "openid", pkce());
        assertThat(location(afterLogout)).contains("/login");
    }

    // ==================== CROSS-TENANT AUTH REFUSAL ====================

    @Test
    void cannotAuthenticateAgainstAClientOfADifferentTenant() throws Exception {
        UUID tenantA = activeTenant();
        String clientA = authCodeClient(TenantContext.of(tenantA));

        // A user that exists ONLY in tenant B.
        UUID tenantB = activeTenant();
        String tenantBEmail = "crosstenant-" + rnd() + "@x.com";
        user(TenantContext.of(tenantB), tenantBEmail);

        HttpClient browser = browser();
        // Start the flow on tenant A's client, then try to log in with tenant B's user credentials.
        authorize(browser, clientA, "openid", pkce()); // 302 → /login, saves the request (tenant A)
        HttpResponse<String> login = postForm(browser, base() + "/login",
                Map.of("email", tenantBEmail, "password", PASSWORD), null);

        // Refused: the tenant-B user is not in tenant A's pool. Uniform, enumeration-safe 401 (no session cookie).
        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(login.body()).contains("invalid_credentials");
    }

    // ============================ flow helpers ============================

    /** Drives GET /authorize → /login → POST /login → resume /authorize → returns the exchanged tokens. */
    private Tokens runAuthCodeFlow(HttpClient browser, String clientId, String scope) throws Exception {
        Pkce pkce = pkce();

        HttpResponse<String> authorize = authorize(browser, clientId, scope, pkce);
        assertThat(authorize.statusCode()).isEqualTo(302);
        assertThat(location(authorize)).contains("/login"); // unauthenticated → login

        HttpResponse<String> login = postForm(browser, base() + "/login",
                Map.of("email", currentEmail, "password", PASSWORD), null);
        assertThat(login.statusCode()).isEqualTo(302);
        String resume = location(login);
        assertThat(resume).contains("/oauth2/authorize"); // resumes the saved authorization request

        HttpResponse<String> resumed = get(browser, resume);
        assertThat(resumed.statusCode()).isEqualTo(302);
        String redirect = location(resumed);
        assertThat(redirect).startsWith(REDIRECT).contains("code="); // SSO recognized → code issued
        String code = queryParam(redirect, "code");

        HttpResponse<String> token = postForm(browser, base() + "/oauth2/token", Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT,
                "code_verifier", pkce.verifier()), basic(clientId));
        assertThat(token.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(token.body());
        return new Tokens(body.get("access_token").asText(), body.get("refresh_token").asText());
    }

    private HttpResponse<String> authorize(HttpClient browser, String clientId, String scope, Pkce pkce)
            throws Exception {
        String url = base() + "/oauth2/authorize?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(REDIRECT)
                + "&scope=" + enc(scope)
                + "&state=" + rnd()
                + "&code_challenge=" + pkce.challenge()
                + "&code_challenge_method=S256";
        return get(browser, url);
    }

    private Tokens refresh(HttpClient browser, String clientId, String refreshToken) throws Exception {
        HttpResponse<String> token = postForm(browser, base() + "/oauth2/token", Map.of(
                "grant_type", "refresh_token", "refresh_token", refreshToken), basic(clientId));
        assertThat(token.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(token.body());
        return new Tokens(body.get("access_token").asText(), body.get("refresh_token").asText());
    }

    private JsonNode introspect(HttpClient browser, String clientId, String token) throws Exception {
        HttpResponse<String> response = postForm(browser, base() + "/oauth2/introspect",
                Map.of("token", token), basic(clientId));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
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

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    private String basic(String clientId) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
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

    private String currentEmail; // the email of the user provisioned for the current flow

    private UUID activeTenant() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("t-" + rnd(), "T"));
        tenantService.activateTenant(tenant.id());
        return tenant.id();
    }

    private UUID user(TenantContext ctx, String email) {
        this.currentEmail = email;
        return userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                email, PASSWORD, "First", "Last", null, UserStatus.ACTIVE)).id();
    }

    private String authCodeClient(TenantContext ctx) {
        String clientId = "app-" + rnd();
        clientRegistrationService.registerClient(ctx, new RegisterClientCommand(
                clientId, clientId, SECRET,
                List.of("authorization_code", "refresh_token"),
                List.of("openid", "profile"),
                List.of(REDIRECT),
                true, true)); // requireProofKey (PKCE), trusted
        return clientId;
    }

    private RefreshTokenStatus status(String rawRefreshToken) {
        return refreshTokenRepository.findByTokenHash(RefreshTokenHasher.sha256Hex(rawRefreshToken))
                .map(RefreshToken::getStatus).orElseThrow();
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Tokens(String accessToken, String refreshToken) {}
}
