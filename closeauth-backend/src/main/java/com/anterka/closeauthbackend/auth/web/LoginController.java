package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.dto.LoginOutcome;
import com.anterka.closeauthbackend.auth.enums.AuthMethod;
import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.LoginPolicyService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The interactive login endpoints (Stage 6a). SAS's {@code /oauth2/authorize}, when unauthenticated, redirects the
 * browser here (via the configured {@code LoginUrlAuthenticationEntryPoint}); this endpoint authenticates the user
 * tenant-scoped, establishes the Auth Server session + cookie, and redirects back to the saved authorization request
 * so SAS can issue the code (recognized by {@link TenantSessionSsoFilter}).
 *
 * <h2>Tenant resolution (cross-tenant-safe)</h2>
 * The tenant is derived from the authorization request's {@code client_id} — taken from the saved {@code /authorize}
 * request (the normal path), or an explicit {@code client_id} form field. Authentication then runs ONLY against that
 * tenant's user pool ({@link LoginPolicyService}). There is no global authentication path.
 *
 * <h2>HTTP contract (for the future UI stage)</h2>
 * <ul>
 *   <li>{@code GET /login} → 200 JSON login context {@code {clientId, tenantId}} (branding is 6b); the hosted page
 *       renders the form.</li>
 *   <li>{@code POST /login} (form-encoded: {@code email}, {@code password}, {@code remember_me?}, {@code client_id?})
 *       → on success <b>302</b> to the saved {@code /oauth2/authorize} URL with {@code Set-Cookie} for the session;
 *       on failure <b>401</b> with a uniform, enumeration-safe JSON body {@code {"error":"invalid_credentials"}} (a
 *       form can render it; it never reveals which factor failed).</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class LoginController {

    private final AuthFlowTenantResolver tenantResolver;
    private final LoginPolicyService loginPolicyService;
    private final LoginSuccessResponder loginSuccessResponder;

    private final RequestCache requestCache = new HttpSessionRequestCache();

    /** Login context for the hosted page. Tenant/branding detail is Stage 6b; here we expose the resolved client/tenant. */
    @GetMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> loginContext(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> body = new LinkedHashMap<>();
        String clientId = resolveClientId(null, request, response);
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
            HttpServletRequest request,
            HttpServletResponse response) {

        String clientId = resolveClientId(clientIdParam, request, response);
        Optional<UUID> tenantId = resolveTenant(clientId);
        if (tenantId.isEmpty()) {
            // Cannot determine the tenant to authenticate against — uniform failure (no enumeration).
            return invalidCredentials();
        }

        LoginOutcome outcome = loginPolicyService.authenticate(TenantContext.of(tenantId.get()), email, password);
        if (!outcome.success()) {
            return invalidCredentials();
        }

        // Establish the tenant-scoped session (capturing request context), set the cookie, resume the OAuth flow.
        // amr=pwd — this login used a password (the OIDC authentication-method reference, RFC 8176).
        String redirectUrl = loginSuccessResponder.establishSessionAndResolveRedirect(
                request, response, tenantId.get(), outcome.userId(), AuthMethod.PASSWORD.amrValue(), rememberMe);
        log.info("Login succeeded user={} tenant={} → resuming {}", outcome.userId(), tenantId.get(), redirectUrl);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
    }

    /** Prefer an explicit {@code client_id}; otherwise read it from the saved authorization request. */
    private String resolveClientId(String explicit, HttpServletRequest request, HttpServletResponse response) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null) {
            String[] values = saved.getParameterValues("client_id");
            if (values != null && values.length > 0) {
                return values[0];
            }
        }
        return null;
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
