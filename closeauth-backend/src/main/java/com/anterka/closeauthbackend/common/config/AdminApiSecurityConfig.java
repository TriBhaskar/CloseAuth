package com.anterka.closeauthbackend.common.config;

import com.anterka.closeauthbackend.admin.security.PlatformAdminRevocationTokenValidator;
import com.anterka.closeauthbackend.common.web.ProblemDetailAuthEntryPoints;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The admin API ({@code /v1/**}) security (§7.8, Stage 7a) — a stateless OAuth2 resource server validating
 * CloseAuth-issued JWT bearer tokens with the same {@link JwtDecoder} as the rest of the platform. Ordered BEFORE the
 * Authorization Server chain so {@code /v1/**} is handled here; non-{@code /v1} requests fall through (securityMatcher).
 *
 * <p>{@code @EnableMethodSecurity} turns on the {@code @PreAuthorize}-based {@link
 * com.anterka.closeauthbackend.admin.security.RequiresPlatformAdmin} / {@code RequiresTenantAccess} gates. Auth
 * failures render as RFC 7807: a missing/invalid token → 401, a role denial → 403 (via the resource-server handlers
 * and {@code ApiExceptionHandler}).
 *
 * <p>{@code /v1/platform/auth/**} is permitAll — it is the platform-admin token-mint endpoint, which authenticates via
 * body credentials (there is no bearer token yet).
 */
@Configuration
@EnableMethodSecurity
public class AdminApiSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain adminApiFilterChain(HttpSecurity http,
                                                   @org.springframework.beans.factory.annotation.Qualifier("adminApiJwtDecoder")
                                                   JwtDecoder adminApiJwtDecoder,
                                                   ObjectMapper objectMapper) throws Exception {
        http
                .securityMatcher("/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/platform/auth/**").permitAll() // token mint (authenticates via body creds)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(adminApiJwtDecoder))
                        .authenticationEntryPoint(ProblemDetailAuthEntryPoints.authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(ProblemDetailAuthEntryPoints.accessDeniedHandler(objectMapper)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(ProblemDetailAuthEntryPoints.authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(ProblemDetailAuthEntryPoints.accessDeniedHandler(objectMapper)));
        return http.build();
    }

    /**
     * The {@link JwtDecoder} for the admin chain — same signature+expiry validation as the platform-wide decoder, PLUS
     * a {@link PlatformAdminRevocationTokenValidator} so {@code /v1/**} rejects a revoked platform-admin token within
     * the token TTL (§7.8). Distinct from the {@code @Primary} platform decoder (which the SAS/OIDC chains use) so this
     * per-request revocation read is scoped to the admin API only.
     */
    @Bean
    public JwtDecoder adminApiJwtDecoder(JWKSource<SecurityContext> jwkSource,
                                         TokenRevocationService tokenRevocationService) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefault(), // the default timestamp validator (signature is validated separately)
                new PlatformAdminRevocationTokenValidator(tokenRevocationService)));
        return decoder;
    }
}
