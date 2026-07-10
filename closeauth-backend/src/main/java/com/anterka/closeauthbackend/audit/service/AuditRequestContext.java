package com.anterka.closeauthbackend.audit.service;

import com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent;
import com.anterka.closeauthbackend.common.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Fills the request-derived fields of a {@link CloseAuthAuditEvent} that a call site usually shouldn't have to thread:
 * the client IP / User-Agent (from the current request), and — for admin mutations — the ACTOR (from the caller's
 * CloseAuth JWT). Kept separate from {@link AuditEmitter} so it is unit-testable and so the actor-resolution rules
 * live in one place.
 *
 * <p><b>Actor resolution</b> mirrors {@code AdminAuthorization}'s reading of the CloseAuth token: a platform token
 * ({@code token_use=platform_admin}) → {@code actorPlatformAdminId}; a tenant-user token (has {@code tenant_id}) →
 * {@code actorUserId}. Only applied when the event carries NO explicit actor (a login/OTT flow that already recorded
 * its subject-as-actor is left untouched, and pre-auth flows have no principal to read).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditRequestContext {

    private final ClientIpResolver clientIpResolver;

    /** Returns a copy of {@code event} with IP/User-Agent and (when absent) the actor filled from the current request. */
    public CloseAuthAuditEvent enrich(CloseAuthAuditEvent event) {
        CloseAuthAuditEvent.CloseAuthAuditEventBuilder builder = event.toBuilder();

        HttpServletRequest request = currentRequest();
        if (request != null) {
            if (event.getIpAddress() == null) {
                builder.ipAddress(clientIpResolver.resolve(request));
            }
            if (event.getUserAgent() == null) {
                builder.userAgent(request.getHeader("User-Agent"));
            }
        }

        if (event.hasNoActor()) {
            applyActorFromSecurityContext(builder);
        }
        return builder.build();
    }

    private void applyActorFromSecurityContext(CloseAuthAuditEvent.CloseAuthAuditEventBuilder builder) {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return; // unauthenticated flow (login, OTT, logout) — no actor to attribute
        }
        UUID sub = parseUuid(jwt.getSubject());
        if (sub == null) {
            return;
        }
        if ("platform_admin".equals(jwt.getClaimAsString("token_use"))) {
            builder.actorPlatformAdminId(sub);
        } else if (jwt.getClaimAsString("tenant_id") != null) {
            builder.actorUserId(sub);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
