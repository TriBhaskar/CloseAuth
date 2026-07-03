package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
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

import java.time.Duration;
import java.time.Instant;
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

    public RefreshTokenRecordingAuthenticationProvider(AuthenticationProvider delegate,
                                                       RefreshTokenRotationService rotationService,
                                                       OAuth2AuthorizationService authorizationService) {
        this.delegate = delegate;
        this.rotationService = rotationService;
        this.authorizationService = authorizationService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication result = delegate.authenticate(authentication);

        if (result instanceof OAuth2AccessTokenAuthenticationToken tokens && tokens.getRefreshToken() != null) {
            RegisteredClient client = tokens.getRegisteredClient();
            UUID tenantId = CloseAuthClientSettings.getTenantId(client);
            UUID userId = resolveUserId(tokens);
            if (tenantId != null && userId != null) {
                OAuth2RefreshToken refreshToken = tokens.getRefreshToken();
                Instant expiresAt = refreshToken.getExpiresAt() != null
                        ? refreshToken.getExpiresAt() : Instant.now().plus(REFRESH_TTL_FALLBACK);
                String scopes = String.join(" ", tokens.getAccessToken().getScopes());
                rotationService.recordInitialToken(userId, tenantId, client.getId(), new RefreshTokenIssuance(
                        RefreshTokenHasher.sha256Hex(refreshToken.getTokenValue()), scopes, expiresAt, null, null));
            } else {
                log.debug("Skipping refresh-token ledger root: tenant/user unresolved (pre-Stage-6 login flow)");
            }
        }
        return result;
    }

    /** The user is the authorization's principal name (Stage 6 login sets it to the user UUID). */
    private UUID resolveUserId(OAuth2AccessTokenAuthenticationToken tokens) {
        OAuth2Authorization authorization = authorizationService.findByToken(
                tokens.getAccessToken().getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null) {
            return null;
        }
        try {
            return UUID.fromString(authorization.getPrincipalName());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
