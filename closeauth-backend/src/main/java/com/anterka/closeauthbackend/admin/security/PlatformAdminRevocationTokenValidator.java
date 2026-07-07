package com.anterka.closeauthbackend.admin.security;

import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

/**
 * Makes the admin resource-server chain ({@code /v1/**}) enforce platform-admin revocation PER REQUEST (§7.8) — so a
 * suspended/compromised platform-admin token is <b>rejected within the token TTL</b>, not merely at expiry. Introspection
 * covers external consumers; this covers the admin API itself (which validates JWTs locally and would otherwise never
 * consult the revocation list). Runs as an extra {@link OAuth2TokenValidator} on the admin {@code JwtDecoder}, after the
 * default signature/expiry validators.
 *
 * <p>Only platform-admin tokens ({@code token_use=platform_admin}) are checked here; every other token (user/M2M) is
 * passed through untouched — their revocation is the tenant/user-scoped concern of {@code /oauth2/introspect} (4b-ii).
 * The revocation read is <b>fail-open</b> (a Redis outage degrades to plain JWT validity = the short TTL), consistent
 * with 4b-ii: a store blip must not become an admin-API outage.
 */
public class PlatformAdminRevocationTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED = new OAuth2Error(
            "invalid_token", "The platform-admin token has been revoked", null);

    private final TokenRevocationService tokenRevocationService;

    public PlatformAdminRevocationTokenValidator(TokenRevocationService tokenRevocationService) {
        this.tokenRevocationService = tokenRevocationService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!"platform_admin".equals(jwt.getClaimAsString("token_use"))) {
            return OAuth2TokenValidatorResult.success(); // not a platform token — nothing to enforce here
        }
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null) {
            return OAuth2TokenValidatorResult.success(); // no iat to compare — leave to the other validators
        }
        UUID adminId;
        try {
            adminId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException notAUuid) {
            return OAuth2TokenValidatorResult.success();
        }
        if (tokenRevocationService.isPlatformAdminRevoked(adminId, issuedAt.getEpochSecond())) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
