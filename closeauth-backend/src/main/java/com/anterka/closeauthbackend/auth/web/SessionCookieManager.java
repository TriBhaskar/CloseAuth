package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Reads / writes / clears the Auth Server session cookie carrying the opaque {@code session_key} (Stage 6a, §7.5).
 *
 * <p><b>Security attributes</b> (from {@code closeauth.session.cookie.*}, documented in the report):
 * <ul>
 *   <li><b>HttpOnly</b> — the session key is never readable by JavaScript (XSS can't exfiltrate it).</li>
 *   <li><b>Secure</b> — HTTPS-only (overridable to {@code false} for plain-HTTP local/dev + tests).</li>
 *   <li><b>SameSite=Lax</b> — the OAuth flow returns to {@code /oauth2/authorize} via a top-level redirect, on which
 *       Lax sends the cookie; Lax still withholds it on cross-site sub-requests (CSRF mitigation).</li>
 * </ul>
 * A remember-me session gets a persistent cookie (Max-Age = the absolute cap); otherwise a session cookie (cleared
 * when the browser closes).
 */
@Component
@RequiredArgsConstructor
public class SessionCookieManager {

    private final CloseAuthProperties properties;

    /** Reads the session key from the request cookie, if present. */
    public Optional<String> readSessionKey(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        String name = properties.getSession().getCookie().getName();
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** Writes the session cookie. When {@code rememberMe}, the cookie persists for {@code maxAge}; else it is a session cookie. */
    public void write(HttpServletResponse response, String sessionKey, boolean rememberMe, Duration maxAge) {
        CloseAuthProperties.Session.Cookie cfg = properties.getSession().getCookie();
        ResponseCookie cookie = ResponseCookie.from(cfg.getName(), sessionKey)
                .httpOnly(cfg.isHttpOnly())
                .secure(cfg.isSecure())
                .sameSite(cfg.getSameSite())
                .path(cfg.getPath())
                // Duration.ofSeconds(-1) → no Max-Age/Expires, i.e. a session cookie (cleared when the browser closes).
                .maxAge(rememberMe ? maxAge : Duration.ofSeconds(-1))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    /** Clears the session cookie (Max-Age=0), matching the attributes so the browser removes it. */
    public void clear(HttpServletResponse response) {
        CloseAuthProperties.Session.Cookie cfg = properties.getSession().getCookie();
        ResponseCookie cookie = ResponseCookie.from(cfg.getName(), "")
                .httpOnly(cfg.isHttpOnly())
                .secure(cfg.isSecure())
                .sameSite(cfg.getSameSite())
                .path(cfg.getPath())
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}
