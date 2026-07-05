package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Extracts request context ({@code ip_address}, {@code user_agent}) for the auth flows (Stage 6a — closes 4b-i C1 /
 * Stage 5's null-context seam). {@code X-Forwarded-For} is honored ONLY when the direct caller is a configured trusted
 * proxy ({@code closeauth.security.trusted-proxies}); otherwise the raw socket address is used, so a client cannot
 * spoof its IP by sending its own {@code X-Forwarded-For} (same discipline as the rest of the platform).
 */
@Component
@RequiredArgsConstructor
public class RequestContextSupport {

    private static final int MAX_USER_AGENT_LENGTH = 1000;

    private final CloseAuthProperties properties;

    /** The client IP: the first {@code X-Forwarded-For} hop when behind a trusted proxy, else the socket address. */
    public String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        List<String> trustedProxies = properties.getSecurity().getTrustedProxies();
        if (trustedProxies != null && trustedProxies.contains(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",", 2)[0].trim();
            }
        }
        return remoteAddr;
    }

    /** The client user-agent (truncated defensively), or {@code null} if absent. */
    public String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return null;
        }
        return ua.length() > MAX_USER_AGENT_LENGTH ? ua.substring(0, MAX_USER_AGENT_LENGTH) : ua;
    }
}
