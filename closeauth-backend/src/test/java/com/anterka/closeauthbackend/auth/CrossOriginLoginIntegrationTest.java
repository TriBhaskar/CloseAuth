package com.anterka.closeauthbackend.auth;

import com.anterka.closeauthbackend.client.dto.ClientCreatedView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the cross-origin login continuity fix (CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md §3a) end-to-end against live
 * Postgres + Redis. Gated on {@code -Dcloseauth.it.db.url=...} (self-skips in plain {@code mvn test}), same as
 * {@link AuthCodeFlowIntegrationTest}.
 *
 * <p><b>The honesty requirement this test exists to satisfy:</b> a test client with an implicit cookie jar would
 * silently carry the servlet session cookie between every request in the flow, hiding the exact bug this design
 * fixes (a real cross-origin browser never sends the backend's own session cookie to the BFF's origin, and the
 * BFF's server-to-server relay of {@code POST /login} has nothing to forward for it either). This test therefore
 * uses a plain {@link HttpClient} with NO cookie handler at all — nothing is ever carried automatically. Every
 * cookie the test relies on is captured from a {@code Set-Cookie} response header and re-attached EXPLICITLY on the
 * specific subsequent request that needs it, mirroring exactly what a real cross-origin browser would (and would
 * not) do:
 * <ul>
 *   <li>the cold {@code GET /oauth2/authorize} → {@code POST /login} hop carries NOTHING (this is the actual
 *       cross-origin boundary being proven safe — no real BFF process exists in this backend-only test, so this
 *       hop stands in for "backend origin → BFF origin → backend origin, server-to-server, zero cookies");</li>
 *   <li>the {@code CLOSEAUTH_SESSION} cookie {@code POST /login} sets IS explicitly re-attached on the resumed
 *       {@code GET /oauth2/authorize} — this is the one cookie the design leaves untouched (§3c), a normal
 *       same-origin browser cookie by the time the browser is back on the backend's own origin.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class CrossOriginLoginIntegrationTest {

    // A FIXED port (not RANDOM_PORT): LoginSuccessResponder's cross-origin-safe redirect is built from
    // properties.getIssuerUrl() + the authorization endpoint path — an ABSOLUTE URL, unlike the old
    // SavedRequest.getRedirectUrl() (always implicitly relative to whatever host/port actually received the
    // request). For that reconstructed URL to actually be reachable by this test's HTTP client, issuer-url must be
    // configured to equal this server's real bound address — exactly as it must in a real deployment (issuer-url IS
    // the externally-reachable address; see application-docker.yml). RANDOM_PORT can't satisfy that (the port isn't
    // known until after the dynamic-property phase), so this test pins a fixed, otherwise-unused port instead.
    private static final int PORT = 19098;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("server.port", () -> PORT);
        registry.add("closeauth.issuer-url", () -> "http://localhost:" + PORT + "/closeauth");
        // Plain-HTTP test: don't mark the session cookie Secure (else it wouldn't be sent over plain HTTP anyway).
        registry.add("closeauth.session.cookie.secure", () -> "false");
        // A deliberately distinct, "foreign" origin standing in for the real BFF — proves the entry point actually
        // redirects to THIS configured value (not a same-origin/relative fallback) and never needs to be reachable.
        // BE-B: there is no more closeauth.bff.login-page property — the login redirect is built dynamically per
        // tenant (base-url + /t/{slug}/login) by TenantAwareLoginRedirectEntryPoint.
        registry.add("closeauth.bff.base-url", () -> "http://bff.example.invalid:8088");
    }

    @Autowired TenantService tenantService;
    @Autowired UserService userService;
    @Autowired ClientRegistrationService clientRegistrationService;

    private static final String REDIRECT = "http://127.0.0.1/callback";
    private static final String PASSWORD = "password123";
    // UI-3c: the backend now generates each client's secret; capture per clientId rather than a shared constant.
    private final java.util.Map<String, String> clientSecrets = new java.util.HashMap<>();
    private static final String BFF_BASE_URL = "http://bff.example.invalid:8088";
    private final ObjectMapper json = new ObjectMapper();

    private String base() {
        return "http://localhost:" + PORT + "/closeauth";
    }

    @Test
    void coldUnauthenticatedAuthorizeCarriesQueryToLoginThenResumesAndIssuesRealTokens() throws Exception {
        TenantView provisioned = activeTenant();
        UUID tenantId = provisioned.id();
        String tenantSlug = provisioned.slug();
        TenantContext ctx = TenantContext.of(tenantId);
        String email = "cross-origin-" + rnd() + "@x.com";
        UUID userId = userService.createUserWithPassword(ctx, new CreateUserWithPasswordCommand(
                email, PASSWORD, "First", "Last", null, UserStatus.ACTIVE)).id();
        String clientId = authCodeClient(ctx);

        // A client that NEVER carries cookies automatically — see class javadoc.
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        Pkce pkce = pkce();
        Map<String, String> authorizeParams = new LinkedHashMap<>();
        authorizeParams.put("response_type", "code");
        authorizeParams.put("client_id", clientId);
        authorizeParams.put("redirect_uri", REDIRECT);
        authorizeParams.put("scope", "openid profile");
        authorizeParams.put("state", rnd());
        authorizeParams.put("code_challenge", pkce.challenge());
        authorizeParams.put("code_challenge_method", "S256");
        String originalQuery = encodeQuery(authorizeParams);

        // ---- STEP 1: cold, unauthenticated GET /oauth2/authorize — no cookies sent at all. ----
        HttpResponse<String> authorize = get(client, base() + "/oauth2/authorize?" + originalQuery, null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String loginRedirect = location(authorize);
        // The entry-point fix: redirect target is the tenant-namespaced BFF login page (a genuinely different,
        // unreachable "foreign" origin — proving this is NOT a same-origin/relative "/login" fallback), built from
        // the resolved tenant's slug (BE-B), with the ORIGINAL query string appended verbatim.
        assertThat(loginRedirect).isEqualTo(BFF_BASE_URL + "/t/" + tenantSlug + "/login?" + originalQuery);

        // Note (confirmed by running this test): Spring Security's ExceptionTranslationFilter still transparently
        // creates its OWN servlet session + "SESSION" cookie here — that is the framework's default
        // request-cache-SAVE behavior, kept ACTIVE (not disabled on the filter chain) deliberately, because
        // MagicLinkController's resume path still legitimately depends on reading it back (see LoginSuccessResponder's
        // class javadoc on the two redirect-resolution strategies). It is harmless dead weight for THIS
        // (password-login) path specifically: the fix's whole point is that step 2 below never needs to send it back
        // — and, using a cookie-jar-free client, it deliberately never does, proving the resume mechanism truly does
        // not depend on it, regardless of whether the framework happens to still set it for an unrelated flow's sake.

        // ---- STEP 2: POST /login directly (standing in for the BFF's relay) — carrying the reconstructed query
        // params as form fields alongside credentials, and (deliberately) NO cookies from step 1 (including the
        // framework's own "SESSION" cookie noted above — proving it is not needed for this path to work). ----
        Map<String, String> loginForm = new LinkedHashMap<>(authorizeParams);
        loginForm.put("email", email);
        loginForm.put("password", PASSWORD);
        HttpResponse<String> login = postForm(client, base() + "/login", loginForm, null);
        assertThat(login.statusCode()).isEqualTo(302);
        String resumeUrl = location(login);
        assertThat(resumeUrl).startsWith(base() + "/oauth2/authorize?");
        // Full-fidelity: every original param survives the reconstruction.
        assertThat(queryParam(resumeUrl, "client_id")).isEqualTo(clientId);
        assertThat(queryParam(resumeUrl, "redirect_uri")).isEqualTo(REDIRECT);
        assertThat(queryParam(resumeUrl, "response_type")).isEqualTo("code");
        assertThat(queryParam(resumeUrl, "scope")).isEqualTo("openid profile");
        assertThat(queryParam(resumeUrl, "state")).isEqualTo(authorizeParams.get("state"));
        assertThat(queryParam(resumeUrl, "code_challenge")).isEqualTo(pkce.challenge());
        assertThat(queryParam(resumeUrl, "code_challenge_method")).isEqualTo("S256");

        String sessionCookie = extractCookie(login, "CLOSEAUTH_SESSION");
        assertThat(sessionCookie).as("POST /login must set the CLOSEAUTH_SESSION cookie").isNotNull();

        // ---- STEP 3: follow the resume redirect — explicitly (and ONLY) attaching the session cookie captured
        // above, exactly as a real same-origin browser navigation back to the backend would. ----
        HttpResponse<String> resumed = get(client, resumeUrl, "CLOSEAUTH_SESSION=" + sessionCookie);
        assertThat(resumed.statusCode()).isEqualTo(302);
        String redirectToClient = location(resumed);
        assertThat(redirectToClient).startsWith(REDIRECT).contains("code=");
        String code = queryParam(redirectToClient, "code");
        assertThat(code).isNotBlank();

        // ---- STEP 4: exchange the REAL authorization code for REAL tokens. ----
        HttpResponse<String> token = postForm(client, base() + "/oauth2/token", Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT,
                "code_verifier", pkce.verifier()), basic(clientId));
        assertThat(token.statusCode()).isEqualTo(200);
        JsonNode tokenBody = json.readTree(token.body());
        String accessToken = tokenBody.get("access_token").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(tokenBody.get("refresh_token").asText()).isNotBlank();

        JsonNode claims = decodeJwtPayload(accessToken);
        assertThat(claims.get("sub").asText()).isEqualTo(userId.toString());
        assertThat(claims.get("tenant_id").asText()).isEqualTo(tenantId.toString());
        assertThat(claims.get("client_id").asText()).isEqualTo(clientId);
        assertThat(claims.get("amr").get(0).asText()).isEqualTo("pwd");
    }

    // ============================ HTTP plumbing (deliberately cookie-jar-free) ============================

    private HttpResponse<String> get(HttpClient client, String url, String cookieHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(HttpClient client, String url, Map<String, String> form, String authHeader)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeQuery(form)));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        // Deliberately: no Cookie header here — this call always stands in for a hop that carries no cookies.
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    /** Parses the (single) Set-Cookie header for the named cookie's value, or null if absent. */
    private static String extractCookie(HttpResponse<String> response, String name) {
        for (String setCookie : response.headers().allValues("Set-Cookie")) {
            String prefix = name + "=";
            if (setCookie.startsWith(prefix)) {
                String rest = setCookie.substring(prefix.length());
                int semi = rest.indexOf(';');
                return semi >= 0 ? rest.substring(0, semi) : rest;
            }
        }
        return null;
    }

    private String basic(String clientId) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecrets.get(clientId)).getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeQuery(Map<String, String> form) {
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
        String rawQuery = URI.create(url).getRawQuery();
        if (rawQuery == null) {
            throw new AssertionError("no query string in " + url);
        }
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
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

    private TenantView activeTenant() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("T"));
        tenantService.activateTenant(tenant.id());
        return tenant;
    }

    private String authCodeClient(TenantContext ctx) {
        String clientId = "app-" + rnd();
        ClientCreatedView created = clientRegistrationService.registerClient(ctx, new RegisterClientCommand(
                clientId, clientId, false,
                List.of("authorization_code", "refresh_token"),
                List.of("openid", "profile"),
                List.of(REDIRECT),
                null,
                true, true)); // requireProofKey (PKCE), trusted
        clientSecrets.put(clientId, created.clientSecret());
        return clientId;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
