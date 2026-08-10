package com.anterka.closeauthbackend.auth;

import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.service.OneTimeTokenService;
import com.anterka.closeauthbackend.client.dto.ClientCreatedView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import com.anterka.closeauthbackend.session.dto.CreateSessionCommand;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.repository.AuthServerSessionRepository;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 6b-i end-to-end flows against live Postgres + Redis: registration (email-verified) → verify → activation;
 * password reset → new password + full revocation cascade; and magic-link → an issued token carrying {@code
 * amr=[magic_link]} (the OIDC method reference, closing 6a's D3 seam). A capturing {@link AuthNotificationSender}
 * substitutes for real email so the tests can read the delivered code/link. Gated on {@code -Dcloseauth.it.db.url}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class IdentityFlowsIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("closeauth.issuer-url", () -> "http://localhost:9098");
        registry.add("closeauth.session.cookie.secure", () -> "false");
    }

    /** Captures the "delivered" secrets in-memory so the tests can consume them (stands in for real email). */
    static class CapturingNotificationSender implements AuthNotificationSender {
        final Map<String, String> codes = new ConcurrentHashMap<>();
        final Map<String, String> magicLinks = new ConcurrentHashMap<>();
        final Map<String, String> resetLinks = new ConcurrentHashMap<>();

        @Override public void sendEmailVerificationCode(String target, String code) { codes.put(target, code); }
        @Override public void sendMagicLink(String target, String url) { magicLinks.put(target, url); }
        @Override public void sendPasswordResetLink(String target, String url) { resetLinks.put(target, url); }
        @Override public void sendInviteLink(String target, String url) { }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary CapturingNotificationSender capturingNotificationSender() {
            return new CapturingNotificationSender();
        }
    }

    @LocalServerPort int port;
    @Autowired TenantService tenantService;
    @Autowired UserService userService;
    @Autowired ClientRegistrationService clientRegistrationService;
    @Autowired AuthServerSessionService sessionService;
    @Autowired AuthServerSessionRepository sessionRepository;
    @Autowired TokenRevocationService tokenRevocationService;
    @Autowired OneTimeTokenService oneTimeTokenService;
    @Autowired CapturingNotificationSender mail;

    private static final String REDIRECT = "http://127.0.0.1/callback";
    private final ObjectMapper json = new ObjectMapper();
    // UI-3c: the backend now generates each client's secret; capture per clientId rather than a shared constant.
    private final java.util.Map<String, String> clientSecrets = new java.util.HashMap<>();

    private String base() {
        return "http://localhost:" + port + "/closeauth";
    }

    // ============ registration (email-verified) → verify → activation ============

    @Test
    void registrationEmailVerifiedThenVerifyActivatesTheUser() throws Exception {
        UUID tenantId = activeTenant(); // provisioning seeds registration mode = EMAIL_VERIFIED
        TenantContext ctx = TenantContext.of(tenantId);
        String clientId = authCodeClient(ctx);
        String email = "reg-" + rnd() + "@x.com";

        HttpClient http = client();
        HttpResponse<String> register = postForm(http, base() + "/register",
                Map.of("email", email, "password", "password123", "client_id", clientId));
        assertThat(register.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(register.body());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("mode").asText()).isEqualTo("EMAIL_VERIFIED");
        assertThat(body.get("emailVerificationSent").asBoolean()).isTrue();

        String code = mail.codes.get(email);
        assertThat(code).isNotNull().hasSize(6);

        HttpResponse<String> verify = postForm(http, base() + "/verify-email/confirm",
                Map.of("email", email, "code", code, "client_id", clientId));
        assertThat(verify.statusCode()).isEqualTo(200);

        // The user is now ACTIVE + email-verified.
        UserView user = userService.getUserByEmail(ctx, email);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.emailVerified()).isTrue();

        // Single-use: the same code cannot be verified again.
        HttpResponse<String> replay = postForm(http, base() + "/verify-email/confirm",
                Map.of("email", email, "code", code, "client_id", clientId));
        assertThat(replay.statusCode()).isEqualTo(400);
    }

    // ============ password reset → new password + revocation cascade ============

    @Test
    void passwordResetSetsNewPasswordAndRevokesExistingSessions() throws Exception {
        UUID tenantId = activeTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        String clientId = authCodeClient(ctx);
        String email = "reset-" + rnd() + "@x.com";
        UUID userId = userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                email, "old-password-1", "F", "L", null, UserStatus.ACTIVE)).id();
        // An existing live session that the reset must kill.
        SessionView session = sessionService.createSession(new CreateSessionCommand(
                userId, tenantId, "10.0.0.1", "JUnit", false, "pwd"));
        // A token "issued" before the reset — the access-token marker must cover it (iat <= revocationTime).
        long iatBeforeReset = Instant.now().getEpochSecond();

        HttpClient http = client();
        // Enumeration-safe: a request for a NON-existent email still returns 200.
        assertThat(postForm(http, base() + "/password-reset/request",
                Map.of("email", "ghost-" + rnd() + "@x.com", "client_id", clientId)).statusCode()).isEqualTo(200);

        assertThat(postForm(http, base() + "/password-reset/request",
                Map.of("email", email, "client_id", clientId)).statusCode()).isEqualTo(200);
        String token = queryParam(mail.resetLinks.get(email), "token");

        HttpResponse<String> confirm = postForm(http, base() + "/password-reset/confirm",
                Map.of("token", token, "password", "new-password-2", "client_id", clientId));
        assertThat(confirm.statusCode()).isEqualTo(200);

        // New password works; old one does not.
        assertThat(userService.verifyPassword(ctx, email, "new-password-2").success()).isTrue();
        assertThat(userService.verifyPassword(ctx, email, "old-password-1").success()).isFalse();
        // Post-reset cascade: the existing session is revoked and an access-token marker was written.
        assertThat(sessionRepository.findBySessionKey(session.sessionKey()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(tokenRevocationService.isRevoked(tenantId, userId, iatBeforeReset)).isTrue();
    }

    // ============ magic-link → issued token carries amr=[magic_link] ============

    @Test
    void magicLinkLoginIssuesTokenWithMagicLinkAmr() throws Exception {
        UUID tenantId = activeTenant();
        TenantContext ctx = TenantContext.of(tenantId);
        String clientId = authCodeClient(ctx);
        String email = "magic-" + rnd() + "@x.com";
        UUID userId = userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                email, "password123", "F", "L", null, UserStatus.ACTIVE)).id();

        HttpClient http = client();
        // Request the link.
        assertThat(postForm(http, base() + "/magic-link/request",
                Map.of("email", email, "client_id", clientId)).statusCode()).isEqualTo(200);
        String magicToken = queryParam(mail.magicLinks.get(email), "token");

        // Begin the OAuth flow (saves the authorize request in the servlet session), then click the magic link.
        Pkce pkce = pkce();
        HttpResponse<String> authorize = get(http, authorizeUrl(clientId, pkce));
        assertThat(authorize.statusCode()).isEqualTo(302);
        assertThat(location(authorize)).contains("/login");

        HttpResponse<String> consume = get(http,
                base() + "/magic-link/consume?token=" + enc(magicToken) + "&client_id=" + enc(clientId));
        assertThat(consume.statusCode()).isEqualTo(302); // session established → resume the saved /authorize
        String resume = location(consume);
        assertThat(resume).contains("/oauth2/authorize");

        HttpResponse<String> resumed = get(http, resume);
        assertThat(resumed.statusCode()).isEqualTo(302);
        String code = queryParam(location(resumed), "code");

        HttpResponse<String> token = postFormAuth(http, base() + "/oauth2/token", Map.of(
                "grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "code_verifier", pkce.verifier()), basic(clientId));
        assertThat(token.statusCode()).isEqualTo(200);

        JsonNode claims = decodeJwtPayload(json.readTree(token.body()).get("access_token").asText());
        assertThat(claims.get("sub").asText()).isEqualTo(userId.toString());
        assertThat(claims.get("idp").asText()).isEqualTo("LOCAL_PASSWORD");   // identity is still LOCAL
        assertThat(claims.get("amr").get(0).asText()).isEqualTo("magic_link"); // method THIS login (closes 6a D3)
    }

    // ---- HTTP plumbing -----------------------------------------------------

    private HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(HttpClient http, String url, Map<String, String> form) throws Exception {
        return postFormAuth(http, url, form, null);
    }

    private HttpResponse<String> postFormAuth(HttpClient http, String url, Map<String, String> form, String auth)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String authorizeUrl(String clientId, Pkce pkce) {
        return base() + "/oauth2/authorize?response_type=code&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(REDIRECT) + "&scope=" + enc("openid profile")
                + "&state=" + rnd() + "&code_challenge=" + pkce.challenge() + "&code_challenge_method=S256";
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
        throw new AssertionError("query param " + name + " not found in " + url);
    }

    private JsonNode decodeJwtPayload(String jwt) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private record Pkce(String verifier, String challenge) {}

    private Pkce pkce() throws Exception {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
    }

    // ---- provisioning ------------------------------------------------------

    private UUID activeTenant() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("t-" + rnd(), "T"));
        tenantService.activateTenant(tenant.id());
        return tenant.id();
    }

    private String authCodeClient(TenantContext ctx) {
        String clientId = "app-" + rnd();
        ClientCreatedView created = clientRegistrationService.registerClient(ctx, new RegisterClientCommand(
                clientId, clientId, false, List.of("authorization_code", "refresh_token"),
                List.of("openid", "profile"), List.of(REDIRECT), true, true));
        clientSecrets.put(clientId, created.clientSecret());
        return clientId;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
