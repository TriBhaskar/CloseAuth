package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.rbac.dto.ResolvedAuthorization;
import com.anterka.closeauthbackend.rbac.dto.ResolvedAuthorization.AppRoleGrant;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRole;
import com.anterka.closeauthbackend.rbac.entity.ApplicationRoleScope;
import com.anterka.closeauthbackend.rbac.entity.PlatformRole;
import com.anterka.closeauthbackend.rbac.entity.TenantRole;
import com.anterka.closeauthbackend.rbac.entity.UserApplicationRole;
import com.anterka.closeauthbackend.rbac.entity.UserPlatformRole;
import com.anterka.closeauthbackend.rbac.entity.UserTenantRole;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.ApplicationRoleScopeRepository;
import com.anterka.closeauthbackend.rbac.repository.PlatformRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.TenantRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserApplicationRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserPlatformRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserTenantRoleRepository;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves a user's effective authorization (roles + scopes) within a tenant — the <b>Stage 4 integration
 * point</b>. Stage 4 maps the returned {@link ResolvedAuthorization} into JWT claims (§12); this service does
 * NOT stamp tokens. Pure read ({@code @Transactional(readOnly = true)}).
 *
 * <p><b>The slug-vs-audience distinction (easy to get wrong):</b>
 * <ul>
 *   <li>{@code app_roles[].rs} = the Resource Server's <b>audience URI</b> ({@link ResourceServer#getAudienceIdentifier()}).</li>
 *   <li>{@code scope} entries are prefixed with the RS <b>slug</b> ({@link ResourceServer#getSlug()}):
 *       {@code {rs_slug}:{scope_name}}. Scope names are stored bare (Stage 1); the slug prefix is applied HERE.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PrincipalAuthorizationService {

    private final UserPlatformRoleRepository userPlatformRoleRepository;
    private final PlatformRoleRepository platformRoleRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final TenantRoleRepository tenantRoleRepository;
    private final UserApplicationRoleRepository userApplicationRoleRepository;
    private final ApplicationRoleRepository applicationRoleRepository;
    private final ApplicationRoleScopeRepository applicationRoleScopeRepository;
    private final ResourceServerRepository resourceServerRepository;
    private final ResourceServerScopeRepository resourceServerScopeRepository;

    /**
     * Resolves all claim material for {@code userId} within the tenant of {@code context}, across every Resource
     * Server the user holds roles in. Stage 4 may filter {@code app_roles}/{@code scope} to a specific target
     * audience when issuing a token for one RS.
     */
    @Transactional(readOnly = true)
    public ResolvedAuthorization resolve(TenantContext context, UUID userId) {
        UUID tenantId = context.tenantId();

        List<String> platformRoles = resolvePlatformRoles(userId);
        List<String> tenantRoles = resolveTenantRoles(userId, tenantId);

        // Application roles + scopes, grouped by Resource Server.
        List<UserApplicationRole> userAppRoles = userApplicationRoleRepository.findByUserId(userId).stream()
                .filter(uar -> tenantId.equals(uar.getTenantId()))
                .toList();
        Map<UUID, List<UserApplicationRole>> byResourceServer = userAppRoles.stream()
                .collect(Collectors.groupingBy(UserApplicationRole::getResourceServerId));

        List<AppRoleGrant> appRoles = new ArrayList<>();
        Set<String> scopes = new LinkedHashSet<>();

        for (Map.Entry<UUID, List<UserApplicationRole>> entry : byResourceServer.entrySet()) {
            ResourceServer rs = resourceServerRepository.findById(entry.getKey()).orElse(null);
            if (rs == null) {
                continue; // defensive: RS deleted out from under a stale assignment
            }
            List<UUID> appRoleIds = entry.getValue().stream()
                    .map(UserApplicationRole::getApplicationRoleId)
                    .toList();

            List<String> roleNames = applicationRoleRepository.findAllById(appRoleIds).stream()
                    .map(ApplicationRole::getName)
                    .sorted()
                    .toList();
            appRoles.add(new AppRoleGrant(rs.getAudienceIdentifier(), roleNames)); // rs = AUDIENCE URI

            // Effective scopes: union of each role's bundle, prefixed with the RS SLUG.
            for (UUID appRoleId : appRoleIds) {
                List<UUID> scopeIds = applicationRoleScopeRepository.findByApplicationRole_Id(appRoleId).stream()
                        .map(ApplicationRoleScope::getResourceServerScopeId)
                        .toList();
                for (ResourceServerScope scope : resourceServerScopeRepository.findAllById(scopeIds)) {
                    scopes.add(rs.getSlug() + ":" + scope.getScopeName()); // SLUG prefix, not the audience
                }
            }
        }

        appRoles.sort(Comparator.comparing(AppRoleGrant::rs));
        return new ResolvedAuthorization(platformRoles, tenantRoles, appRoles, new ArrayList<>(scopes));
    }

    private List<String> resolvePlatformRoles(UUID userId) {
        List<UUID> ids = userPlatformRoleRepository.findByUserId(userId).stream()
                .map(UserPlatformRole::getPlatformRoleId)
                .toList();
        return platformRoleRepository.findAllById(ids).stream()
                .map(PlatformRole::getName)
                .sorted()
                .toList();
    }

    private List<String> resolveTenantRoles(UUID userId, UUID tenantId) {
        List<UUID> ids = userTenantRoleRepository.findByUserIdAndTenantId(userId, tenantId).stream()
                .map(UserTenantRole::getTenantRoleId)
                .toList();
        return tenantRoleRepository.findAllById(ids).stream()
                .filter(role -> role.getTenantId().equals(tenantId))
                .map(TenantRole::getName)
                .sorted()
                .toList();
    }
}
