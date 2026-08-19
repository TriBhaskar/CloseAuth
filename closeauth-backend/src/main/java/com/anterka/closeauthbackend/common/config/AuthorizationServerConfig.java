package com.anterka.closeauthbackend.common.config;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.ConsentScopeResolver;
import com.anterka.closeauthbackend.auth.web.LoginSuccessResponder;
import com.anterka.closeauthbackend.auth.web.TenantAwareLoginRedirectEntryPoint;
import com.anterka.closeauthbackend.auth.web.TenantSessionSsoFilter;
import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
            TokenRevocationService tokenRevocationService,
            AuthServerSessionService sessionService,
            TenantSessionSsoFilter tenantSessionSsoFilter,
            ConsentScopeResolver consentScopeResolver,
            JwtDecoder jwtDecoder,
            com.anterka.closeauthbackend.audit.service.AuditEmitter auditEmitter,
            AuthFlowTenantResolver tenantResolver,
            CloseAuthProperties properties) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults()) // OIDC discovery, UserInfo, ID tokens, RP-initiated logout endpoint
                // 6b-ii: consent screen. Non-trusted clients are redirected to the CloseAuth consent page; the
                // auto-grant customizer approves requires_consent=false scopes without an explicit checkbox.
                .authorizationEndpoint(authorizationEndpoint -> authorizationEndpoint
                        // Absolute BFF-hosted consent page (cross-origin login continuity — see
                        // CLOSEAUTH_CONSENT_CROSS_ORIGIN_DESIGN.md §1/§5): SAS already appends client_id/scope/state
                        // onto whatever base is configured here, same as it did for the relative literal this
                        // replaces — no custom entry-point override needed, unlike login's fix.
                        .consentPage(properties.getBff().getConsentPage())
                        .authenticationProviders(consentAutoGrantProviders(consentScopeResolver)))
                // 4b-i: wrap SAS's token-endpoint providers to add refresh rotation + replay detection.
                .tokenEndpoint(tokenEndpoint -> tokenEndpoint.authenticationProviders(
                        rotationProviders(rotationService, authorizationService, sessionService)))
                // 4b-ii: wrap the introspection provider to consult the Redis revocation list.
                .tokenIntrospectionEndpoint(introspection -> introspection.authenticationProviders(
                        introspectionProviders(tokenRevocationService, jwtDecoder, auditEmitter)));
        http
                // 6a: consult the tenant-scoped Auth Server session on /oauth2/authorize (SSO). Placed right after
                // SecurityContextHolderFilter (which loads the — anonymous — context) so a recognized session
                // populates the SecurityContext BEFORE SAS's authorization endpoint filter reads the principal;
                // otherwise the endpoint (which runs early, before RequestCacheAwareFilter and AnonymousAuthentication
                // Filter) would see no principal and never skip login. SAS's own filters can't be used as an
                // addFilterBefore reference (no registered order), so we anchor to the standard SecurityContextHolderFilter.
                .addFilterAfter(tenantSessionSsoFilter, SecurityContextHolderFilter.class)
                // Redirect unauthenticated browser requests to the CloseAuth-hosted login page on the BFF's own
                // origin (6a's LoginController is reached via the BFF; see crossOriginLoginEntryPoint below —
                // cross-origin login continuity design).
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        crossOriginLoginEntryPoint(tenantResolver, properties),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // UserInfo / protected OIDC endpoints validate the CloseAuth-issued JWT.
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * BE-B: the unauthenticated-entry-point redirect target for a browser hitting a protected endpoint — the
     * tenant-namespaced hosted login page (see {@link TenantAwareLoginRedirectEntryPoint} for the resolution logic
     * and the cross-origin-continuity rationale).
     */
    private LoginUrlAuthenticationEntryPoint crossOriginLoginEntryPoint(
            AuthFlowTenantResolver tenantResolver, CloseAuthProperties properties) {
        return new TenantAwareLoginRedirectEntryPoint(tenantResolver, properties);
    }

    /**
     * Replaces SAS's refresh and authorization-code token-endpoint providers with CloseAuth wrappers that add
     * rotation + replay detection (refresh) and family-root recording (authorization code). SAS constructs the
     * providers; we only wrap the existing instances in the list (the framework's intended extension point).
     */
    private Consumer<List<AuthenticationProvider>> rotationProviders(
            RefreshTokenRotationService rotationService, OAuth2AuthorizationService authorizationService,
            AuthServerSessionService sessionService) {
        return providers -> {
            for (int i = 0; i < providers.size(); i++) {
                AuthenticationProvider provider = providers.get(i);
                if (provider instanceof OAuth2RefreshTokenAuthenticationProvider) {
                    providers.set(i, new ReplayDetectingRefreshTokenAuthenticationProvider(provider, rotationService));
                } else if (provider instanceof OAuth2AuthorizationCodeAuthenticationProvider) {
                    providers.set(i, new RefreshTokenRecordingAuthenticationProvider(
                            provider, rotationService, authorizationService, sessionService));
                }
            }
        };
    }

    /**
     * The {@link TenantSessionSsoFilter} is a {@code @Component} so Spring injects its dependencies, but it must run
     * ONLY inside the Authorization Server security chain (added via {@code addFilterBefore} above), not as a global
     * servlet filter on every request. Disabling its Boot auto-registration prevents the duplicate top-level filter.
     */
    @Bean
    public FilterRegistrationBean<TenantSessionSsoFilter> tenantSessionSsoFilterRegistration(
            TenantSessionSsoFilter filter) {
        FilterRegistrationBean<TenantSessionSsoFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Customizes SAS's consent provider (6b-ii): during consent processing, auto-approve every requested scope whose
     * RS-catalog {@code requires_consent = false} — so those scopes are granted without the user having to check a box,
     * while {@code requires_consent = true} scopes still need the user's explicit approval (submitted from the consent
     * page). This is backend-enforced (not merely UI-honored). The per-client trusted flag governs whether the consent
     * page shows at ALL; this governs auto-grant WITHIN a shown page.
     */
    private Consumer<List<AuthenticationProvider>> consentAutoGrantProviders(ConsentScopeResolver consentScopeResolver) {
        return providers -> {
            for (AuthenticationProvider provider : providers) {
                if (provider instanceof OAuth2AuthorizationConsentAuthenticationProvider consentProvider) {
                    consentProvider.setAuthorizationConsentCustomizer(context -> {
                        OAuth2AuthorizationConsentAuthenticationToken consentAuthentication = context.getAuthentication();
                        // A deny submits NO approved scopes → do NOT auto-grant; let SAS return access_denied and
                        // issue nothing ("deny must actually deny"). Only auto-grant when the user is approving.
                        if (consentAuthentication.getScopes().isEmpty()) {
                            return;
                        }
                        RegisteredClient client = context.getRegisteredClient();
                        UUID tenantId = CloseAuthClientSettings.getTenantId(client);
                        if (tenantId == null) {
                            return;
                        }
                        OAuth2AuthorizationConsent.Builder consentBuilder = context.getAuthorizationConsent();
                        OAuth2AuthorizationRequest authorizationRequest = context.getAuthorizationRequest();
                        // Auto-approve requires_consent=false scopes: added to the consent record, so the user is
                        // never prompted for them and they are granted on this + subsequent authorizations.
                        for (String scope : authorizationRequest.getScopes()) {
                            if (consentScopeResolver.isAutoGrantable(tenantId, scope)) {
                                consentBuilder.scope(scope);
                            }
                        }
                    });
                }
            }
        };
    }

    /**
     * Replaces SAS's introspection provider with a wrapper that consults the Redis revocation list (4b-ii) and also
     * recognizes directly-minted platform-admin tokens (7a — no SAS store record; the {@code jwtDecoder} lets the
     * wrapper validate them by signature + claims + revocation).
     */
    private Consumer<List<AuthenticationProvider>> introspectionProviders(TokenRevocationService tokenRevocationService,
                                                                          JwtDecoder jwtDecoder,
                                                                          com.anterka.closeauthbackend.audit.service.AuditEmitter auditEmitter) {
        return providers -> {
            for (int i = 0; i < providers.size(); i++) {
                if (providers.get(i) instanceof OAuth2TokenIntrospectionAuthenticationProvider provider) {
                    providers.set(i, new RevocationAwareTokenIntrospectionAuthenticationProvider(
                            provider, tokenRevocationService, jwtDecoder, auditEmitter));
                }
            }
        };
    }

    /**
     * Everything outside SAS's endpoints — notably the 6a custom auth endpoints ({@code /login}, {@code /logout},
     * handled by the controllers) which must be reachable without authentication. Unauthenticated browser requests to
     * anything else are redirected to the login endpoint.
     *
     * <p>CSRF is disabled on this chain: {@code /login} and {@code /logout} are the custom auth endpoints and there is
     * no ambient session-cookie authentication for them to protect against forgery (the SSO session cookie is
     * {@code SameSite=Lax}); a production hosted UI would add CSRF tokens. TODO(6b/UI): CSRF tokens on the login page.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(
            HttpSecurity http, AuthFlowTenantResolver tenantResolver, CloseAuthProperties properties)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // Disable Spring Security's default LogoutFilter so POST /logout reaches the 6a LogoutController
                // (which runs the tenant-scoped four-leg revoke cascade) instead of the framework's servlet-session logout.
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        // 6a auth endpoints + 6b-i token-based identity flows + 6b-ii public pages. All are
                        // unauthenticated entry points (the consent page + branding render pre-/around authentication);
                        // they enforce tenant-scoping internally and expose only non-sensitive data.
                        .requestMatchers("/login", "/logout", "/error", "/actuator/**",
                                "/register", "/verify-email/**", "/magic-link/**", "/password-reset/**",
                                "/password-rotation/**", "/branding", "/oauth2/consent",
                                "/entry/resolve").permitAll()
                        .anyRequest().authenticated())
                // Same cross-origin-safe redirect as the @Order(1) chain above (this chain guards everything else via
                // anyRequest().authenticated()) — must stay consistent, else this path would relocate the same bug.
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        crossOriginLoginEntryPoint(tenantResolver, properties),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
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

    // @Primary: the platform-wide decoder used by the SAS/OIDC resource-server chains and everywhere a JwtDecoder is
    // injected by type. The admin chain deliberately uses a SECOND decoder (adminApiJwtDecoder) that adds per-request
    // platform-admin revocation; @Primary keeps that second bean from making this injection ambiguous.
    @Bean
    @org.springframework.context.annotation.Primary
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Signs directly-minted CloseAuth JWTs over the same platform key (7a: the platform-admin access token, minted
     * outside SAS's grant flow because platform admins have no registered OAuth client — see STAGE_7A_REPORT.md). The
     * resulting token validates against the same {@link #jwtDecoder} as any CloseAuth token.
     */
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
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
