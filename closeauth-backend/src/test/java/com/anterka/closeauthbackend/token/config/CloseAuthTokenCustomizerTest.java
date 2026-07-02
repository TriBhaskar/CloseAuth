package com.anterka.closeauthbackend.token.config;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import com.anterka.closeauthbackend.rbac.dto.ResolvedAuthorization;
import com.anterka.closeauthbackend.rbac.dto.ResolvedAuthorization.AppRoleGrant;
import com.anterka.closeauthbackend.rbac.service.PrincipalAuthorizationService;
import com.anterka.closeauthbackend.resourceserver.entity.ClientAuthorizedResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.repository.ClientAuthorizedResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloseAuthTokenCustomizerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID RS_ID = UUID.randomUUID();
    private static final String AUDIENCE = "https://acme.rs.closeauth.io/todomaster-api";

    private PrincipalAuthorizationService principalAuthorizationService;
    private ClientAuthorizedResourceServerRepository clientAuthRepo;
    private ResourceServerRepository resourceServerRepository;
    private CloseAuthTokenCustomizer customizer;

    @BeforeEach
    void setUp() {
        principalAuthorizationService = mock(PrincipalAuthorizationService.class);
        clientAuthRepo = mock(ClientAuthorizedResourceServerRepository.class);
        resourceServerRepository = mock(ResourceServerRepository.class);
        customizer = new CloseAuthTokenCustomizer(principalAuthorizationService, clientAuthRepo, resourceServerRepository);

        // The client is authorized for one Resource Server whose audience is a URI.
        ClientAuthorizedResourceServer link = new ClientAuthorizedResourceServer();
        link.setResourceServerId(RS_ID);
        when(clientAuthRepo.findByClientRegisteredId("client-internal-id")).thenReturn(List.of(link));
        ResourceServer rs = new ResourceServer();
        rs.setId(RS_ID);
        rs.setSlug("todomaster-api");
        rs.setAudienceIdentifier(AUDIENCE);
        when(resourceServerRepository.findAllById(any())).thenReturn(List.of(rs));
    }

    private RegisteredClient client(AuthorizationGrantType grant) {
        RegisteredClient.Builder builder = RegisteredClient.withId("client-internal-id")
                .clientId("client-x")
                .clientName("Client X")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(grant)
                .scope("read")
                .clientSettings(CloseAuthClientSettings.withTenantId(ClientSettings.builder(), TENANT).build());
        if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(grant)) {
            builder.redirectUri("https://app.example/callback");
        }
        return builder.build();
    }

    private JwtEncodingContext context(RegisteredClient client, OAuth2TokenType tokenType,
                                       AuthorizationGrantType grant, String principalName, Set<String> authorizedScopes,
                                       JwtClaimsSet.Builder claims) {
        JwtEncodingContext ctx = mock(JwtEncodingContext.class);
        when(ctx.getRegisteredClient()).thenReturn(client);
        when(ctx.getClaims()).thenReturn(claims);
        when(ctx.getTokenType()).thenReturn(tokenType);
        when(ctx.getAuthorizationGrantType()).thenReturn(grant);
        when(ctx.getAuthorizedScopes()).thenReturn(authorizedScopes);
        Authentication principal = mock(Authentication.class);
        when(principal.getName()).thenReturn(principalName);
        when(ctx.<Authentication>getPrincipal()).thenReturn(principal);
        return ctx;
    }

    @Test
    void userAccessTokenStampsSubUuidAudienceUriAndSlugPrefixedScopes() {
        UUID userId = UUID.randomUUID();
        when(principalAuthorizationService.resolve(any(), eq(userId))).thenReturn(new ResolvedAuthorization(
                List.of(),                                   // platform roles
                List.of("TENANT_MEMBER"),                    // tenant roles
                List.of(new AppRoleGrant(AUDIENCE, List.of("EDITOR"))),
                List.of("todomaster-api:read")));            // slug-prefixed scope

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("https://auth.example").subject("placeholder");
        customizer.customize(context(client(AuthorizationGrantType.AUTHORIZATION_CODE), OAuth2TokenType.ACCESS_TOKEN,
                AuthorizationGrantType.AUTHORIZATION_CODE, userId.toString(), Set.of("openid", "profile"), claims));
        JwtClaimsSet result = claims.build();

        assertThat(result.getSubject()).isEqualTo(userId.toString());              // sub = user UUID, not username
        assertThat(result.getClaimAsString("tenant_id")).isEqualTo(TENANT.toString());
        assertThat(result.getAudience()).containsExactly(AUDIENCE);                 // aud = RS audience URI
        assertThat(result.getAudience()).doesNotContain("client-x");               // NOT the client_id
        assertThat(result.getClaimAsString("client_id")).isEqualTo("client-x");
        assertThat(result.getClaimAsString("idp")).isEqualTo("LOCAL_PASSWORD");
        assertThat(result.getClaimAsStringList("tenant_roles")).containsExactly("TENANT_MEMBER");
        String scope = result.getClaimAsString("scope");
        assertThat(scope).contains("openid", "profile", "todomaster-api:read");     // slug prefix, not audience
        assertThat(scope).doesNotContain("https://");
    }

    @Test
    void machineToMachineTokenHasNoUserRolesAndTargetsResourceServerAudience() {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("https://auth.example").subject("client-x");
        customizer.customize(context(client(AuthorizationGrantType.CLIENT_CREDENTIALS), OAuth2TokenType.ACCESS_TOKEN,
                AuthorizationGrantType.CLIENT_CREDENTIALS, "client-x", Set.of("read"), claims));
        JwtClaimsSet result = claims.build();

        assertThat(result.getClaimAsString("tenant_id")).isEqualTo(TENANT.toString());
        assertThat(result.getAudience()).containsExactly(AUDIENCE);
        assertThat(result.getClaimAsString("client_id")).isEqualTo("client-x");
        assertThat(result.getClaimAsString("scope")).isEqualTo("read");
        assertThat(result.getClaim("tenant_roles")).isNull();   // no user roles for M2M
        assertThat(result.getClaim("app_roles")).isNull();
        assertThat(result.getClaim("idp")).isNull();
    }

    @Test
    void idTokenCarriesIdentityOnlyNoAuthorizationClaims() {
        UUID userId = UUID.randomUUID();
        OAuth2TokenType idToken = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("https://auth.example").subject("placeholder");
        customizer.customize(context(client(AuthorizationGrantType.AUTHORIZATION_CODE), idToken,
                AuthorizationGrantType.AUTHORIZATION_CODE, userId.toString(), Set.of("openid"), claims));
        JwtClaimsSet result = claims.build();

        assertThat(result.getSubject()).isEqualTo(userId.toString());
        assertThat(result.getClaimAsString("tenant_id")).isEqualTo(TENANT.toString());
        assertThat(result.getClaim("scope")).isNull();          // ID token excludes authorization claims
        assertThat(result.getClaim("app_roles")).isNull();
        assertThat(result.getClaim("tenant_roles")).isNull();
    }
}
