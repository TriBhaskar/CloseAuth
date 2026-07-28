package com.anterka.closeauth.it.journeys;

import com.anterka.closeauth.it.support.AdminApiClient;
import com.anterka.closeauth.it.support.AdminApiClient.ClientCredentials;
import com.anterka.closeauth.it.support.Fixtures;
import com.anterka.closeauth.it.support.IntegrationTest;
import com.anterka.closeauth.it.support.Jwt;
import com.anterka.closeauth.it.support.OAuthFlowClient.AuthorizeOutcome;
import com.anterka.closeauth.it.support.OAuthFlowClient.ConsentContext;
import com.anterka.closeauth.it.support.OAuthFlowClient.ConsentScope;
import com.anterka.closeauth.it.support.OAuthFlowClient.SessionState;
import com.anterka.closeauth.it.support.OAuthFlowClient.TokenResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-6's OAuth2 consent journey, black-box. This is the first stage to register a <b>{@code trusted=false}</b> client —
 * every prior stage used trusted clients specifically to avoid consent. It proves, from outside the process, the subtle
 * consent behaviours the Stage 6b-ii build had to get right:
 * <ol>
 *   <li>a non-trusted client redirects to consent instead of issuing a code;</li>
 *   <li>the consent context reports correct descriptions + {@code requiresConsent} flags for tenant-RS and platform scopes;</li>
 *   <li>approving yields a token that also carries the auto-granted ({@code requires_consent=false}) scope never explicitly approved;</li>
 *   <li>the auto-grant <b>persists</b> — a second authorization skips consent entirely (the key proof);</li>
 *   <li>a denial grants nothing (not even the auto-grantable scope) and forces consent again;</li>
 *   <li>admin list + revoke actually removes the persisted consent, forcing re-consent.</li>
 * </ol>
 *
 * <p>Independent test methods share the container stack and a fixture set (one trusted login client for establishing
 * sessions + one non-trusted consent client with a {@code requires_consent=true} scope added to its RS). Each scenario
 * uses its own fresh user so consent records never collide.
 */
class ConsentJourneyTest extends IntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Pw!";
    private static final String MANAGE_DESC = "Manage your to-do items and settings";
    // Built-in, non-tenant-controllable platform-scope descriptions (ConsentScopeResolver.PLATFORM_SCOPES).
    private static final String OPENID_DESC = "Verify your identity";
    private static final String PROFILE_DESC = "Access your basic profile (name and details)";

    private static String platformToken;
    private static String tenant;
    private static String loginClientId;    // trusted — used only to establish a user session
    private static String loginSecret;
    private static String consentClientId;  // trusted=false — the client under test
    private static String consentSecret;
    private static String readScope;        // {slug}:read   — requires_consent=false (auto-grantable)
    private static String manageScope;      // {slug}:manage — requires_consent=true  (explicit)
    private static String requestedScope;   // "openid profile {slug}:read {slug}:manage"

    @BeforeAll
    static void fixtures() {
        AdminApiClient admin = adminApi();
        platformToken = platformAdminToken();
        tenant = admin.provisionActiveTenant(platformToken);

        ClientCredentials login = admin.registerConfidentialClient(platformToken, tenant);
        loginClientId = login.clientId();
        loginSecret = login.secret();

        // Non-trusted consent client. clientName == clientId (already a clean slug) so the auto-created Resource
        // Server's slug == clientId, letting us predict the {slug}:scope strings.
        consentClientId = Fixtures.clientId("consent");
        readScope = consentClientId + ":read";
        manageScope = consentClientId + ":manage";
        requestedScope = String.join(" ", "openid", "profile", readScope, manageScope);
        ClientCredentials consent = admin.registerClient(platformToken, tenant, consentClientId, consentClientId,
                false, List.of("openid", "profile", readScope, manageScope));
        consentSecret = consent.secret();

        // The auto-created RS already has a default `read` (requires_consent=false). Add `manage` (requires_consent=true).
        String rsId = admin.resourceServerIdBySlug(platformToken, tenant, consentClientId);
        admin.addScope(platformToken, tenant, rsId, "manage", MANAGE_DESC, true, false);
    }

    // ---- Scenario 1: non-trusted client triggers consent ------------------

    @Test
    @DisplayName("Scenario 1: a non-trusted client redirects to consent instead of issuing a code directly")
    void nonTrustedClientTriggersConsent() {
        UserSession user = freshUser();
        AuthorizeOutcome outcome = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(outcome.consentRequired())
                .as("a trusted client would get a code; this non-trusted one must require consent").isTrue();
        assertThat(outcome.code()).as("no code until consent is decided").isNull();
    }

    // ---- Scenario 2: consent context correctness --------------------------

    @Test
    @DisplayName("Scenario 2: consent context reports correct descriptions + requiresConsent for tenant-RS and platform scopes")
    void consentContextIsCorrect() {
        UserSession user = freshUser();
        AuthorizeOutcome trigger = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(trigger.consentRequired()).isTrue();

        ConsentContext ctx = oauthFlow().fetchConsentContext(
                consentClientId, trigger.session(), trigger.scope(), trigger.state());
        assertThat(ctx.clientId()).isEqualTo(consentClientId);

        // tenant-RS requires_consent=true scope → its real description + requiresConsent=true
        ConsentScope manage = ctx.scope(manageScope);
        assertThat(manage).as("manage scope present").isNotNull();
        assertThat(manage.requiresConsent()).as("manage needs explicit consent").isTrue();
        assertThat(manage.description()).isEqualTo(MANAGE_DESC);

        // tenant-RS requires_consent=false scope → requiresConsent=false (auto-grantable, not prompted)
        ConsentScope read = ctx.scope(readScope);
        assertThat(read).as("read scope present").isNotNull();
        assertThat(read.requiresConsent()).as("read is auto-grantable").isFalse();

        // platform scopes → built-in descriptions + requiresConsent=true
        ConsentScope openid = ctx.scope("openid");
        assertThat(openid).as("openid present").isNotNull();
        assertThat(openid.requiresConsent()).isTrue();
        assertThat(openid.description()).isEqualTo(OPENID_DESC);
        ConsentScope profile = ctx.scope("profile");
        assertThat(profile).as("profile present").isNotNull();
        assertThat(profile.requiresConsent()).isTrue();
        assertThat(profile.description()).isEqualTo(PROFILE_DESC);
    }

    // ---- Scenario 3: approve → token carries the auto-granted scope -------

    @Test
    @DisplayName("Scenario 3: approving grants the explicit scopes; the auto-granted scope becomes effective once persisted")
    void approveGrantsExplicitScopesAndAutoGrantBecomesEffective() {
        UserSession user = freshUser();
        AuthorizeOutcome trigger = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(trigger.consentRequired()).isTrue();

        // Approve ONLY the scopes that require consent — deliberately NOT readScope.
        AuthorizeOutcome approved = oauthFlow().submitConsent(consentClientId, trigger.session(), trigger.state(),
                List.of("openid", "profile", manageScope));
        assertThat(approved.ssoRecognized()).as("approval issues a code").isTrue();

        // The FIRST token (exchanged with the TRIGGERING authorize's verifier) carries exactly the explicitly-approved
        // scopes. BACKEND FINDING (IT-6 report): the auto-granted `read` is persisted into the consent record (proven
        // in scenario 6) but SAS issues THIS code with only the submitted scopes — the auto-grant customizer mutates the
        // persisted OAuth2AuthorizationConsent, NOT the current authorization's granted scopes — so `read` is NOT in the
        // first token, despite the AuthorizationServerConfig comment implying it's granted "on this ... authorization".
        TokenResponse firstTokens = oauthFlow().exchange(consentClientId, consentSecret, approved.code(), trigger.codeVerifier());
        List<String> firstScopes = scopeList(firstTokens);
        assertThat(firstScopes).as("first token carries the explicitly approved scopes")
                .contains("openid", "profile", manageScope);
        assertThat(firstScopes).as("the auto-granted read is not yet in the FIRST token (it is only persisted to consent)")
                .doesNotContain(readScope);

        // A SECOND authorization now skips consent (read is persisted) and its token DOES carry the auto-granted read —
        // proving the requires_consent=false scope is effectively granted without ever being explicitly approved.
        AuthorizeOutcome second = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(second.ssoRecognized()).as("second authorization skips consent").isTrue();
        TokenResponse secondTokens = oauthFlow().exchange(consentClientId, consentSecret, second.code(), second.codeVerifier());
        assertThat(scopeList(secondTokens))
                .as("the auto-granted (requires_consent=false) scope is carried once consent is persisted")
                .contains("openid", "profile", manageScope, readScope);
    }

    // ---- Scenario 4: persistence — second authorization skips consent -----

    @Test
    @DisplayName("Scenario 4: after approval, a second authorization for the same scopes skips consent (the key proof)")
    void secondAuthorizationSkipsConsent() {
        UserSession user = freshUser();

        AuthorizeOutcome trigger = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(trigger.consentRequired()).isTrue();
        AuthorizeOutcome approved = oauthFlow().submitConsent(consentClientId, trigger.session(), trigger.state(),
                List.of("openid", "profile", manageScope));
        assertThat(approved.ssoRecognized()).isTrue();

        // Second authorization, same user/client/scopes → a code directly, NO consent prompt. This proves the
        // auto-granted `read` was PERSISTED into the consent record (not a one-time bypass).
        AuthorizeOutcome second = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(second.consentRequired()).as("consent must NOT be requested a second time").isFalse();
        assertThat(second.ssoRecognized()).as("the saved consent lets SAS issue a code directly").isTrue();
        assertThat(second.code()).isNotBlank();
    }

    // ---- Scenario 5: deny grants nothing, re-attempt still consents -------

    @Test
    @DisplayName("Scenario 5: denying grants nothing (not even auto-grantable scopes) and forces consent again")
    void denyGrantsNothingAndForcesReconsent() {
        UserSession user = freshUser();

        AuthorizeOutcome trigger = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(trigger.consentRequired()).isTrue();

        // Deny = submit zero approved scopes.
        AuthorizeOutcome denied = oauthFlow().submitConsent(consentClientId, trigger.session(), trigger.state(), List.of());
        assertThat(denied.accessDenied()).as("a zero-scope submission is a denial").isTrue();
        assertThat(denied.code()).as("no code on denial").isNull();
        assertThat(denied.location()).as("OAuth access_denied error").contains("error=access_denied");

        // Re-attempt → consent required AGAIN: denial left no partial record, not even for the auto-grantable read.
        AuthorizeOutcome reAttempt = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(reAttempt.consentRequired())
                .as("denial persisted no consent — the next authorization must prompt again").isTrue();
    }

    // ---- Scenario 6: admin list + revoke forces re-consent ----------------

    @Test
    @DisplayName("Scenario 6: admin lists a granted consent and revokes it; the revoke actually forces re-consent")
    void adminListAndRevokeConsentForcesReconsent() {
        UserSession user = freshUser();

        // Establish a consent to list/revoke.
        AuthorizeOutcome trigger = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(trigger.consentRequired()).isTrue();
        assertThat(oauthFlow().submitConsent(consentClientId, trigger.session(), trigger.state(),
                List.of("openid", "profile", manageScope)).ssoRecognized()).isTrue();

        // List consents for this user → the consent client is listed. (Root JSON array → pluck parallel field lists.)
        Response list = adminApi().get(platformToken, "/v1/tenants/{tid}/users/{uid}/consents", tenant, user.userId());
        assertThat(list.statusCode()).isEqualTo(200);
        List<String> clientIds = list.jsonPath().getList("clientId");
        int idx = clientIds.indexOf(consentClientId);
        assertThat(idx).as("the consent for the consent client is listed").isGreaterThanOrEqualTo(0);
        String registeredClientId = list.jsonPath().<String>getList("registeredClientId").get(idx);
        List<String> scopes = list.jsonPath().<List<String>>getList("scopes").get(idx);
        // The admin consents API returns BARE scope names (the SCOPE_ authority prefix is stripped by the consent
        // service — the IT-6 follow-up fix). The auto-granted read IS present here — proving auto-grant was persisted
        // into the consent record even though it was never explicitly submitted.
        assertThat(scopes).as("listed consent carries the granted scopes (incl. the auto-granted read), as bare names")
                .contains(manageScope, readScope);

        // Revoke by SAS's internal registeredClientId → 204.
        Response revoke = adminApi().delete(platformToken, "/v1/tenants/{tid}/users/{uid}/consents/{rcid}",
                tenant, user.userId(), registeredClientId);
        assertThat(revoke.statusCode()).as("consent revoke").isEqualTo(204);

        // Re-attempt authorization → consent required again: the revoke really removed the persisted record.
        AuthorizeOutcome reAttempt = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(reAttempt.consentRequired())
                .as("after admin revoke, authorization must prompt for consent again").isTrue();
    }

    // ---- Bonus: no scope elevation ----------------------------------------

    @Test
    @DisplayName("Bonus: submitting a scope that was never requested is not silently granted")
    void submittingUnrequestedScopeIsNotElevated() {
        UserSession user = freshUser();
        AuthorizeOutcome trigger = oauthFlow().authorize(consentClientId, user.session(), requestedScope);
        assertThat(trigger.consentRequired()).isTrue();

        // `email` was never in the authorization request. SAS must not grant it.
        AuthorizeOutcome outcome = oauthFlow().submitConsent(consentClientId, trigger.session(), trigger.state(),
                List.of("openid", "profile", manageScope, "email"));
        if (outcome.ssoRecognized()) {
            // If SAS ignored the out-of-request scope: the issued token must NOT carry it.
            TokenResponse tokens = oauthFlow().exchange(consentClientId, consentSecret, outcome.code(), trigger.codeVerifier());
            assertThat(scopeList(tokens)).as("un-requested scope not elevated into the token").doesNotContain("email");
        } else {
            // Or SAS rejected the request outright (invalid_scope / access_denied): nothing granted either way.
            assertThat(outcome.code()).as("no token granted for an out-of-request scope").isNull();
        }
    }

    // ---- fixtures ----------------------------------------------------------

    /** A fresh ACTIVE user with a logged-in session (via the trusted login client). */
    private static UserSession freshUser() {
        String email = Fixtures.email("consent-user");
        String userId = adminApi().createActiveUser(platformToken, tenant, email, PASSWORD);
        SessionState session = oauthFlow().login(loginClientId, loginSecret, email, PASSWORD).session();
        return new UserSession(userId, session);
    }

    private record UserSession(String userId, SessionState session) {
    }

    /** The access token's space-delimited {@code scope} claim, split into a list. */
    private static List<String> scopeList(TokenResponse tokens) {
        String scopeClaim = (String) Jwt.claims(tokens.accessToken()).get("scope");
        assertThat(scopeClaim).as("access token scope claim present").isNotNull();
        return List.of(scopeClaim.split(" "));
    }
}
