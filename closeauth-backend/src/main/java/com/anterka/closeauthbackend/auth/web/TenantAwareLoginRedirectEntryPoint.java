package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * BE-B: the unauthenticated-entry-point redirect target for a browser hitting a protected endpoint, made
 * tenant-aware. Spec §2.2 puts every hosted-auth page under {@code /t/{tenantId}/...}; this promotes the previous
 * anonymous {@code LoginUrlAuthenticationEntryPoint} subclass in {@code AuthorizationServerConfig} (which redirected
 * to one fixed {@code closeauth.bff.login-page}) into a named class that resolves the tenant from the incoming
 * request's {@code client_id} and builds {@code {bffBaseUrl}/t/{slug}/login} instead.
 *
 * <p>Cross-origin login continuity (unchanged from before this class existed): the backend and the BFF are separate
 * origins with no reverse proxy, so Spring Security's session-based {@code RequestCache}/{@code SavedRequest}
 * mechanism cannot survive the hop to the BFF's login form and back. Carrying the original {@code /oauth2/authorize}
 * request's own query string forward on the URL sidesteps this — it is already public, URL-visible data in any
 * normal OAuth2 flow (see CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md §3a/§3c). A successful login then re-issues a fresh
 * {@code /oauth2/authorize} hit with the same parameters ({@link LoginSuccessResponder}) rather than resuming a
 * session-correlated saved request.
 *
 * <p>An unresolvable {@code client_id} (missing, or belonging to no known/live tenant) has no tenant to namespace a
 * login page under — spec §2.2's hosted-auth pages are all tenant-branded, so there is no side-door unnamespaced
 * {@code /login} to fall back to. It redirects to the platform's own terminal error page instead.
 */
public class TenantAwareLoginRedirectEntryPoint extends LoginUrlAuthenticationEntryPoint {

    private final AuthFlowTenantResolver tenantResolver;
    private final CloseAuthProperties properties;

    public TenantAwareLoginRedirectEntryPoint(AuthFlowTenantResolver tenantResolver, CloseAuthProperties properties) {
        // The parent's loginFormUrl is never actually used — determineUrlToUseForThisRequest below is fully
        // overridden and always computes its own URL — but the constructor requires a non-blank value.
        super(properties.getBff().getBaseUrl() + "/error");
        this.tenantResolver = tenantResolver;
        this.properties = properties;
    }

    @Override
    protected String determineUrlToUseForThisRequest(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) {
        String clientId = request.getParameter("client_id");
        return tenantResolver.resolveTenantSlug(clientId)
                .map(slug -> withOriginalQuery(request, properties.getBff().getBaseUrl() + "/t/" + slug + "/login"))
                .orElseGet(() -> properties.getBff().getBaseUrl() + "/error?reason=unknown_client");
    }

    private static String withOriginalQuery(HttpServletRequest request, String base) {
        String query = request.getQueryString();
        return (query == null || query.isBlank()) ? base : base + "?" + query;
    }
}
