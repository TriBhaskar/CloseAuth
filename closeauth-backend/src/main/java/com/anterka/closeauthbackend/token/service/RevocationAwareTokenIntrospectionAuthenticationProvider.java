package com.anterka.closeauthbackend.token.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
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
 * <p><b>Platform-admin tokens (§7.8)</b> are minted OUTSIDE SAS's grant flow, so they have no authorization record and
 * SAS's delegate always reports them inactive. This wrapper therefore recognizes them itself: when the delegate says
 * "inactive", it tries to decode the token and, if it is a valid-signature, unexpired {@code token_use=platform_admin}
 * token whose {@code sub} has no platform-admin revocation marker, reports it <b>active</b> (built from signature +
 * claims + revocation-list, NOT a SAS store record). A revoked/expired/invalid one stays inactive.
 *
 * <p>Per RFC 7662, an inactive token's response is exactly {@code {"active": false}} — no other claims are leaked for
 * a revoked token.
 */
public class RevocationAwareTokenIntrospectionAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticationProvider delegate;
    private final TokenRevocationService tokenRevocationService;
    private final JwtDecoder jwtDecoder;
    private final AuditEmitter auditEmitter;

    public RevocationAwareTokenIntrospectionAuthenticationProvider(AuthenticationProvider delegate,
                                                                   TokenRevocationService tokenRevocationService,
                                                                   JwtDecoder jwtDecoder,
                                                                   AuditEmitter auditEmitter) {
        this.delegate = delegate;
        this.tokenRevocationService = tokenRevocationService;
        this.jwtDecoder = jwtDecoder;
        this.auditEmitter = auditEmitter;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication result = delegate.authenticate(authentication);
        if (!(result instanceof OAuth2TokenIntrospectionAuthenticationToken introspection)) {
            return result;
        }
        OAuth2TokenIntrospection claims = introspection.getTokenClaims();
        if (claims == null || !claims.isActive()) {
            // SAS says inactive. It may be a platform-admin token (no SAS store record) — interpret it ourselves.
            return interpretPlatformAdminToken(introspection, result);
        }
        Instant issuedAt = claims.getIssuedAt();
        if (issuedAt == null) {
            return result; // no iat to compare — leave SAS's response as-is
        }

        UUID tenantId = parseUuid(claims.getClaims().get("tenant_id"));
        UUID userId = parseUuid(claims.getSubject()); // null for machine (client-credentials) tokens
        if (tokenRevocationService.isRevoked(tenantId, userId, issuedAt.getEpochSecond())) {
            auditEmitter.emit(AuditEvents.tokenIntrospectedRevoked(tenantId, userId, "USER"));
            return inactive(introspection);
        }
        return result;
    }

    /**
     * Interprets an otherwise-inactive introspection result as a platform-admin token: valid signature + unexpired +
     * {@code token_use=platform_admin} + not revoked ⇒ active. Anything else falls through to the SAS inactive result.
     */
    private Authentication interpretPlatformAdminToken(OAuth2TokenIntrospectionAuthenticationToken introspection,
                                                       Authentication original) {
        String tokenValue = introspection.getToken();
        if (tokenValue == null) {
            return original;
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(tokenValue); // validates signature + expiry
        } catch (JwtException invalidOrExpired) {
            return original; // genuinely inactive (bad signature / expired / malformed)
        }
        if (!"platform_admin".equals(jwt.getClaimAsString("token_use"))) {
            return original; // not a platform-admin token — respect SAS's inactive verdict
        }
        UUID adminId = parseUuid(jwt.getSubject());
        Instant issuedAt = jwt.getIssuedAt();
        if (adminId == null || issuedAt == null) {
            return original;
        }
        if (tokenRevocationService.isPlatformAdminRevoked(adminId, issuedAt.getEpochSecond())) {
            auditEmitter.emit(AuditEvents.tokenIntrospectedRevoked(null, null, "PLATFORM_ADMIN"));
            return inactive(introspection); // revoked ⇒ active:false, no claim leakage
        }
        OAuth2TokenIntrospection active = OAuth2TokenIntrospection.builder(true)
                .tokenType(OAuth2AccessToken.TokenType.BEARER.getValue())
                .subject(adminId.toString())
                .issuedAt(issuedAt)
                .expiresAt(jwt.getExpiresAt())
                .claim("token_use", "platform_admin")
                .build();
        return new OAuth2TokenIntrospectionAuthenticationToken(
                tokenValue, (Authentication) introspection.getPrincipal(), active);
    }

    private OAuth2TokenIntrospectionAuthenticationToken inactive(OAuth2TokenIntrospectionAuthenticationToken source) {
        return new OAuth2TokenIntrospectionAuthenticationToken(
                source.getToken(), (Authentication) source.getPrincipal(), OAuth2TokenIntrospection.builder(false).build());
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
