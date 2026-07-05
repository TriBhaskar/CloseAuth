package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code /oauth2/authorize} SSO consult (Stage 6a — the whole point of the Stage 5 session model). When an
 * authorization request arrives and the {@link SecurityContext} is not yet authenticated, this filter resolves the
 * tenant from the request's {@code client_id}, validates the tenant-scoped Auth Server session carried by the session
 * cookie, and — if valid — populates the {@link SecurityContext} with the session's user so SAS proceeds WITHOUT
 * showing login (single sign-on). If there is no valid <em>tenant-matching</em> session, the context is left
 * unauthenticated and SAS's normal flow redirects to login.
 *
 * <p><b>Why the tenant-scoped session (not the servlet session) is the sole SSO driver — the security crux:</b> the
 * decision is re-derived on EVERY {@code /authorize} from {@code validateSession(cookie, tenantOfThisRequest)}. A
 * session established in tenant A therefore cannot silently authorize a tenant-B client (validation returns empty for
 * the wrong tenant), even in the same browser. We never persist an authenticated {@code SecurityContext} to the
 * servlet session for cross-request reuse, precisely so a tenant-blind servlet-session principal can't leak across
 * tenants. This closes the deferred Stage-5 seam without a cross-tenant hole.
 *
 * <p>Placement: {@code addFilterBefore(AuthorizationFilter.class)} on the Authorization Server chain — after Spring
 * Security has established the (anonymous) context but before the {@code authenticated()} authorization check, so
 * setting the context here makes that check pass and SAS's authorization endpoint use our principal.
 *
 * <p>The principal's {@link WebAuthenticationDetails} carries the client IP and — in its {@code sessionId} slot — our
 * Auth Server session's ledger id. SAS persists this principal with the authorization (Jackson-allowlisted types
 * only, so it round-trips — the 4b-ii D1 lesson), letting the token endpoint later link the refresh family to the
 * session and stamp the request context (Stage 6a seams 3 &amp; 5).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantSessionSsoFilter extends OncePerRequestFilter {

    private final AuthFlowTenantResolver tenantResolver;
    private final AuthServerSessionService sessionService;
    private final SessionCookieManager cookieManager;
    private final RequestContextSupport requestContext;
    private final AuthorizationServerSettings authorizationServerSettings;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAuthorizationEndpoint(request) && !isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
            try {
                consultSession(request);
            } catch (RuntimeException e) {
                // Never break /authorize on an SSO-consult error: fall through to the login flow (fail toward re-auth).
                log.warn("SSO session consult failed; falling back to login", e);
            }
        }
        chain.doFilter(request, response);
    }

    private void consultSession(HttpServletRequest request) {
        String clientId = request.getParameter("client_id");
        Optional<UUID> tenantId = tenantResolver.resolveTenantId(clientId);
        Optional<String> sessionKey = cookieManager.readSessionKey(request);
        if (tenantId.isEmpty() || sessionKey.isEmpty()) {
            return; // no client/tenant, or no cookie → not an SSO candidate; SAS will require login
        }
        // Tenant-scoped: only a session belonging to THIS request's tenant validates.
        Optional<SessionView> session = sessionService.validateSession(sessionKey.get(), tenantId.get());
        if (session.isEmpty()) {
            return; // no / expired / wrong-tenant session → login required (no cross-tenant leak)
        }
        SessionView s = session.get();

        // Carry the login's authentication method (amr) as an authority so the token customizer can emit the OIDC
        // amr claim (RFC 8176) without a custom principal type — same serialization-safe channel discipline as the
        // details slot-reuse below (Jackson-allowlisted SimpleGrantedAuthority). Prefix "AMR_" is stripped downstream.
        List<String> authorities = new ArrayList<>(List.of("ROLE_USER"));
        if (s.amr() != null && !s.amr().isBlank()) {
            authorities.add("AMR_" + s.amr());
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                s.userId().toString(), null, AuthorityUtils.createAuthorityList(authorities.toArray(String[]::new)));
        // WRITE SITE. The sessionId here carries the CloseAuth Auth Server session ledger UUID, NOT the servlet
        // session id — deliberately reusing this Jackson-allowlisted slot to avoid a custom details type that would
        // hit SAS's serialization allowlist (see 4b-ii D1). remoteAddress carries the client IP. SAS persists this
        // principal with the authorization; the token endpoint reads both back (see RefreshTokenRecordingAuthentication
        // Provider#extractAuthContext).
        // This slot-reuse is a deliberate cheap-and-safe choice for 6a's small scalar context (one UUID + one IP);
        // richer context threading (Phase 2 federation, Phase 4 agents) should instead register a Jackson mixin for a
        // purpose-built details type rather than overload more slots here.
        authentication.setDetails(new WebAuthenticationDetails(requestContext.clientIp(request), s.id().toString()));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        log.debug("SSO recognized: session={} user={} tenant={} on /authorize (login skipped)",
                s.id(), s.userId(), tenantId.get());
    }

    private boolean isAuthorizationEndpoint(HttpServletRequest request) {
        // getServletPath() is context-path-stripped, matching the configured endpoint (e.g. /oauth2/authorize).
        return authorizationServerSettings.getAuthorizationEndpoint().equals(request.getServletPath());
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
