package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.dto.LoginOutcome;
import com.anterka.closeauthbackend.auth.enums.AuthMethod;
import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.LoginPolicyService;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The interactive login endpoints (Stage 6a). SAS's {@code /oauth2/authorize}, when unauthenticated, redirects the
 * browser to the BFF's hosted login page (via the configured {@code LoginUrlAuthenticationEntryPoint}, carrying the
 * original request's own query string — see {@code CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md}); this endpoint
 * authenticates the user tenant-scoped, establishes the Auth Server session + cookie, and redirects back to a
 * freshly re-issued {@code /oauth2/authorize} hit (built from the same parameters this endpoint received) so SAS can
 * issue the code (recognized by {@link TenantSessionSsoFilter}'s SSO consult).
 *
 * <h2>Tenant resolution (cross-tenant-safe)</h2>
 * The tenant is derived from the authorization request's {@code client_id} — an explicit request parameter, carried
 * forward end-to-end from the original {@code /oauth2/authorize} hit (no session-correlated request cache: the
 * backend and BFF are on genuinely separate origins with no reverse proxy, so that mechanism cannot survive the
 * hop). Authentication then runs ONLY against that tenant's user pool ({@link LoginPolicyService}). There is no
 * global authentication path.
 *
 * <h2>HTTP contract (for the future UI stage)</h2>
 * <ul>
 *   <li>{@code GET /login} → 200 JSON login context {@code {clientId, tenantId}} (branding is 6b); the hosted page
 *       renders the form.</li>
 *   <li>{@code POST /login} (form-encoded: {@code email}, {@code password}, {@code remember_me?}, plus the original
 *       {@code /oauth2/authorize} parameters — {@code client_id}, {@code redirect_uri}, {@code response_type},
 *       {@code scope}, {@code state}, {@code code_challenge}, {@code code_challenge_method}, and any OIDC extras)
 *       → on success <b>302</b> to a freshly reconstructed {@code /oauth2/authorize} URL with {@code Set-Cookie} for
 *       the session; on failure <b>401</b> with a uniform, enumeration-safe JSON body
 *       {@code {"error":"invalid_credentials"}} (a form can render it; it never reveals which factor failed —
 *       including whether the failure was a bad credential, a rate-limit refusal, or an expired temp credential,
 *       see {@link LoginPolicyService#authenticate}); when the correct password belongs to a credential pending
 *       forced rotation (Phase 2, §2.2), <b>302</b> instead to the password-rotation page — <em>no</em>
 *       {@code Set-Cookie}, no session, no code: identity was proven but access is withheld until rotation
 *       completes at {@code POST /password-rotation/confirm}.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class LoginController {

    private final AuthFlowTenantResolver tenantResolver;
    private final LoginPolicyService loginPolicyService;
    private final LoginSuccessResponder loginSuccessResponder;
    private final PasswordRotationService passwordRotationService;

    /**
     * Non-authorize form fields — excluded when reconstructing the {@code /oauth2/authorize} query string for the
     * post-login redirect (see {@link #buildAuthorizeQuery(HttpServletRequest)}).
     */
    private static final Set<String> NON_AUTHORIZE_PARAMS = Set.of("email", "password", "remember_me");

    /** Login context for the hosted page. Tenant/branding detail is Stage 6b; here we expose the resolved client/tenant. */
    @GetMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> loginContext(
            @RequestParam(value = "client_id", required = false) String clientIdParam) {
        Map<String, Object> body = new LinkedHashMap<>();
        String clientId = resolveClientId(clientIdParam);
        body.put("clientId", clientId);
        resolveTenant(clientId).ifPresent(t -> body.put("tenantId", t.toString()));
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> login(
            @RequestParam("email") String email,
            @RequestParam("password") String password,
            @RequestParam(value = "remember_me", defaultValue = "false") boolean rememberMe,
            @RequestParam(value = "client_id", required = false) String clientIdParam,
            // The remaining original /oauth2/authorize parameters, carried forward end-to-end (cross-origin login
            // continuity — see CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md §3a). Named here (matching client_id's
            // existing style) for readability/validation; the actual redirect reconstruction below reads the full,
            // unabridged parameter map so OIDC extras (nonce, prompt, login_hint, ...) survive too without having to
            // be hand-enumerated.
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            HttpServletRequest request,
            HttpServletResponse response) {

        String clientId = resolveClientId(clientIdParam);
        Optional<UUID> tenantId = resolveTenant(clientId);
        if (tenantId.isEmpty()) {
            // Cannot determine the tenant to authenticate against — uniform failure (no enumeration).
            return invalidCredentials();
        }

        LoginOutcome outcome = loginPolicyService.authenticate(TenantContext.of(tenantId.get()), email, password);
        if (outcome.rotationRequired()) {
            // The password was correct, but must_change_password is set (Phase 2, §2.2) — the caller proved
            // identity, but NO session/code/token may be issued yet. Route to the rotation interstitial instead of
            // establishSessionAndResolveRedirect; the original authorize request is carried forward exactly as an
            // ordinary login already carries it (buildAuthorizeQuery, unchanged), so rotation resumes it afterward.
            String authorizeQuery = buildAuthorizeQuery(request);
            String rotationUrl = passwordRotationService.beginRotation(
                    TenantContext.of(tenantId.get()), outcome.userId(), email, clientId, authorizeQuery,
                    tenantResolver.resolveTenantSlug(clientId).orElse(null));
            log.info("Login requires password rotation user={} tenant={} → {}", outcome.userId(), tenantId.get(),
                    rotationUrl);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(rotationUrl)).build();
        }
        if (!outcome.success()) {
            return invalidCredentials();
        }

        // Establish the tenant-scoped session (capturing request context), set the cookie, and resolve the redirect
        // by re-issuing a fresh /oauth2/authorize hit with the same parameters this request received — NOT by
        // resuming a session-correlated saved request (see LoginSuccessResponder).
        // amr=pwd — this login used a password (the OIDC authentication-method reference, RFC 8176).
        String authorizeQuery = buildAuthorizeQuery(request);
        String redirectUrl = loginSuccessResponder.establishSessionAndResolveRedirect(request, response,
                tenantId.get(), outcome.userId(), AuthMethod.PASSWORD.amrValue(), rememberMe, authorizeQuery);
        log.info("Login succeeded user={} tenant={} → resuming {}", outcome.userId(), tenantId.get(), redirectUrl);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
    }

    /**
     * Reconstructs the original {@code /oauth2/authorize} query string from this request's own parameters (the
     * server-to-server-hop equivalent of {@code SavedRequest.getRedirectUrl()}'s full-fidelity reconstruction,
     * without SAS's {@code RequestCache} machinery) — full-fidelity passthrough, not a hand-picked field allowlist,
     * so any OIDC extra a relying party sends survives too.
     */
    private String buildAuthorizeQuery(HttpServletRequest request) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (NON_AUTHORIZE_PARAMS.contains(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        return query.toString();
    }

    /** The client_id is now always carried explicitly (no session-correlated request-cache fallback). */
    private String resolveClientId(String explicit) {
        return (explicit != null && !explicit.isBlank()) ? explicit : null;
    }

    private Optional<UUID> resolveTenant(String clientId) {
        return clientId == null ? Optional.empty() : tenantResolver.resolveTenantId(clientId);
    }

    private ResponseEntity<Map<String, Object>> invalidCredentials() {
        // Uniform, enumeration-safe failure — identical for bad password / unknown user / inactive user / inactive tenant.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_credentials");
        body.put("error_description", "Authentication failed.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
