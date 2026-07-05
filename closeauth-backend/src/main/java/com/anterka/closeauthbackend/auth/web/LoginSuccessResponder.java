package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.session.dto.CreateSessionCommand;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Shared login-completion for the credential paths (password login, magic-link) — establishes the tenant-scoped Auth
 * Server session, sets the session cookie, and resolves the post-login redirect (resume the saved {@code /authorize}
 * request, else a default). Extracted so the session/cookie handling is defined ONCE and can't diverge between the
 * two entry points (the same "build the primitive once" discipline the OTT layer follows).
 */
@Component
@RequiredArgsConstructor
public class LoginSuccessResponder {

    private final AuthServerSessionService sessionService;
    private final SessionCookieManager cookieManager;
    private final RequestContextSupport requestContext;
    private final CloseAuthProperties properties;

    private final RequestCache requestCache = new HttpSessionRequestCache();

    /**
     * Creates the session (tagged with {@code amr}), writes the session cookie, and returns the URL to redirect to:
     * the saved authorization request if present, else the configured default.
     */
    public String establishSessionAndResolveRedirect(HttpServletRequest request, HttpServletResponse response,
                                                     UUID tenantId, UUID userId, String amr, boolean rememberMe) {
        SessionView session = sessionService.createSession(new CreateSessionCommand(
                userId, tenantId, requestContext.clientIp(request), requestContext.userAgent(request), rememberMe, amr));
        cookieManager.write(response, session.sessionKey(), rememberMe,
                properties.getSession().getRememberMeTimeout());

        SavedRequest saved = requestCache.getRequest(request, response);
        return saved != null ? saved.getRedirectUrl() : properties.getBff().getBaseUrl();
    }
}
