package com.anterka.closeauthbackend.common.util;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the real client IP address for security decisions (e.g. rate limiting).
 *
 * <p>The {@code X-Forwarded-For} header is attacker-controlled and must NOT be
 * trusted blindly — a client could send a fresh fake value per request to evade
 * IP-based rate limiting. This resolver only honors the header when the direct
 * caller is a configured trusted proxy; otherwise it falls back to the raw socket
 * address ({@link HttpServletRequest#getRemoteAddr()}).
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final CloseAuthProperties properties;

    /**
     * Returns the best-effort real client IP for the given request.
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        List<String> trustedProxies = properties.getSecurity().getTrustedProxies();
        if (trustedProxies == null || !trustedProxies.contains(remoteAddr)) {
            // Direct caller is not a trusted proxy — ignore forwarded headers.
            return remoteAddr;
        }

        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }

        // The left-most entry is the originating client as recorded by the trusted proxy.
        String clientIp = forwardedFor.split(",")[0].trim();
        return clientIp.isEmpty() ? remoteAddr : clientIp;
    }
}

