package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Wraps a grant provider that issues the FIRST refresh token of a login (e.g. the authorization-code provider) to
 * record the root of a new refresh-token family in the ledger. Rotation (children) is handled by
 * {@link ReplayDetectingRefreshTokenAuthenticationProvider}; this only records roots.
 *
 * <p>The resource-owner (user) is read from the {@link OAuth2Authorization} (its {@code principalName}, which the
 * Stage 6 login sets to the user's UUID); the tenant comes from the client's settings.
 *
 * <p>Not exercised end-to-end in 4b-i (the authorization-code flow needs the Stage 6 login/consent UI). It is wired
 * now so the ledger root exists the moment login lands; the algorithm itself is covered by the service tests.
 */
@Slf4j
public class RefreshTokenRecordingAuthenticationProvider implements AuthenticationProvider {

    private static final Duration REFRESH_TTL_FALLBACK = Duration.ofDays(14);

    private final AuthenticationProvider delegate;
    private final RefreshTokenRotationService rotationService;
    private final OAuth2AuthorizationService authorizationService;
    private final AuthServerSessionService sessionService;

    public RefreshTokenRecordingAuthenticationProvider(AuthenticationProvider delegate,
                                                       RefreshTokenRotationService rotationService,
                                                       OAuth2AuthorizationService authorizationService,
                                                       AuthServerSessionService sessionService) {
        this.delegate = delegate;
        this.rotationService = rotationService;
        this.authorizationService = authorizationService;
        this.sessionService = sessionService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication result = delegate.authenticate(authentication);

        if (result instanceof OAuth2AccessTokenAuthenticationToken tokens && tokens.getRefreshToken() != null) {
            RegisteredClient client = tokens.getRegisteredClient();
            UUID tenantId = CloseAuthClientSettings.getTenantId(client);
            OAuth2Authorization authorization = authorizationService.findByToken(
                    tokens.getAccessToken().getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
            UUID userId = resolveUserId(authorization);
            if (tenantId != null && userId != null) {
                // Request context + session linkage threaded from the /authorize-time principal (Stage 6a seams 3 & 5):
                // the SSO/login step stored the client IP + our session id in the principal's WebAuthenticationDetails,
                // which SAS persisted with the authorization (Jackson-allowlisted, so it round-trips).
                AuthContext ctx = extractAuthContext(authorization);
                String ip = ctx.ip();
                String userAgent = null;
                if (ctx.sessionId() != null) {
                    Optional<SessionView> session = sessionService.getById(ctx.sessionId());
                    if (session.isPresent()) {
                        userAgent = session.get().userAgent();
                        if (ip == null) {
                            ip = session.get().ipAddress();
                        }
                    }
                }

                OAuth2RefreshToken refreshToken = tokens.getRefreshToken();
                Instant expiresAt = refreshToken.getExpiresAt() != null
                        ? refreshToken.getExpiresAt() : Instant.now().plus(REFRESH_TTL_FALLBACK);
                String scopes = String.join(" ", tokens.getAccessToken().getScopes());
                rotationService.recordInitialToken(userId, tenantId, client.getId(),
                        new RefreshTokenIssuance(RefreshTokenHasher.sha256Hex(refreshToken.getTokenValue()),
                                scopes, expiresAt, ip, userAgent),
                        ctx.sessionId());
            } else {
                log.debug("Skipping refresh-token ledger root: tenant/user unresolved (non-user grant)");
            }
        }
        return result;
    }

    /** The user is the authorization's principal name (the Stage 6a login/SSO sets it to the user UUID). */
    private UUID resolveUserId(OAuth2Authorization authorization) {
        if (authorization == null) {
            return null;
        }
        return parseUuid(authorization.getPrincipalName());
    }

    /**
     * READ SITE. Reads the client IP + Auth Server session id off the persisted resource-owner principal's details.
     * The {@code sessionId} here carries the CloseAuth Auth Server session ledger UUID, NOT the servlet session id —
     * the SSO/login step deliberately reused this Jackson-allowlisted slot to avoid a custom details type that would
     * hit SAS's serialization allowlist (see 4b-ii D1); {@code remoteAddress} carries the client IP.
     *
     * <p>This slot-reuse is a deliberate cheap-and-safe choice for 6a's small scalar context (one UUID + one IP);
     * richer context threading (Phase 2 federation, Phase 4 agents) should instead register a Jackson mixin for a
     * purpose-built details type rather than overload more slots here.
     */
    private AuthContext extractAuthContext(OAuth2Authorization authorization) {
        Object principal = authorization.getAttribute(Principal.class.getName());
        if (principal instanceof Authentication auth
                && auth.getDetails() instanceof WebAuthenticationDetails details) {
            return new AuthContext(details.getRemoteAddress(), parseUuid(details.getSessionId()));
        }
        return new AuthContext(null, null);
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

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }

    /** Request context threaded from the /authorize principal: client IP + Auth Server session id (both nullable). */
    private record AuthContext(String ip, UUID sessionId) {
    }
}
