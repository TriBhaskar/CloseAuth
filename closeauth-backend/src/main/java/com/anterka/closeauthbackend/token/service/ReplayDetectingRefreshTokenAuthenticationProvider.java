package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.token.entity.RefreshToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;

import java.time.Duration;
import java.time.Instant;

/**
 * Wraps SAS's {@code OAuth2RefreshTokenAuthenticationProvider} to add rotation + replay detection (§7.3), keeping
 * the algorithm itself in {@link RefreshTokenRotationService}.
 *
 * <p>Before delegating: hash the presented refresh token and run {@link RefreshTokenRotationService#authorizeRotation}.
 * Only {@code PROCEED} lets SAS issue new tokens; a replay (which has already revoked the family), a lost race, an
 * expired or unknown token → {@code invalid_grant}. After SAS issues the new refresh token: record it in the ledger
 * as a child of the presented (now-USED) token, preserving family lineage.
 *
 * <p>Note: the atomic {@code ACTIVE->USED} transition commits BEFORE SAS issues, so the concurrency guard is in
 * place before issuance. If SAS's issuance were to fail after a successful check, the presented token is already
 * consumed and the client re-authenticates — the safe direction.
 */
@Slf4j
public class ReplayDetectingRefreshTokenAuthenticationProvider implements AuthenticationProvider {

    private static final Duration REFRESH_TTL_FALLBACK = Duration.ofDays(14);

    private final AuthenticationProvider delegate;
    private final RefreshTokenRotationService rotationService;

    public ReplayDetectingRefreshTokenAuthenticationProvider(AuthenticationProvider delegate,
                                                             RefreshTokenRotationService rotationService) {
        this.delegate = delegate;
        this.rotationService = rotationService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        OAuth2RefreshTokenAuthenticationToken request = (OAuth2RefreshTokenAuthenticationToken) authentication;
        String presentedHash = RefreshTokenHasher.sha256Hex(request.getRefreshToken());

        RotationOutcome outcome = rotationService.authorizeRotation(presentedHash);
        if (outcome.type() != RotationOutcome.Type.PROCEED) {
            // REPLAY (family already revoked), RACE_LOST, EXPIRED, UNKNOWN — reject without issuing.
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_GRANT, "Refresh token rejected (" + outcome.type() + ")", null));
        }
        RefreshToken parent = outcome.parent();

        Authentication result = delegate.authenticate(authentication);

        if (result instanceof OAuth2AccessTokenAuthenticationToken tokens && tokens.getRefreshToken() != null) {
            OAuth2RefreshToken newRefreshToken = tokens.getRefreshToken();
            Instant expiresAt = newRefreshToken.getExpiresAt() != null
                    ? newRefreshToken.getExpiresAt() : Instant.now().plus(REFRESH_TTL_FALLBACK);
            String scopes = String.join(" ", tokens.getAccessToken().getScopes());
            rotationService.recordRotatedToken(parent, new RefreshTokenIssuance(
                    RefreshTokenHasher.sha256Hex(newRefreshToken.getTokenValue()), scopes, expiresAt, null, null));
        }
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
