package com.anterka.closeauthbackend.common.config;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.constants.ApiPaths;
import com.anterka.closeauthbackend.common.filter.TwoLayerAuthenticationFilter;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.anterka.closeauthbackend.common.config.CustomClientMetadataConfig.configureCustomClientMetadataConverters;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AuthorisationServerConfig {

    private final CloseAuthProperties properties;
    private final CorsConfigurationSource corsConfigurationSource;

    public AuthorisationServerConfig(CloseAuthProperties properties, CorsConfigurationSource corsConfigurationSource) {
        this.properties = properties;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    /**
     * Filter Chain 1: OAuth2 Authorization Server endpoints
     * Handles: /oauth2/**, /connect/register (OIDC dynamic client registration), /.well-known/**
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();

        http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, authorizationServer -> authorizationServer
                        .authorizationEndpoint(authorizationEndpoint -> authorizationEndpoint
                                .consentPage(properties.getBff().getConsentPage()))
                        .oidc(oidc -> oidc
                                .clientRegistrationEndpoint(clientRegistrationEndpoint -> clientRegistrationEndpoint
                                        .authenticationProviders(configureCustomClientMetadataConverters()))))
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(properties.getBff().getLoginPage())))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Filter Chain 2: Admin authentication endpoints
     * Handles: /api/v1/admin/auth/** (register, login, verify-email, etc.)
     * These endpoints require OAuth2 Bearer token (BFF always sends it)
     * but do NOT require X-User-Token (these endpoints establish user identity).
     */
    @Bean
    @Order(2)
    public SecurityFilterChain adminAuthEndpointsSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(ApiPaths.ADMIN_AUTH_ENDPOINTS)
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((req, res, authEx) -> {
                            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"error\":\"Unauthorized\"," +
                                            "\"message\":\"Valid OAuth2 Bearer token required\"}"
                            );
                        }))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * Filter Chain 3: Dual authentication endpoints
     * Handles: /api/v1/clients/** (client configuration management)
     * Requires BOTH OAuth2 Bearer token AND X-User-Token header.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain dualAuthEndpointsSecurityFilterChain(
            HttpSecurity http,
            TwoLayerAuthenticationFilter twoLayerAuthenticationFilter) throws Exception {

        http.securityMatcher(ApiPaths.CLIENT_CONFIG_BASE + "/**")
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterBefore(twoLayerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((req, res, authEx) -> {
                            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"error\":\"Unauthorized\"," +
                                            "\"message\":\"Dual authentication required: OAuth2 Bearer token AND X-User-Token header\"}"
                            );
                        }))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * Filter Chain 4: Default security chain for any remaining endpoints.
     */
    @Bean
    @Order(4)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage(properties.getBff().getLoginPage())
                        .loginProcessingUrl("/login")
                        .permitAll())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            Authentication principal = context.getPrincipal();
            if (context.getTokenType().getValue().equals("id_token")) {
                context.getClaims().claim("token_type", "id token");
            }
            if (context.getTokenType().getValue().equals("access_token")) {
                context.getClaims().claim("token_type", "access token");
                Set<String> roles = principal.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());
                context.getClaims().claim("roles", roles).claim("username", principal.getName());
            }
        };
    }

    /**
     * JWK Source using persisted RSA keys from PEM files.
     * Keys survive application restarts — tokens remain valid.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaKey = new RSAKey.Builder(properties.getKeys().getRsaPublicKey())
                .privateKey(properties.getKeys().getRsaPrivateKey())
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuerUrl())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public OAuth2TokenGenerator<OAuth2Token> tokenGenerator(JWKSource<SecurityContext> jwkSource) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(tokenCustomizer());
        OAuth2AccessTokenGenerator accessTokenGenerator = new OAuth2AccessTokenGenerator();
        OAuth2RefreshTokenGenerator refreshTokenGenerator = new OAuth2RefreshTokenGenerator();
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator, accessTokenGenerator, refreshTokenGenerator);
    }

    @Bean
    public TwoLayerAuthenticationFilter twoLayerAuthenticationFilter(JwtDecoder jwtDecoder) {
        return new TwoLayerAuthenticationFilter(jwtDecoder);
    }

    @Bean
    public FilterRegistrationBean<TwoLayerAuthenticationFilter> twoLayerAuthenticationFilterRegistration(
            TwoLayerAuthenticationFilter filter) {
        FilterRegistrationBean<TwoLayerAuthenticationFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false); // prevent global servlet registration
        return reg;
    }
}
