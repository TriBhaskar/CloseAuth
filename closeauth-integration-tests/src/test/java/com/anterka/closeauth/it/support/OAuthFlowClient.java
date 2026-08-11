package com.anterka.closeauth.it.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the REAL OAuth2 Authorization Code + PKCE flow against the running app container, exactly as a browser-based
 * client would — the highest-reuse piece of the integration module. Every flow-driven journey (SSO, consent, token
 * lifecycle, RBAC-gated actions) uses this to get from "no session" to "have valid tokens".
 *
 * <h2>What it does</h2>
 * <ul>
 *   <li><b>PKCE</b> (S256) per RFC 7636 — the fixtures register clients with {@code requireProofKey=true}, so every
 *       {@code /authorize} carries a {@code code_challenge} and every token exchange the matching {@code code_verifier}.</li>
 *   <li><b>A cookie jar carried across the flow</b>, like a browser: the servlet {@code SESSION} cookie (holds SAS's
 *       saved {@code /authorize} request) and the {@code CLOSEAUTH_SESSION} Auth Server session cookie. A jar snapshot
 *       is captured in {@link SessionState} so a later scenario can present <b>that exact session state</b> to a
 *       different client (e.g. the cross-tenant SSO-refusal check) without re-logging-in.</li>
 *   <li><b>Manual redirect inspection</b> — redirects are never auto-followed; callers observe the {@code Location} to
 *       distinguish "SSO recognized → 302 to the client callback with a code" from "not recognized → 302 to
 *       {@code /login}". Auto-following would hide exactly that signal.</li>
 * </ul>
 *
 * <h2>Cookie-state swapping</h2>
 * {@link SessionState} is an immutable snapshot of the cookie jar. {@link #login} returns the state at the end of a
 * successful login; {@link #authorizeOnly}, {@link #logout}, etc. take a {@code SessionState} and read from it without
 * mutating it — so the same post-login state can be replayed against multiple clients / after logout.
 *
 * <p>Confidential clients (a secret) are used so a refresh token is issued (SAS does not issue refresh tokens to public
 * clients); the token exchange and introspection therefore authenticate with HTTP Basic.
 */
public final class OAuthFlowClient {

    /** Registered on the fixture clients; the flow must use the same value at {@code /authorize} and {@code /token}. */
    public static final String REDIRECT_URI = "http://localhost:12345/callback";
    private static final String SCOPE = "openid";

    private final String appBaseUri;
    private final String contextPath;

    public OAuthFlowClient(String appBaseUri, String contextPath) {
        this.appBaseUri = appBaseUri;
        this.contextPath = contextPath;
    }

    // ---- PKCE --------------------------------------------------------------

    public static PkcePair pkce() {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String verifier = base64Url(verifierBytes);
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        return new PkcePair(verifier, challenge);
    }

    // ---- full happy path (logs in from scratch) ---------------------------

    /**
     * Drives the whole flow for a fresh login: {@code GET /oauth2/authorize} → {@code /login} → resume {@code /authorize}
     * (SSO now recognizes the just-established session) → capture the code → {@code POST /oauth2/token}. Returns the
     * tokens AND the cookie state, so a caller can reuse the session or inspect it.
     */
    public LoginResult login(String clientId, String clientSecret, String email, String password) {
        Map<String, String> jar = new HashMap<>();
        PkcePair pkce = pkce();
        String state = "st-" + UUID.randomUUID();

        // 1. Unauthenticated /authorize → 302 to /login (SAS saves the request; sets the servlet SESSION cookie).
        Response init = authorize(clientId, SCOPE, pkce.challenge(), state, jar);
        merge(jar, init);
        expect(init.statusCode() == 302 && containsLogin(location(init)),
                "step 1: unauthenticated /authorize should 302 to /login, got " + describe(init));

        // 2. POST /login → 302 back to the saved /authorize URL (sets the CLOSEAUTH_SESSION cookie).
        Response login = request(jar)
                .contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("password", password)
                .formParam("client_id", clientId)
                .post("/login");
        merge(jar, login);
        String resume = location(login);
        expect(login.statusCode() == 302 && resume != null && resume.contains("/oauth2/authorize"),
                "step 2: /login should 302 back to the saved /authorize request, got " + describe(login)
                        + " Location=" + resume);

        // 3. Follow the resume URL (now carrying the session cookie) → 302 to the client callback with a code.
        //    urlEncodingEnabled(false): the resume URL is SAS's saved /authorize URL whose query is ALREADY encoded;
        //    letting REST Assured re-encode it would double-encode redirect_uri and SAS would reject it (400 /error).
        Response authorized = request(jar).urlEncodingEnabled(false).accept("text/html").get(resume);
        merge(jar, authorized);
        String callback = location(authorized);
        expect(authorized.statusCode() == 302 && callback != null && callback.startsWith(REDIRECT_URI),
                "step 3: resumed /authorize should 302 to the client callback with a code, got " + describe(authorized)
                        + " Location=" + callback);
        String code = queryParam(callback, "code");
        expect(code != null, "step 3: no authorization code in the callback: " + callback);

        // 4. Exchange the code for tokens.
        TokenResponse tokens = exchange(clientId, clientSecret, code, pkce.verifier());
        return new LoginResult(tokens, new SessionState(Map.copyOf(jar)));
    }

    // ---- recognition + consent-aware /authorize ---------------------------

    /**
     * Hits {@code /oauth2/authorize} for {@code clientId} carrying the given session, requesting {@code scope}, WITHOUT
     * logging in, and classifies the response into one of three outcomes:
     * <ul>
     *   <li>{@link Outcome#CODE_ISSUED} — SSO recognized and no consent needed (trusted client, or already-consented):
     *       302 straight to the callback with a code (exchangeable with the returned {@code codeVerifier}).</li>
     *   <li>{@link Outcome#CONSENT_REQUIRED} — recognized but this (non-trusted) client needs an explicit decision:
     *       302 to {@code /oauth2/consent}. The returned {@code scope}/{@code state} are the values SAS placed on that
     *       redirect — feed them to {@link #fetchConsentContext}/{@link #submitConsent}.</li>
     *   <li>{@link Outcome#LOGIN_REQUIRED} — session not recognized: 302 to {@code /login}.</li>
     * </ul>
     * Does not mutate the passed {@link SessionState}; the returned {@link AuthorizeOutcome#session()} carries the
     * (possibly updated) cookie jar to thread into the consent GET/POST.
     */
    public AuthorizeOutcome authorize(String clientId, SessionState session, String scope) {
        Map<String, String> jar = new HashMap<>(session.cookies());
        PkcePair pkce = pkce();
        String state = "st-" + UUID.randomUUID();
        Response resp = authorize(clientId, scope, pkce.challenge(), state, jar);
        merge(jar, resp);
        SessionState updated = new SessionState(Map.copyOf(jar));
        String location = location(resp);

        Outcome outcome;
        String code = null;
        String consentScope = scope;
        String consentState = state;
        if (resp.statusCode() == 302 && location != null && location.startsWith(REDIRECT_URI)) {
            outcome = Outcome.CODE_ISSUED;
            code = queryParam(location, "code");
        } else if (resp.statusCode() == 302 && location != null && location.contains("/oauth2/consent")) {
            outcome = Outcome.CONSENT_REQUIRED;
            consentScope = queryParam(location, "scope"); // the scopes SAS wants a decision on
            consentState = queryParam(location, "state");  // SAS's state for the saved authorization request
        } else if (resp.statusCode() == 302 && containsLogin(location)) {
            outcome = Outcome.LOGIN_REQUIRED;
        } else {
            throw new IllegalStateException("OAuth flow: unexpected /authorize outcome: " + describe(resp)
                    + " Location=" + location);
        }
        return new AuthorizeOutcome(outcome, code, pkce.verifier(), location, consentScope, consentState, updated);
    }

    /**
     * Recognition-only for the default {@code openid} scope (backward-compatible: SSO recognized → {@code CODE_ISSUED},
     * otherwise {@code LOGIN_REQUIRED}). Trusted-client callers use {@link AuthorizeOutcome#ssoRecognized()} as before.
     */
    public AuthorizeOutcome authorizeOnly(String clientId, SessionState session) {
        return authorize(clientId, session, SCOPE);
    }

    // ---- consent (non-trusted clients) ------------------------------------

    /**
     * Fetches the consent-page context ({@code GET /oauth2/consent?client_id=&scope=&state=}) carrying the session
     * cookie and parses the JSON the hosted UI would render. Pass the {@code scope}/{@code state} from a
     * {@link Outcome#CONSENT_REQUIRED} {@link #authorize} outcome.
     */
    public ConsentContext fetchConsentContext(String clientId, SessionState session, String scope, String state) {
        Response resp = request(new HashMap<>(session.cookies()))
                .accept(ContentType.JSON)
                .queryParam("client_id", clientId)
                .queryParam("scope", scope)
                .queryParam("state", state)
                .get("/oauth2/consent");
        expect(resp.statusCode() == 200,
                "consent context fetch failed: " + describe(resp) + " body=" + resp.asString());
        JsonPath json = resp.jsonPath();
        List<Map<String, Object>> rawScopes = json.getList("scopes");
        List<ConsentScope> scopes = new ArrayList<>();
        if (rawScopes != null) {
            for (Map<String, Object> entry : rawScopes) {
                scopes.add(new ConsentScope((String) entry.get("scope"), (String) entry.get("description"),
                        Boolean.TRUE.equals(entry.get("requiresConsent"))));
            }
        }
        List<String> alreadyGranted = json.getList("alreadyGranted");
        return new ConsentContext(json.getString("clientId"), json.getString("clientName"), json.getString("state"),
                scopes, alreadyGranted == null ? List.of() : alreadyGranted);
    }

    /**
     * Submits a consent decision: {@code POST /oauth2/authorize} with the session cookie, {@code client_id},
     * {@code state}, and one {@code scope} form parameter per approved scope. Submitting <b>zero</b> scopes is a denial
     * (SAS returns {@code access_denied}). Returns {@link Outcome#CODE_ISSUED} (with a code — exchange it using the
     * {@code codeVerifier} from the <em>triggering</em> {@link #authorize} outcome, where the PKCE challenge was bound)
     * or {@link Outcome#ACCESS_DENIED}.
     */
    public AuthorizeOutcome submitConsent(String clientId, SessionState session, String state, List<String> approvedScopes) {
        RequestSpecification spec = request(new HashMap<>(session.cookies()))
                .contentType(ContentType.URLENC)
                .formParam("client_id", clientId)
                .formParam("state", state);
        for (String approved : approvedScopes) {
            spec.formParam("scope", approved);
        }
        Response resp = spec.post("/oauth2/authorize");
        String location = location(resp);
        expect(resp.statusCode() == 302 && location != null,
                "consent submit should 302 to the client callback (code, or an error such as access_denied), got "
                        + describe(resp) + " Location=" + location);
        String code = queryParam(location, "code");
        Outcome outcome = code != null ? Outcome.CODE_ISSUED : Outcome.ACCESS_DENIED;
        return new AuthorizeOutcome(outcome, code, null, location, null, state,
                new SessionState(Map.copyOf(session.cookies())));
    }

    // ---- token endpoint ----------------------------------------------------

    /** Exchanges an authorization code for tokens (confidential client → HTTP Basic; public client → client_id in body). */
    public TokenResponse exchange(String clientId, String clientSecret, String code, String codeVerifier) {
        RequestSpecification spec = base()
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "authorization_code")
                .formParam("code", code)
                .formParam("redirect_uri", REDIRECT_URI)
                .formParam("code_verifier", codeVerifier);
        if (clientSecret != null) {
            spec.auth().preemptive().basic(clientId, clientSecret);
        } else {
            spec.formParam("client_id", clientId);
        }
        Response resp = spec.post("/oauth2/token");
        expect(resp.statusCode() == 200, "token exchange failed: " + describe(resp) + " body=" + resp.asString());
        JsonPath json = resp.jsonPath();
        return new TokenResponse(
                json.getString("access_token"),
                json.getString("refresh_token"),
                json.getString("token_type"),
                json.getString("scope"));
    }

    /**
     * Exchanges a refresh token for new tokens ({@code grant_type=refresh_token}, confidential client HTTP Basic).
     * Returns the raw response so the caller can assert success (200) or rejection — used to probe whether a
     * pre-existing refresh token still works after a lifecycle change (e.g. tenant suspension).
     */
    public Response refresh(String clientId, String clientSecret, String refreshToken) {
        return base()
                .contentType(ContentType.URLENC)
                .auth().preemptive().basic(clientId, clientSecret)
                .formParam("grant_type", "refresh_token")
                .formParam("refresh_token", refreshToken)
                .post("/oauth2/token");
    }

    /**
     * RFC 7009 token revocation ({@code POST /oauth2/revoke}, confidential client HTTP Basic). Returns the raw response
     * so a caller can assert the RFC 7009 quirk that revocation returns success (200) even for an already-invalid or
     * unknown token (no enumeration oracle). {@code tokenTypeHint} e.g. {@code refresh_token}/{@code access_token}.
     */
    public Response revoke(String clientId, String clientSecret, String token, String tokenTypeHint) {
        return base()
                .contentType(ContentType.URLENC)
                .auth().preemptive().basic(clientId, clientSecret)
                .formParam("token", token)
                .formParam("token_type_hint", tokenTypeHint)
                .post("/oauth2/revoke");
    }

    /** RFC 7662 introspection (confidential client, HTTP Basic). Returns {@code active}. */
    public boolean isActive(String clientId, String clientSecret, String token) {
        Response resp = base()
                .contentType(ContentType.URLENC)
                .auth().preemptive().basic(clientId, clientSecret)
                .formParam("token", token)
                .post("/oauth2/introspect");
        expect(resp.statusCode() == 200, "introspection failed: " + describe(resp) + " body=" + resp.asString());
        return Boolean.TRUE.equals(resp.jsonPath().getBoolean("active"));
    }

    // ---- logout ------------------------------------------------------------

    /** RP-initiated logout carrying the given session cookies (runs the backend's four-leg revoke cascade). */
    public void logout(String clientId, SessionState session) {
        Response resp = request(new HashMap<>(session.cookies()))
                .contentType(ContentType.URLENC)
                .formParam("client_id", clientId)
                .post("/logout");
        // 204 when no post_logout_redirect_uri is supplied (our case); 302 if a registered one were given.
        expect(resp.statusCode() == 204 || resp.statusCode() == 302, "logout unexpected status: " + describe(resp));
    }

    // ---- self-registration -------------------------------------------------

    /** Self-registers ({@code POST /register}, no invite). See {@link #register(String, String, String, String)}. */
    public Response register(String email, String password, String clientId) {
        return register(email, password, clientId, null);
    }

    /**
     * Drives self-registration ({@code POST /register}, form-encoded) exactly as the UI would — the tenant is resolved
     * from {@code client_id}, and the tenant's registration mode selects the strategy. Returns the raw response so the
     * caller asserts the mode-specific outcome (200 {@code {userId,status,mode,emailVerificationSent}}; 409 duplicate
     * email; 403 invite-only refusal). {@code inviteToken} is sent only when non-null (INVITE_ONLY mode).
     */
    public Response register(String email, String password, String clientId, String inviteToken) {
        RequestSpecification spec = base().contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("password", password)
                .formParam("client_id", clientId);
        if (inviteToken != null) {
            spec.formParam("invite_token", inviteToken);
        }
        return spec.post("/register");
    }

    // ---- magic-link + password-reset (one-time-token email flows) ---------

    /** Requests a magic-link email. Enumeration-safe: 200 regardless of whether the email maps to a user. */
    public Response requestMagicLink(String email, String clientId) {
        return base().contentType(ContentType.URLENC)
                .formParam("email", email).formParam("client_id", clientId)
                .post("/magic-link/request");
    }

    /**
     * Consumes a magic link ({@code GET /magic-link/consume?token&client_id}) — establishes the Auth Server session
     * the same way a password login does (a {@code CLOSEAUTH_SESSION} cookie + a 302 resuming the flow). Returns the
     * resulting cookie state; on success {@link SessionState#sessionKey()} is non-blank and the session is usable for
     * SSO exactly like a password-derived one. (The token is extracted from the email body — see {@code Emails}.)
     */
    public SessionState consumeMagicLink(String token, String clientId) {
        Map<String, String> jar = new HashMap<>();
        Response resp = base().cookies(jar)
                .queryParam("token", token).queryParam("client_id", clientId)
                .get("/magic-link/consume");
        merge(jar, resp);
        expect(resp.statusCode() == 302,
                "magic-link consume should 302 (session established, or to /login on failure), got " + describe(resp));
        return new SessionState(Map.copyOf(jar));
    }

    /** Requests a password-reset email. Enumeration-safe: 200 regardless of whether the email maps to a user. */
    public Response requestPasswordReset(String email, String clientId) {
        return base().contentType(ContentType.URLENC)
                .formParam("email", email).formParam("client_id", clientId)
                .post("/password-reset/request");
    }

    /** Confirms a password reset with the emailed token + a new password. 200 on success; 400 (generic) otherwise. */
    public Response confirmPasswordReset(String token, String newPassword, String clientId) {
        return base().contentType(ContentType.URLENC)
                .formParam("token", token).formParam("password", newPassword).formParam("client_id", clientId)
                .post("/password-reset/confirm");
    }

    /** Attempts a password login and returns the raw {@code POST /login} response (302 on success, 401 on bad creds). */
    public Response attemptPasswordLogin(String clientId, String email, String password) {
        return base().contentType(ContentType.URLENC)
                .formParam("email", email).formParam("password", password).formParam("client_id", clientId)
                .post("/login");
    }

    // ---- forced password rotation (Phase 2 of the tenant-onboarding design) -----------------------------

    /**
     * Confirms a forced password rotation ({@code POST /password-rotation/confirm}) with the token carried on the
     * {@code /login} rotation redirect, a new password, and (when resuming an interrupted login) the
     * {@code authorize_query} carried alongside it. On success: <b>302</b> with a fresh {@code CLOSEAUTH_SESSION}
     * {@code Set-Cookie} and a {@code Location} resuming the original {@code /oauth2/authorize} request (or the BFF
     * default if {@code authorizeQuery} was {@code null} — the emailed-link on-ramp). <b>400</b> generic on an
     * invalid/expired/used/stale token — never says which (no enumeration). Redirects are never auto-followed —
     * callers that need to complete the flow follow the returned {@code Location} carrying this response's cookies,
     * exactly as {@link #login} does for an ordinary password login.
     */
    public Response confirmPasswordRotation(String token, String newPassword, String clientId, String authorizeQuery) {
        RequestSpecification spec = base().contentType(ContentType.URLENC)
                .formParam("token", token).formParam("password", newPassword).formParam("client_id", clientId);
        if (authorizeQuery != null) {
            spec.formParam("authorize_query", authorizeQuery);
        }
        return spec.post("/password-rotation/confirm");
    }

    // ---- internals ---------------------------------------------------------

    private Response authorize(String clientId, String scope, String codeChallenge, String state, Map<String, String> jar) {
        return request(jar)
                .accept("text/html") // so an unrecognized session 302s to /login (not a 401)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", scope)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", state)
                .get("/oauth2/authorize");
    }

    private RequestSpecification base() {
        return RestAssured.given().baseUri(appBaseUri).basePath(contextPath).redirects().follow(false);
    }

    private RequestSpecification request(Map<String, String> jar) {
        return base().cookies(jar);
    }

    private static void merge(Map<String, String> jar, Response response) {
        jar.putAll(response.getCookies());
    }

    private static String location(Response response) {
        return response.getHeader("Location");
    }

    private static boolean containsLogin(String location) {
        return location != null && location.contains("/login");
    }

    private static String queryParam(String url, String name) {
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(name)) {
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String describe(Response response) {
        return "HTTP " + response.statusCode();
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("OAuth flow: " + message);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- value types -------------------------------------------------------

    /** A PKCE verifier + its S256 challenge. */
    public record PkcePair(String verifier, String challenge) {
    }

    /** Parsed token-endpoint response. {@code refreshToken} is null if none was issued (e.g. a public client). */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType, String scope) {
    }

    /** An immutable snapshot of the browser cookie jar, replayable against later requests. */
    public record SessionState(Map<String, String> cookies) {
        public SessionState {
            cookies = Map.copyOf(cookies);
        }

        /** The Auth Server session key (the {@code CLOSEAUTH_SESSION} cookie value) — also the DB {@code session_key}. */
        public String sessionKey() {
            return cookies.get("CLOSEAUTH_SESSION");
        }
    }

    /** Result of a happy-path login: the tokens plus the session state established. */
    public record LoginResult(TokenResponse tokens, SessionState session) {
    }

    /** How an {@code /authorize} hit or a consent submission resolved. */
    public enum Outcome { CODE_ISSUED, LOGIN_REQUIRED, CONSENT_REQUIRED, ACCESS_DENIED }

    /**
     * Result of an {@code /authorize} hit or a consent submission. {@code ssoRecognized()} is kept for backward
     * compatibility (true iff a code was issued). For consent flows {@code scope}/{@code state} carry SAS's
     * consent-redirect values and {@code session} the cookie jar to thread into the follow-up consent GET/POST.
     */
    public record AuthorizeOutcome(Outcome outcome, String code, String codeVerifier, String location,
                                   String scope, String state, SessionState session) {

        public boolean ssoRecognized() {
            return outcome == Outcome.CODE_ISSUED;
        }

        public boolean consentRequired() {
            return outcome == Outcome.CONSENT_REQUIRED;
        }

        public boolean loginRequired() {
            return outcome == Outcome.LOGIN_REQUIRED;
        }

        public boolean accessDenied() {
            return outcome == Outcome.ACCESS_DENIED;
        }
    }

    /** The consent-page context ({@code GET /oauth2/consent}) as the hosted UI would receive it. */
    public record ConsentContext(String clientId, String clientName, String state, List<ConsentScope> scopes,
                                 List<String> alreadyGranted) {
        /** The requested-scope entry for {@code scope}, or null if not present. */
        public ConsentScope scope(String scope) {
            return scopes.stream().filter(s -> s.scope().equals(scope)).findFirst().orElse(null);
        }
    }

    /** One requested scope on the consent page: the raw scope, its description, and whether it needs explicit consent. */
    public record ConsentScope(String scope, String description, boolean requiresConsent) {
    }
}
