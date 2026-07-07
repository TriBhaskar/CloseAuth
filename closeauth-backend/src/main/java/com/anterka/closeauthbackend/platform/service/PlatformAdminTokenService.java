package com.anterka.closeauthbackend.platform.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mints the platform-admin access token (§7.8, Stage 7a) — the <b>third token shape</b> alongside 4a's user and M2M
 * tokens. Minted directly over the platform signing key (not via SAS's grant flow, because platform admins have no
 * registered OAuth client, and {@code oauth2_registered_client.tenant_id} is NOT NULL — see STAGE_7A_REPORT.md §token).
 * The result is an ordinary CloseAuth JWT that the resource server validates with the same {@code JwtDecoder}.
 *
 * <p><b>The platform token shape:</b> {@code sub} = platform_admin UUID, {@code roles} = its platform roles, and
 * crucially <b>NO {@code tenant_id} claim</b> — its ABSENCE is the signal "platform-level, not tenant-scoped". A
 * {@code token_use=platform_admin} marker is added for explicitness. This shape is built HERE (not in
 * {@code CloseAuthTokenCustomizer}, which stamps the SAS-flow user/M2M tokens).
 */
@Service
@RequiredArgsConstructor
public class PlatformAdminTokenService {

    private final JwtEncoder jwtEncoder;
    private final PlatformAdminService platformAdminService;
    private final CloseAuthProperties properties;

    /**
     * Mints a signed access token for the (already-authenticated) platform admin.
     *
     * <p><b>Access token ONLY — deliberately NO refresh token.</b> The platform admin is the highest-privilege
     * principal (cross-tenant reach), so it gets the shortest-lived credential with no renewal: a compromised token
     * dies at its short TTL with nothing to extend it, and no long-lived platform-admin credential ever exists. Staff
     * re-authenticate when the token expires. Adding refresh here later is a NON-BREAKING future option (mint a refresh
     * token alongside + a {@code /v1/platform/auth/refresh} endpoint) if staff re-auth friction proves unworkable —
     * revisit only then.
     *
     * <p><b>Keep this claim set compatible with {@link com.anterka.closeauthbackend.token.config.CloseAuthTokenCustomizer}
     * / SAS's token shape.</b> This is the ONE CloseAuth token minted OUTSIDE SAS's grant flow, so it can silently
     * drift from the SAS-issued user/M2M shape. If SAS's claim contract changes (claim names, {@code roles} structure,
     * validators, revocation keys), update this mint in lockstep — nothing else couples the two.
     */
    public MintedToken mintFor(UUID adminId) {
        List<String> roles = platformAdminService.resolveRoleNames(adminId);
        Instant now = Instant.now();
        Duration ttl = properties.getPlatformAdmin().getTokenTtl();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuerUrl())
                .subject(adminId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                // Mutable list (D1/4b-ii lesson) — though not persisted through SAS's mapper here, keep the convention.
                .claim("roles", new ArrayList<>(roles))
                .claim("token_use", "platform_admin")
                // Deliberately NO tenant_id: its absence marks a platform-level (not tenant-scoped) token.
                .build();

        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build(), claims));
        return new MintedToken(jwt.getTokenValue(), ttl.toSeconds());
    }

    /** A minted access token + its lifetime (seconds), for the {@code {access_token, token_type, expires_in}} response. */
    public record MintedToken(String accessToken, long expiresInSeconds) {
    }
}
