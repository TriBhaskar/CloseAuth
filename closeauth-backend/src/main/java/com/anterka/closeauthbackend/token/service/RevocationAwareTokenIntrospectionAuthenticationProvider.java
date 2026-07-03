package com.anterka.closeauthbackend.token.service;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;

import java.time.Instant;
import java.util.UUID;

/**
 * Wraps SAS's {@code OAuth2TokenIntrospectionAuthenticationProvider} to make {@code /oauth2/introspect} consult the
 * Redis revocation list (§7.3). SAS first validates the token (signature + expiry); if it's active, we additionally
 * check whether its subject/tenant has a revocation marker with a timestamp at/after the token's {@code iat}. If so
 * the token is reported inactive.
 *
 * <p>Per RFC 7662, an inactive token's response is exactly {@code {"active": false}} — no other claims are leaked for
 * a revoked token.
 */
public class RevocationAwareTokenIntrospectionAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticationProvider delegate;
    private final TokenRevocationService tokenRevocationService;

    public RevocationAwareTokenIntrospectionAuthenticationProvider(AuthenticationProvider delegate,
                                                                   TokenRevocationService tokenRevocationService) {
        this.delegate = delegate;
        this.tokenRevocationService = tokenRevocationService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication result = delegate.authenticate(authentication);
        if (!(result instanceof OAuth2TokenIntrospectionAuthenticationToken introspection)) {
            return result;
        }
        OAuth2TokenIntrospection claims = introspection.getTokenClaims();
        if (claims == null || !claims.isActive()) {
            return result; // SAS already determined the token is inactive
        }
        Instant issuedAt = claims.getIssuedAt();
        if (issuedAt == null) {
            return result; // no iat to compare — leave SAS's response as-is
        }

        UUID tenantId = parseUuid(claims.getClaims().get("tenant_id"));
        UUID userId = parseUuid(claims.getSubject()); // null for machine (client-credentials) tokens
        if (tokenRevocationService.isRevoked(tenantId, userId, issuedAt.getEpochSecond())) {
            Authentication clientPrincipal = (Authentication) introspection.getPrincipal();
            return new OAuth2TokenIntrospectionAuthenticationToken(
                    introspection.getToken(), clientPrincipal, OAuth2TokenIntrospection.builder(false).build());
        }
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
