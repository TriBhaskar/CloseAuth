package com.anterka.closeauthbackend.token.config;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.rbac.dto.ResolvedAuthorization;
import com.anterka.closeauthbackend.rbac.service.PrincipalAuthorizationService;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.repository.ClientAuthorizedResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stamps the CloseAuth §12 claim set onto issued JWTs. Sources authorization material from
 * {@link PrincipalAuthorizationService#resolve} (never recomputing roles/scopes here).
 *
 * <p>Correctness points (§12, and decisions confirmed in 3c-ii):
 * <ul>
 *   <li><b>{@code sub} = the user's UUID</b>, never username/email (stable opaque id). The old code's
 *       {@code sub=username} was a bug. For user tokens the principal name is expected to be the user UUID
 *       (Stage 6 login sets it); we set {@code sub} explicitly from it.</li>
 *   <li><b>{@code aud} = the Resource Server audience URI(s)</b> the client targets, never the {@code client_id}.
 *       Resolved from the client's authorized resource servers.</li>
 *   <li><b>Scope prefixes use the RS slug</b> ({@code todomaster-api:read}), while {@code aud}/{@code app_roles[].rs}
 *       use the audience URI — {@code resolve()} already produces slug-prefixed scopes; used as-is.</li>
 *   <li><b>ID token excludes authorization claims</b> (no scope/roles) — identity only, plus {@code tenant_id}.</li>
 *   <li><b>M2M (client_credentials) differs</b>: the client acts as itself — {@code sub}=client, no
 *       tenant/app roles, scopes are the client's own grants.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CloseAuthTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    // TODO(stage-6): source `idp` from the user_identities row actually used to authenticate. Until the login
    // flow passes it through, user tokens default to LOCAL_PASSWORD.
    private static final String DEFAULT_IDP = "LOCAL_PASSWORD";

    private final PrincipalAuthorizationService principalAuthorizationService;
    private final ClientAuthorizedResourceServerRepository clientAuthorizedResourceServerRepository;
    private final ResourceServerRepository resourceServerRepository;

    @Override
    public void customize(JwtEncodingContext context) {
        RegisteredClient client = context.getRegisteredClient();
        UUID tenantId = CloseAuthClientSettings.getTenantId(client);
        JwtClaimsSet.Builder claims = context.getClaims();

        // Every CloseAuth token carries tenant context (§11).
        if (tenantId != null) {
            claims.claim("tenant_id", tenantId.toString());
        }

        String tokenTypeValue = context.getTokenType() == null ? null : context.getTokenType().getValue();
        boolean isIdToken = OidcParameterNames.ID_TOKEN.equals(tokenTypeValue);
        boolean isAccessToken = OAuth2TokenType.ACCESS_TOKEN.getValue().equals(tokenTypeValue);

        if (isIdToken) {
            // Identity only: tenant_id (above) + a stable-UUID sub. No scope/roles.
            parseUserId(context).ifPresent(userId -> claims.subject(userId.toString()));
            return;
        }
        if (!isAccessToken) {
            return; // refresh tokens etc. — nothing to stamp
        }

        // ---- Access token ----
        claims.claim("client_id", client.getClientId());

        List<String> audiences = resolveAudiences(client.getId());
        if (!audiences.isEmpty()) {
            claims.audience(audiences); // replaces SAS's default aud=[client_id]
        }

        boolean isM2M = AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType());
        if (isM2M) {
            stampMachineToMachine(context, claims);
        } else {
            stampUser(context, claims, tenantId);
        }
    }

    /** M2M: the client acts as itself. sub=client (SAS default), scopes = the client's own grants, no user roles. */
    private void stampMachineToMachine(JwtEncodingContext context, JwtClaimsSet.Builder claims) {
        claims.claim("scope", spaceDelimited(context.getAuthorizedScopes()));
        // no tenant_roles / app_roles / roles / idp for a machine principal
    }

    private void stampUser(JwtEncodingContext context, JwtClaimsSet.Builder claims, UUID tenantId) {
        var maybeUserId = parseUserId(context);
        if (maybeUserId.isEmpty() || tenantId == null) {
            // No CloseAuth user resolvable yet (e.g. before the Stage 6 login flow sets sub=user UUID).
            // Leave SAS defaults + the raw requested scopes; do not fabricate roles.
            claims.claim("scope", spaceDelimited(context.getAuthorizedScopes()));
            log.debug("User token issued without resolvable CloseAuth user id; roles/scopes not resolved");
            return;
        }
        UUID userId = maybeUserId.get();
        claims.subject(userId.toString()); // stable opaque sub
        claims.claim("idp", DEFAULT_IDP);

        ResolvedAuthorization resolved = principalAuthorizationService.resolve(TenantContext.of(tenantId), userId);
        claims.claim("roles", resolved.platformRoles());
        claims.claim("tenant_roles", resolved.tenantRoles());
        claims.claim("app_roles", resolved.appRoles().stream()
                .map(grant -> Map.<String, Object>of("rs", grant.rs(), "roles", grant.roles()))
                .toList());

        // Effective scope = the requested OIDC/platform scopes + the resolved slug-prefixed RS scopes.
        Set<String> scopes = new LinkedHashSet<>(context.getAuthorizedScopes());
        scopes.addAll(resolved.scopes());
        claims.claim("scope", spaceDelimited(scopes));
    }

    private List<String> resolveAudiences(String clientRegisteredId) {
        List<UUID> rsIds = clientAuthorizedResourceServerRepository.findByClientRegisteredId(clientRegisteredId).stream()
                .map(link -> link.getResourceServerId())
                .toList();
        return resourceServerRepository.findAllById(rsIds).stream()
                .map(ResourceServer::getAudienceIdentifier)
                .sorted()
                .toList();
    }

    private java.util.Optional<UUID> parseUserId(JwtEncodingContext context) {
        if (context.getPrincipal() == null || context.getPrincipal().getName() == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(UUID.fromString(context.getPrincipal().getName()));
        } catch (IllegalArgumentException notAUuid) {
            return java.util.Optional.empty();
        }
    }

    private static String spaceDelimited(Set<String> values) {
        return values == null ? "" : String.join(" ", new ArrayList<>(values));
    }
}
