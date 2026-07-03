package com.anterka.closeauthbackend.token.config;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.rbac.dto.ResolvedAuthorization;
import com.anterka.closeauthbackend.rbac.service.PrincipalAuthorizationService;
import com.anterka.closeauthbackend.resourceserver.entity.ClientAuthorizedResourceServer;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stamps the CloseAuth §12 claim set onto issued JWTs. Sources authorization material from
 * {@link PrincipalAuthorizationService#resolve} (never recomputing roles/scopes here).
 *
 * <p>Correctness points (§12, and decisions confirmed in 3c-ii):
 * <ul>
 *   <li><b>{@code sub} = the user's UUID</b>, never username/email (stable opaque id). The old code's
 *       {@code sub=username} was a bug. For user tokens the principal name is expected to be the user UUID
 *       (Stage 6 login sets it); we set {@code sub} explicitly from it.</li>
 *   <li><b>{@code aud} = the Resource Server audience URI(s) whose scopes were actually requested</b> (see
 *       {@link #resolveAudiences}), never the {@code client_id} and never over-broadened to every RS the client
 *       could target.</li>
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
            // Identity only: tenant_id (above) + a stable-UUID sub. No scope/roles. (ID-token aud=client_id is
            // OIDC-correct and left to SAS.)
            parseUserId(context).ifPresent(userId -> claims.subject(userId.toString()));
            return;
        }
        if (!isAccessToken) {
            return; // refresh tokens etc. — nothing to stamp
        }

        // ---- Access token ----
        claims.claim("client_id", client.getClientId());

        // The effective scope set the token carries: requested scopes, plus (for user tokens) the resolved
        // slug-prefixed RS scopes from the user's application roles.
        Set<String> effectiveScopes = new LinkedHashSet<>(context.getAuthorizedScopes());

        boolean isM2M = AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType());
        if (!isM2M) {
            Optional<UUID> maybeUserId = parseUserId(context);
            if (maybeUserId.isPresent() && tenantId != null) {
                UUID userId = maybeUserId.get();
                claims.subject(userId.toString()); // stable opaque sub
                claims.claim("idp", DEFAULT_IDP);

                ResolvedAuthorization resolved =
                        principalAuthorizationService.resolve(TenantContext.of(tenantId), userId);
                // NB: claim VALUES must be standard mutable collections (ArrayList/LinkedHashMap), never Java
                // immutable ones (List.of()/Stream.toList()/Map.of() → ImmutableCollections$*). SAS persists the
                // claims map through JdbcOAuth2AuthorizationService's allowlisted Jackson mapper, which cannot
                // deserialize ImmutableCollections$* on read-back (introspection would then 500). See §7.3 / 4b-ii.
                claims.claim("roles", new ArrayList<>(resolved.platformRoles()));
                claims.claim("tenant_roles", new ArrayList<>(resolved.tenantRoles()));
                claims.claim("app_roles", resolved.appRoles().stream()
                        .map(grant -> {
                            Map<String, Object> appRole = new LinkedHashMap<>();
                            appRole.put("rs", grant.rs());
                            appRole.put("roles", new ArrayList<>(grant.roles()));
                            return appRole;
                        })
                        .collect(Collectors.toCollection(ArrayList::new)));
                effectiveScopes.addAll(resolved.scopes());
            } else {
                // No CloseAuth user resolvable yet (e.g. before the Stage 6 login flow sets sub=user UUID).
                log.debug("User token issued without a resolvable CloseAuth user id; roles/scopes not resolved");
            }
        }

        claims.claim("scope", spaceDelimited(effectiveScopes));

        // aud = ONLY the resource server(s) whose scopes are actually in this token (least-privilege).
        List<String> audiences = resolveAudiences(client.getId(), effectiveScopes);
        if (!audiences.isEmpty()) {
            claims.audience(audiences); // replaces SAS's default aud=[client_id]
        }
    }

    /**
     * Resolves {@code aud} to exactly the Resource Servers targeted by this token — inferred from the RS-slug
     * prefixes of the effective scopes ({@code {rs_slug}:scope}). A token obtained for RS-A's scopes must NOT be
     * valid against RS-B; {@code aud} is a security boundary, so it is narrowed to the requested resource(s), not
     * broadened to every RS the client is authorized for.
     *
     * <p>Edge case: a request with no RS-scoped scope (e.g. pure OIDC {@code openid profile}) yields no RS
     * audience — such a token targets no resource API.
     *
     * <p>TODO(direction): RFC 8707 (Resource Indicators) is the standards-clean way for a client to explicitly
     * request a token for a specific resource. Scope-prefix inference is the MVP approximation of it; adopting
     * RFC 8707 is a later enhancement, not implemented now.
     */
    private List<String> resolveAudiences(String clientRegisteredId, Set<String> effectiveScopes) {
        Set<String> targetSlugs = effectiveScopes.stream()
                .filter(scope -> scope.indexOf(':') > 0)
                .map(scope -> scope.substring(0, scope.indexOf(':')))
                .collect(Collectors.toSet());
        if (targetSlugs.isEmpty()) {
            return new ArrayList<>();
        }
        List<UUID> authorizedRsIds = clientAuthorizedResourceServerRepository
                .findByClientRegisteredId(clientRegisteredId).stream()
                .map(ClientAuthorizedResourceServer::getResourceServerId)
                .toList();
        // ArrayList (not Stream.toList()): this list becomes the `aud` claim VALUE that SAS persists and must
        // deserialize on introspection — an immutable ImmutableCollections$ListN would break that read-back.
        return resourceServerRepository.findAllById(authorizedRsIds).stream()
                .filter(rs -> targetSlugs.contains(rs.getSlug()))
                .map(ResourceServer::getAudienceIdentifier)
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Optional<UUID> parseUserId(JwtEncodingContext context) {
        if (context.getPrincipal() == null || context.getPrincipal().getName() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(context.getPrincipal().getName()));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static String spaceDelimited(Set<String> values) {
        return values == null ? "" : String.join(" ", values);
    }
}
