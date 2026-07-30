package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.session.dto.CreateSessionCommand;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Shared login-completion for the credential paths (password login, magic-link) — establishes the tenant-scoped Auth
 * Server session and writes the session cookie. Extracted so the session/cookie handling is defined ONCE and can't
 * diverge between the two entry points (the same "build the primitive once" discipline the OTT layer follows).
 *
 * <h2>Two redirect-resolution strategies</h2>
 * <p>{@link #establishSessionAndResolveRedirect(HttpServletRequest, HttpServletResponse, UUID, UUID, String,
 * boolean, String)} (password login, via {@link LoginController}) builds the resume URL directly from the caller's
 * own reconstructed {@code /oauth2/authorize} query string — no session-correlated {@code RequestCache} lookup,
 * because the backend and BFF are on genuinely separate origins with no reverse proxy, so that mechanism cannot
 * survive the BFF's server-to-server relay of {@code POST /login} (see
 * {@code CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md} §3a). This is the fixed, cross-origin-safe path.
 *
 * <p>{@link #establishSessionAndResolveRedirect(HttpServletRequest, HttpServletResponse, UUID, UUID, String,
 * boolean)} (magic-link, via {@code MagicLinkController}) retains the legacy {@code SavedRequest}-based lookup. That
 * flow's emailed link ({@code MagicLinkService#magicLinkUrl}) always points directly at THIS backend's own origin —
 * a real top-level browser navigation, never proxied through the BFF — so the servlet session established on the
 * original {@code /oauth2/authorize} hit is genuinely same-origin there and the mechanism still works. It was not
 * part of the cross-origin problem this class's other overload was fixed for, and the cross-origin design document
 * did not investigate or specify threading magic-link's own request/consume endpoints with the full
 * authorize-parameter set (mirroring {@link LoginController}) — a reasonable, low-risk follow-on, but out of scope
 * here; removing this overload now would regress magic-link's currently-working, end-to-end-tested auto-resume
 * behavior for no corresponding bug fix.
 */
@Component
@RequiredArgsConstructor
public class LoginSuccessResponder {

    private final AuthServerSessionService sessionService;
    private final SessionCookieManager cookieManager;
    private final RequestContextSupport requestContext;
    private final CloseAuthProperties properties;
    private final AuthorizationServerSettings authorizationServerSettings;

    /** Retained for the magic-link path only — see class javadoc. */
    private final RequestCache requestCache = new HttpSessionRequestCache();

    /**
     * Cross-origin-safe path (password login). Creates the session (tagged with {@code amr}), writes the session
     * cookie, and returns the URL to redirect to: a freshly built {@code /oauth2/authorize?<authorizeQuery>} URL, or
     * the configured BFF default if no authorize query was supplied.
     */
    public String establishSessionAndResolveRedirect(HttpServletRequest request, HttpServletResponse response,
                                                      UUID tenantId, UUID userId, String amr, boolean rememberMe,
                                                      String authorizeQuery) {
        createSessionAndCookie(request, response, tenantId, userId, amr, rememberMe);
        if (authorizeQuery == null || authorizeQuery.isBlank()) {
            return properties.getBff().getBaseUrl();
        }
        return properties.getIssuerUrl() + authorizationServerSettings.getAuthorizationEndpoint()
                + "?" + authorizeQuery;
    }

    /**
     * Legacy, session-correlated path (magic-link only — see class javadoc). Creates the session, writes the
     * cookie, and resumes the saved authorization request if present, else the configured default.
     */
    public String establishSessionAndResolveRedirect(HttpServletRequest request, HttpServletResponse response,
                                                      UUID tenantId, UUID userId, String amr, boolean rememberMe) {
        createSessionAndCookie(request, response, tenantId, userId, amr, rememberMe);
        SavedRequest saved = requestCache.getRequest(request, response);
        return saved != null ? saved.getRedirectUrl() : properties.getBff().getBaseUrl();
    }

    private void createSessionAndCookie(HttpServletRequest request, HttpServletResponse response,
                                        UUID tenantId, UUID userId, String amr, boolean rememberMe) {
        SessionView session = sessionService.createSession(new CreateSessionCommand(
                userId, tenantId, requestContext.clientIp(request), requestContext.userAgent(request), rememberMe, amr));
        cookieManager.write(response, session.sessionKey(), rememberMe,
                properties.getSession().getRememberMeTimeout());
    }
}
