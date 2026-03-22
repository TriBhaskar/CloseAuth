package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.user.entity.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

/**
 * Stateless JWT token service for user authentication.
 * No database lookups, no OAuth2 client dependencies - just pure JWT generation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtTokenService {

    private static final long TOKEN_VALIDITY_HOURS = 24;

    private final JwtEncoder jwtEncoder;
    private final AuthorizationServerSettings authorizationServerSettings;

    /**
     * Generates a stateless JWT token for the authenticated user.
     * All user information is embedded in the token claims.
     *
     * @param user The user for whom to generate the token
     * @return The generated JWT access token string
     */
    public String generateToken(Users user) {
        log.debug("Generating JWT for user: {}", user.getUsername());

        Instant now = Instant.now();
        Instant expiry = now.plus(TOKEN_VALIDITY_HOURS, ChronoUnit.HOURS);

        // Extract roles from user authorities
        var roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        // Build JWT claims with all necessary user information
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(authorizationServerSettings.getIssuer())
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(expiry)
                .claim("userId", user.getId())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .claim("username", user.getUsername())
                .claim("token_type", "access_token")
                .build();

        // Create JWT header
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();

        // Encode the JWT
        var jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));

        log.info("JWT generated successfully for user: {}", user.getUsername());
        return jwt.getTokenValue();
    }

    /**
     * Overloaded method for backward compatibility.
     * clientId is ignored as it's not needed for stateless user tokens.
     */
    public String generateToken(Users user, String clientId) {
        if (clientId != null) {
            log.debug("ClientId '{}' provided but not used for user token generation", clientId);
        }
        return generateToken(user);
    }

    /**
     * Gets the token expiration time.
     *
     * @return Token expiration instant
     */
    public Instant getTokenExpiration() {
        return Instant.now().plus(TOKEN_VALIDITY_HOURS, ChronoUnit.HOURS);
    }

    /**
     * Overloaded method for backward compatibility.
     * clientId is ignored as token expiration is fixed.
     */
    @SuppressWarnings("unused") // clientId kept for backward compatibility
    public Instant getTokenExpiration(String clientId) {
        return getTokenExpiration();
    }
}
