package com.anterka.closeauthbackend.common.config;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.token.service.RefreshTokenRecordingAuthenticationProvider;
import com.anterka.closeauthbackend.token.service.RefreshTokenRotationService;
import com.anterka.closeauthbackend.token.service.ReplayDetectingRefreshTokenAuthenticationProvider;
import com.anterka.closeauthbackend.token.service.RevocationAwareTokenIntrospectionAuthenticationProvider;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Spring Authorization Server wiring (§7.2). Uses SAS's intended extension points only — no custom filters.
 *
 * <p>Signing keys: the platform-global RSA keypair from {@code closeauth.keys.*} (PEM), wired into a
 * {@link JWKSource}. The {@code kid} is the RFC 7638 thumbprint. The JWKS is built from a <em>list</em> of keys so
 * publishing current + previous (§7.2, for non-disruptive rotation) is just adding the previous key to the list —
 * full rotation tooling is Phase-later; the structure that permits it is here.
 *
 * <p>Issuer: {@code closeauth.issuer-url} (platform issuer). The per-tenant {@code /t/{slug}} issuer pattern (§7.2)
 * is reserved structurally — not enabled here (single platform issuer for MVP).
 */
@Configuration
@Slf4j
public class AuthorizationServerConfig {

    /** SAS endpoints (/oauth2/**, /.well-known/**, /oauth2/jwks, /connect/**, /userinfo). */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            RefreshTokenRotationService rotationService,
            OAuth2AuthorizationService authorizationService,
            TokenRevocationService tokenRevocationService) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults()) // OIDC discovery, UserInfo, ID tokens
                // 4b-i: wrap SAS's token-endpoint providers to add refresh rotation + replay detection.
                .tokenEndpoint(tokenEndpoint -> tokenEndpoint.authenticationProviders(
                        rotationProviders(rotationService, authorizationService)))
                // 4b-ii: wrap the introspection provider to consult the Redis revocation list.
                .tokenIntrospectionEndpoint(introspection -> introspection.authenticationProviders(
                        introspectionProviders(tokenRevocationService)));
        http
                // Redirect unauthenticated browser requests to the (SAS default for now) login page — Stage 6 replaces it.
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // UserInfo / protected OIDC endpoints validate the CloseAuth-issued JWT.
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Replaces SAS's refresh and authorization-code token-endpoint providers with CloseAuth wrappers that add
     * rotation + replay detection (refresh) and family-root recording (authorization code). SAS constructs the
     * providers; we only wrap the existing instances in the list (the framework's intended extension point).
     */
    private Consumer<List<AuthenticationProvider>> rotationProviders(
            RefreshTokenRotationService rotationService, OAuth2AuthorizationService authorizationService) {
        return providers -> {
            for (int i = 0; i < providers.size(); i++) {
                AuthenticationProvider provider = providers.get(i);
                if (provider instanceof OAuth2RefreshTokenAuthenticationProvider) {
                    providers.set(i, new ReplayDetectingRefreshTokenAuthenticationProvider(provider, rotationService));
                } else if (provider instanceof OAuth2AuthorizationCodeAuthenticationProvider) {
                    providers.set(i, new RefreshTokenRecordingAuthenticationProvider(
                            provider, rotationService, authorizationService));
                }
            }
        };
    }

    /** Replaces SAS's introspection provider with a wrapper that consults the Redis revocation list (4b-ii). */
    private Consumer<List<AuthenticationProvider>> introspectionProviders(TokenRevocationService tokenRevocationService) {
        return providers -> {
            for (int i = 0; i < providers.size(); i++) {
                if (providers.get(i) instanceof OAuth2TokenIntrospectionAuthenticationProvider provider) {
                    providers.set(i, new RevocationAwareTokenIntrospectionAuthenticationProvider(
                            provider, tokenRevocationService));
                }
            }
        };
    }

    /** Everything else (login page for the authorization-code flow — SAS default until Stage 6). */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(CloseAuthProperties properties) {
        List<RSAKey> keys = new ArrayList<>();
        keys.add(currentSigningKey(properties));
        // To rotate: unshift the freshly-generated key and keep the previous one here, e.g. keys.add(previousKey).
        return new ImmutableJWKSet<>(new JWKSet(new ArrayList<>(keys)));
    }

    private RSAKey currentSigningKey(CloseAuthProperties properties) {
        RSAPublicKey publicKey = properties.getKeys().getRsaPublicKey();
        RSAPrivateKey privateKey = properties.getKeys().getRsaPrivateKey();
        if (publicKey != null && privateKey != null) {
            return rsaKey(publicKey, privateKey);
        }
        log.warn("No RSA signing keys configured (closeauth.keys.*); generating an EPHEMERAL keypair. "
                + "Tokens will NOT survive a restart — configure persistent keys in every real environment.");
        KeyPair ephemeral = generateEphemeralKeyPair();
        return rsaKey((RSAPublicKey) ephemeral.getPublic(), (RSAPrivateKey) ephemeral.getPrivate());
    }

    private RSAKey rsaKey(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyIDFromThumbprint() // RFC 7638 thumbprint as kid (matches prior behavior)
                    .build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Failed to compute RFC 7638 thumbprint for the signing key", e);
        }
    }

    private static KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate ephemeral RSA keypair", e);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(CloseAuthProperties properties) {
        // Platform issuer for MVP. Per-tenant issuer pattern https://auth.closeauth.io/t/{slug} (§7.2) is
        // reserved structurally and NOT enabled here.
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuerUrl())
                .build();
    }
}
